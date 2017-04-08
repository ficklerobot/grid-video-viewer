/*
 *
 * Copyright 2015 FickleRobot LLC.
 *
 */

package com.ficklerobot.gridvideoviewer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Locale;

import android.app.Fragment;
import android.app.ProgressDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.media.MediaCodec;
import android.media.MediaCodec.BufferInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnCompletionListener;
import android.media.MediaPlayer.OnPreparedListener;
import android.media.ThumbnailUtils;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.provider.MediaStore;
import android.provider.MediaStore.Images;
import android.provider.MediaStore.Video;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.TextureView;
import android.view.TextureView.SurfaceTextureListener;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.view.animation.AnimationUtils;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ImageView.ScaleType;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RelativeLayout;
import android.widget.Toast;

import com.ficklerobot.gridvideoviewer.GalleryActivity.OnBackPressListener;

import static android.R.attr.id;

public class VideoGridFragment extends Fragment
        implements OnPreparedListener, SurfaceTextureListener, OnBackPressListener, OnCompletionListener {

    private static final String TAG = "VideoGrid";

    /** グリッドの列数 */
    private int mColCount;
    /** グリッドをタップした際のアクション種別 */
    private int mTapAction;

    private RelativeLayout mRootView;
    private ListView mVideoList;
    /** 拡大再生する用のView */
    private TextureView mPickedVideoView;
    private MediaPlayer mPlayer;

    private ArrayList<VideoData> mVideoUriList;
    private HashMap<Integer, DecoderSurface> mSurfaceSet;
    /** DecoderSurfaceの番号 */
    private int mSurfaceNumber;
    private ThumbnailCache mThumbnailCache;
    /** 画面サイズ */
    private int[] mWindowSize;

    private static DecoderSurface.DecodeQueueManager stQueueManager
            = new DecoderSurface.DecodeQueueManager();

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {

        Bundle args = this.getArguments();

        mColCount = args.getInt(GalleryActivity.EXT_COL_COUNT, 2);
        int playCount = args.getInt(GalleryActivity.EXT_PLAY_COUNT, 1);
        mTapAction = args.getInt(GalleryActivity.EXT_TAP_ACTION, MainActivity.TAP_ACTION_FLOAT);

        Context context = getActivity().getApplicationContext();
        mWindowSize = new int[2];
        DisplayMetrics display = context.getResources().getDisplayMetrics();
        mWindowSize[0] = display.widthPixels;
        mWindowSize[1] = display.heightPixels;

        mRootView = new RelativeLayout(context);
        RelativeLayout.LayoutParams listLayoutParams = new RelativeLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);

        mVideoList = new ListView(context);
        mVideoList.setDivider(null);

        mRootView.addView(mVideoList, listLayoutParams);

        mVideoUriList = new ArrayList<>();
        mThumbnailCache = new ThumbnailCache(context);
        mSurfaceSet = new HashMap<>();

        stQueueManager.setMaxRunCount(playCount);

        return mRootView;
    }

    @Override
    public void onResume() {
        super.onResume();

        synchronized (this) {
            mVideoUriList.clear();
            mThumbnailCache.clearCache();
            mSurfaceSet.clear();
        }

        loadVideoUrlList();

        PrepareVideoListTask task = new PrepareVideoListTask();
        task.execute(0);

        initPickupPlayer();
    }

    @Override
    public void onPause() {
        super.onPause();

        releaseVideos();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        releaseVideos();
    }

    /**
     * リリース処理<br>
     * onPause/onDestroyから呼び出され、動画を停止して各リソースをクリアする
     *
     */
    private void releaseVideos() {

        synchronized (this) {
            mThumbnailCache.clearCache();
            mVideoUriList.clear();

            for (DecoderSurface ds : mSurfaceSet.values()) {
                ds.release();
            }

            if (mPlayer != null) {
                try {
                    if (mPlayer.isPlaying()) {
                        mPlayer.stop();
                    }
                } catch (IllegalStateException e) {
                    e.printStackTrace();
                }

                try {
                    mPlayer.release();
                } catch (IllegalStateException e) {
                    e.printStackTrace();
                }
            }

            mPlayer = null;

            if (mPickedVideoView != null) {
                mRootView.removeView(mPickedVideoView);
                mPickedVideoView = null;
            }

            mSurfaceSet.clear();
        }

        stQueueManager.clear();
    }

    /**
     * メディアストアから動画リストを読み込む
     *
     */
    private void loadVideoUrlList() {

        ContentResolver resolver = getActivity().getApplicationContext().getContentResolver();

        String[] projection = {MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DATA,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.SIZE,
                Video.VideoColumns.MINI_THUMB_MAGIC};
        Cursor cursor = MediaStore.Video.query(resolver, Video.Media.EXTERNAL_CONTENT_URI, projection);

        if (cursor != null) {
            if (cursor.moveToFirst()) {
                int colId = cursor.getColumnIndex(MediaStore.Video.Media._ID);
                int colPath = cursor.getColumnIndex(MediaStore.MediaColumns.DATA);
                int colName = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME);

                do {

                    long id = cursor.getLong(colId);
                    String path = cursor.getString(colPath);
                    String name = cursor.getString(colName);
                    Log.d(TAG, "Load media id:" + id + " name:" + name + " path:" + path);

                    synchronized (this) {
                        mVideoUriList.add(new VideoData(name, Uri.parse(path), id));
                    }

                } while (cursor.moveToNext());
            }
        }

        mSurfaceNumber = 0;
    }

    /**
     * 動画リストのアダプター<br>
     * GridViewではなくListViewにグリッド表示を行うため、列数個分のViewを持ったリスト行Viewを作成している
     *
     */
    private class GridAdapter extends BaseAdapter
            implements OnClickListener {

        Context context;
        LayoutInflater inflater;
        /** 各セルのViewのサイズ。画面幅/列数 */
        int cellSize;
        /** 列数 */
        int colCount;

        /**
         *
         * @param context Context
         * @param colCount 列数
         */
        GridAdapter(Context context, int colCount) {
            this.context = context;
            inflater = LayoutInflater.from(context);

            this.colCount = colCount;
            cellSize = context.getResources().getDisplayMetrics().widthPixels / colCount;
        }

        @Override
        public int getCount() {
            return (int) Math.ceil((float) mVideoUriList.size() / colCount);
        }

        @Override
        public VideoData getItem(int position) {

            if (position < mVideoUriList.size()) {
                return mVideoUriList.get(position);
            }

            return null;
        }

        @Override
        public long getItemId(int position) {
            return 0;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder holder;

            if (convertView == null) {
                holder = new ViewHolder();

                convertView = inflater.inflate(R.layout.videolist_item, null, false);
                LinearLayout linearLayout = (LinearLayout) convertView;

                for (int i = 0; i < colCount; i++) {

                    RelativeLayout cellLayout = new RelativeLayout(context);

                    RelativeLayout.LayoutParams textureLayoutParams
                            = new RelativeLayout.LayoutParams(cellSize, cellSize);

                    holder.textureViews[i] = new TextureView(context);
                    holder.textureViews[i].setOnClickListener(this);
                    holder.textureViews[i].setLayoutParams(textureLayoutParams);

                    RelativeLayout.LayoutParams imageLayoutParams
                            = new RelativeLayout.LayoutParams(cellSize, cellSize);

                    holder.imageViews[i] = new ImageView(context);
                    holder.imageViews[i].setScaleType(ScaleType.CENTER_CROP);
                    holder.imageViews[i].setOnClickListener(this);
                    holder.imageViews[i].setLayoutParams(imageLayoutParams);

                    DecoderSurface ds = new DecoderSurface(
                            mSurfaceNumber++, holder.imageViews[i], holder.textureViews[i]);
                    holder.textureViews[i].setSurfaceTextureListener(ds);

                    int surfaceNum = position * colCount + i;
                    synchronized (VideoGridFragment.this) {
                        mSurfaceSet.put(surfaceNum, ds);
                    }

                    //TODO DecoderSurface, textureViews, imageViewsを内包したViewを作成する
                    holder.textureViews[i].setTag(ds);
                    holder.imageViews[i].setTag(ds);

                    cellLayout.addView(holder.textureViews[i]);
                    cellLayout.addView(holder.imageViews[i]);

                    linearLayout.addView(cellLayout);
                }

                convertView.setTag(holder);

            } else {
                holder = (ViewHolder) convertView.getTag();
            }

            for (int i = 0; i < colCount; i++) {
                int dataPosition = position * colCount + i;
                final VideoData data = getItem(dataPosition);

                Drawable thumb;
                Resources res = getResources();

                if (data != null) {
                    thumb = new BitmapDrawable(
                            res, mThumbnailCache.getBitmapFromMemCache(data)); //TODO 非同期でやる
                } else {
                    thumb = res.getDrawable(R.drawable.blank_panel);
                }

                holder.imageViews[i].setImageDrawable(thumb);
                holder.imageViews[i].clearAnimation();
                holder.imageViews[i].setAlpha(1.0f);

                final DecoderSurface ds =
                        (DecoderSurface) holder.textureViews[i].getTag();

                if (ds != null) {
                    ds.setVideoData(data);
                }
            }

            return convertView;
        }

        private class ViewHolder {
            TextureView[] textureViews;
            ImageView[] imageViews;

            ViewHolder() {
                textureViews = new TextureView[colCount];
                imageViews = new ImageView[colCount];
            }
        }

        @Override
        /**
         * セルがタップされた際の動作<br>
         * アクション種別に応じた形式で動画を再生する
         */
        public void onClick(View v) {
            DecoderSurface ds = (DecoderSurface) v.getTag();

            if (mTapAction == MainActivity.TAP_ACTION_FLOAT) {
                if (ds != null && ds.videoData != null) {
                    String filePath = ds.videoData.videoUri.getPath();
                    pickupVideo(filePath);
                }
            } else {
                ds.play(true);
            }
        }
    }

    /**
     * ピックアップ動画Viewを非表示にする
     */
    private void hidePickedVideo() {
        mPickedVideoView.clearAnimation();
        mPickedVideoView.setVisibility(View.GONE);
    }

    /**
     * ピックアップ再生用のViewとMediaPlayerを初期化する
     */
    private void initPickupPlayer() {
        mPlayer = new MediaPlayer();
        mPlayer.setOnPreparedListener(this);
        mPlayer.setOnCompletionListener(this);

        RelativeLayout.LayoutParams videoLayoutParams = new RelativeLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        videoLayoutParams.addRule(RelativeLayout.CENTER_IN_PARENT);

        Context context = getActivity().getApplicationContext();

        mPickedVideoView = new TextureView(context);
        mPickedVideoView.setSurfaceTextureListener(this);
        mPickedVideoView.setVisibility(View.GONE);

        mRootView.addView(mPickedVideoView, videoLayoutParams);
    }

    /**
     * 動画をピックアップ再生する
     *
     * @param filePath 動画のファイルパス
     */
    private void pickupVideo(String filePath) {
        if (filePath == null || mPlayer == null) {
            return;
        }

        try {
            if (mPlayer.isPlaying()) {
                mPlayer.stop();
            }
        } catch (java.lang.IllegalStateException e) {
            e.printStackTrace();
        }

        try {
            mPlayer.reset();
        } catch (java.lang.IllegalStateException e) {
            e.printStackTrace();
        }

        hidePickedVideo();

        try {
            mPlayer.setDataSource(filePath);
            mPlayer.prepare();

        } catch (Exception e) {
            e.printStackTrace();
            Log.d(TAG, "Failed to setDataSource file:" + filePath
                    + " msg:" + e.getMessage());

            Toast.makeText(getActivity(),
                    "動画の再生に失敗しました。再生数を減らしてください",
                    Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 画面サイズとビデオサイズからビューサイズを算出する
     *
     * @param videoWidth ビデオの幅
     * @param videoHeight ビデオの高さ
     * @return 縦横比を維持して、画面に収まるサイズに拡大した際のサイズ [0]:幅 [1]:高さ
     */
    private int[] calcVideoSize(int videoWidth, int videoHeight) {

        float basePar = 0.9f;

        int[] size = new int[2];

        float frameWidth = mWindowSize[0] * basePar;
        float frameHeight = mWindowSize[1] * basePar;

        float wPar = frameWidth / videoWidth;
        float hPar = frameHeight / videoHeight;
        float outPar;

        outPar = Math.min(wPar, hPar);

        size[0] = Math.round(videoWidth * outPar * basePar);
        size[1] = Math.round(videoHeight * outPar * basePar);

        return size;
    }

    private static class VideoData {
        Uri videoUri;
        String name;
        Matrix textureMatrix;
        long thumbId;

        VideoData(String name, Uri uri, long thumbId) {
            this.name = name;
            this.videoUri = uri;
            this.thumbId = thumbId;
            textureMatrix = null;
        }
    }

    /**
     * 動画リストの準備を行うsyncTask<br>
     * 動画のサムネイルを作成し、GridAdapterをListViewにセットする
     */
    private class PrepareVideoListTask extends AsyncTask<Integer, Integer, Integer> {

        ProgressDialog progress;

        PrepareVideoListTask() {
            progress = new ProgressDialog(getActivity());
            progress.setCancelable(false);
        }

        @Override
        protected void onPreExecute() {

            try {
                onProgressUpdate(0);
                progress.show();
            } catch (Exception e) {
                //Do nothing
            }
        }

        @Override
        protected Integer doInBackground(Integer... params) {

            for (int i = 0; i < mVideoUriList.size(); i++) {

                VideoData data = null;

                synchronized (mVideoUriList) {
                    if (i < mVideoUriList.size()) {
                        data = mVideoUriList.get(i);
                    }
                }

                if (data != null) {
                    mThumbnailCache.getBitmapFromMemCache(data);
                }

                publishProgress(i);
            }

            return 0;
        }

        @Override
        protected void onPostExecute(Integer result) {

            if (progress.isShowing()) {
                try {
                    progress.dismiss();
                } catch (Exception e) {
                    //Do nothing
                }
            }

            if (!isCancelled()) {
                updateGrid();
            }
        }

        @Override
        protected void onProgressUpdate(Integer... values) {

            String message = String.format(Locale.getDefault(),
                    "Making video thumbnails %d / %d", values[0], mVideoUriList.size());

            progress.setMessage(message);
        }

        private void updateGrid() {
            Context context = getActivity().getApplicationContext();
            GridAdapter adapter = new GridAdapter(context, mColCount);
            mVideoList.setAdapter(adapter);
        }
    }

    private static class ThumbnailCache extends BitmapLruCache<VideoData> {
        private static final int CACHE_SIZE = 64 * 1024 * 1024;
        private Context context;

        ThumbnailCache(Context context) {
            super(CACHE_SIZE);
            this.context = context;
        }

        @Override
        protected Bitmap createBitmap(VideoData data) {
            ContentResolver resolver = context.getContentResolver();
            //MediaStoreから取得
            Bitmap thumbnail = MediaStore.Video.Thumbnails.getThumbnail(resolver, data.thumbId,
                    MediaStore.Images.Thumbnails.MINI_KIND, null);

            //取得できなかったら、作成する
            if (thumbnail == null) {
                thumbnail = ThumbnailUtils.createVideoThumbnail(
                        data.videoUri.getPath(), Images.Thumbnails.MINI_KIND);
            }

            return thumbnail;
        }
    }

    private static class DecoderSurface implements SurfaceTextureListener {
        /** デコード処理のタイムアウト時間(マイクロ秒) */
        private static final int BUFFER_TIMEOUT_USEC = 100000;
        /** 動画をループ再生するか */
        private static final boolean DO_LOOP_VIDEO = false;

        /** サムネイル画像表示用 */
        private ImageView imageView;
        /** 動画再生用 */
        private TextureView textureView;
        private DecodeThread decodeThread = null;
        private VideoData videoData;

        /**
         * DecoderSurfaceを一意に区別するID
         */
        private int surfaceNumber;

        /**
         * @param surfaceNumber 識別用番号
         * @param imageView ImageView
         */
        DecoderSurface(int surfaceNumber, ImageView imageView, TextureView textureView) {
            this.imageView = imageView;
            this.textureView = textureView;
            this.surfaceNumber = surfaceNumber;
        }

        void setVideoData(VideoData videoData) {
            this.videoData = videoData;
            play(false);
        }

        /**
         * 動画を再生する
         * @param interrupt true:割り込んで再生する false:再生キューに追加する
         */
        private void play(boolean interrupt) {
            Log.d(TAG, "play :" + surfaceNumber);

            if (decodeThread != null && decodeThread.isAlive()) {
                decodeThread.setVideoData(videoData);
            }

            if (interrupt) {
                stQueueManager.interrupt(decodeThread);
            }
        }

        void release() {
            videoData = null;

            if (decodeThread != null && decodeThread.isAlive()) {
                decodeThread.stopDecode();
            }

            decodeThread = null;
        }

        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface,
                                              int width, int height) {
            decodeThread = new DecodeThread(id, new DecodeHandler(),
                    new Surface(surface), stQueueManager, width);
            decodeThread.start();
            play(false);
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface,
                                                int width, int height) {
            //Do nothing
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
            release();
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {
            //Do nothing
        }

        private class DecodeHandler extends Handler {
            /**
             * デコード準備完了
             */
            static final int MSG_DECODE_READY = 1;
            /**
             * デコード開始
             */
            static final int MSG_DECODE_START = 2;
            /**
             * デコード失敗
             */
            static final int MSG_FAILED_TO_DECODE = 3;

            @Override
            public void handleMessage(Message msg) {

                switch (msg.what) {
                    case MSG_DECODE_READY:
                        Matrix mtx = (Matrix) msg.obj;
                        textureView.setTransform(mtx);
                        break;
                    case MSG_DECODE_START:
                        Log.d(TAG, "MSG_DECODE_START alpha:" + imageView.getAlpha());

                        if (imageView.getAlpha() == 1.0f) {
                            imageView.startAnimation(
                                    AnimationUtils.loadAnimation(imageView.getContext(),
                                            R.animator.fadeout_anim));
                        }
                        break;
                    case MSG_FAILED_TO_DECODE:
                        String text = (String) msg.obj;
                        Toast.makeText(imageView.getContext(),
                                text, Toast.LENGTH_SHORT).show();
                        break;
                }
            }
        }

        /**
         * デコードキューの管理クラス<br>
         * デコードスレッドのキューに持ち、同時再生数だけ順次取り出して実行する<br>
         *
         */
        private static class DecodeQueueManager {
            LinkedList<DecodeThread> waitQueue;
            /** 同時再生数 */
            int maxRunCount;

            DecodeQueueManager() {
                this.maxRunCount = 3;
                waitQueue = new LinkedList<DecodeThread>();
            }

            @Override
            synchronized public String toString() {
                StringBuffer bf = new StringBuffer();
                for (int i = 0; i < waitQueue.size(); i++) {
                    bf.append(String.valueOf(waitQueue.get(i).surfaceNumber) + ",");
                }

                return bf.toString();
            }

            /**
             * @param maxRunCount 同時再生数
             */
            void setMaxRunCount(int maxRunCount) {
                if (maxRunCount < 1) {
                    throw new java.lang.IllegalArgumentException(
                            "The max count must be over 1. :" + maxRunCount);
                }
                this.maxRunCount = maxRunCount;
            }

            synchronized void clear() {
                waitQueue.clear();
            }

            /**
             * 再生待ちキューの末尾に追加
             *
             * @param inDs DecodeThread
             */
            synchronized void offerDecoder(DecodeThread inDs) {
                if (!waitQueue.contains(inDs)) {
                    waitQueue.offer(inDs);
                }
            }

            /**
             * 再生待ちキューから削除
             *
             * @param inDs DecodeThread
             */
            synchronized void removeDecoder(DecodeThread inDs) {
                waitQueue.remove(inDs);

                synchronized (waitQueue) {
                    waitQueue.notifyAll();
                }
            }

            /**
             * 割り込んで再生する<br>
             * 動画再生数が最大数であれば、最初に再生された動画を停止して新たに動画を再生する
             *
             * @param inDs DecodeThread
             */
            synchronized void interrupt(DecodeThread inDs) {
                Log.d(TAG, "BEFORE interrupt id:" + inDs.surfaceNumber + " list:" + toString());

                waitQueue.remove(inDs);

                int insertIndex = maxRunCount;
                if (insertIndex >= waitQueue.size()) {
                    insertIndex = waitQueue.size();
                }

                waitQueue.add(insertIndex, inDs);

                DecodeThread headDs = waitQueue.poll();
                if (headDs != null) {
                    headDs.decodeDone = true;
                }

                Log.d(TAG, "AFTER interrupt id:" + inDs.surfaceNumber + " list:" + toString());
            }

            /**
             * いずれかの動画の再生が停止したら呼び出す<br>
             * notifyAll()を呼び出し、再生待ちスレッドが実行されるようにする
             */
            void notifyStop() {
                synchronized (waitQueue) {
                    waitQueue.notifyAll();
                }
            }

            /**
             * 再生できるようになるまで待つ<br>
             *
             * @param ds DecodeThread
             * @return true:順番が来たので再生可能 false:順番待ち
             */
            boolean waitForTurn(DecodeThread ds) {
                while (true) {
                    int index = waitQueue.indexOf(ds);

                    if (index < 0) {
                        return false;
                    } else if (index < maxRunCount) {
                        return true;
                    }

                    synchronized (waitQueue) {
                        try {
                            waitQueue.wait();
                        } catch (InterruptedException e) {
                            // nop
                        }
                    }
                }
            }
        }

        private static class DecodeThread extends Thread {
            /**
             * ファイルからのビデオ読み込みが完了したか否か
             */
            private boolean inputDone = false;
            /**
             * 末尾までデコードが完了した
             */
            private boolean decodeDone = false;
            /**
             * スレッドが停止されたか否か
             */
            private boolean isStopped = false;

            private VideoData videoData;

            private MediaExtractor extractor;
            private MediaCodec decoder;
            private BufferInfo bufferInfo;
            private Surface outSurface;
            private DecodeHandler handler;
            private DecodeQueueManager queueManager;
            /** 動画の再生サイズ(縦横) */
            private int outSize;
            /** DecoderSurfaceの番号 */
            private int surfaceNumber;

            private long startMs;

            /**
             *
             * @param surfaceNumber DecoderSurfaceの番号
             * @param handler DecodeHandler
             * @param outSurface 動画出力先Surface
             * @param queueManager DecodeQueueManager
             * @param outSize 動画の再生サイズ(縦横)
             */
            DecodeThread(int surfaceNumber, DecodeHandler handler, Surface outSurface,
                         DecodeQueueManager queueManager, int outSize) {
                this.setName("Thread_SF_" + surfaceNumber);

                this.surfaceNumber = surfaceNumber;
                this.outSurface = outSurface;
                this.handler = handler;
                this.queueManager = queueManager;
                this.outSize = outSize;
            }

            void setVideoData(VideoData videoData) {
                this.videoData = videoData;
                this.inputDone = true;
                this.decodeDone = true;
            }

            /**
             * 現在のfilePathを使ってExtractorを作成する
             */
            private boolean readyExtractor(String filePath) {
                boolean isOk = false;
                bufferInfo = new BufferInfo();

                try {
                    extractor = new MediaExtractor();
                    extractor.setDataSource(filePath);
                    isOk = true;

                } catch (IOException e) {
                    e.printStackTrace();
                    Log.d(TAG, "Failed to setDataSource id:" + id);
                    videoData = null;
                }

                return isOk;
            }

            /**
             * 動画を開始地点から末尾まで再生する
             * DO_LOOP_VIDEOがtrueの場合、ループ再生する
             */
            private void playVideo() {
                do {
                    extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                    inputDone = false;
                    decodeDone = false;

                    startMs = System.currentTimeMillis();

                    boolean isFirst = true;

                    while (!decodeDone && !isStopped) {
                        try {
                            decodeVideoFrame();
                        } catch (Exception e) {
                            handler.sendMessage(
                                    handler.obtainMessage(DecodeHandler.MSG_FAILED_TO_DECODE,
                                            "再生に失敗しました。再生数を減らしてください"));

                            Log.d(TAG, "Failed to playVideo id:" + surfaceNumber + " msg:" + e.getMessage());
                            break;
                        }

                        //前のVideoの画像が残っている場合があるので、
                        //数ミリ秒再生後にサムネイルを消す
                        if (bufferInfo.presentationTimeUs > 200 * 1000 && isFirst) {
                            handler.sendEmptyMessage(DecodeHandler.MSG_DECODE_START);
                            isFirst = false;
                        }
                    }

                } while (!isStopped && DO_LOOP_VIDEO);
            }

            /**
             * デコーダとextractorを停止し、破棄する
             */
            private void finishDecode() {

                if (decoder != null) {
                    try {
                        decoder.stop();
                    } catch (IllegalStateException e) {
                        e.printStackTrace();
                    }

                    decoder.release();
                    decoder = null;
                }

                if (extractor != null) {
                    extractor.release();
                    extractor = null;
                }
            }

            private void stopDecode() {
                Log.d(TAG, "stopDecode id:" + surfaceNumber);

                isStopped = true;
                videoData = null;
                queueManager.notifyStop();
            }

            @Override
            public void run() {
                isStopped = false;

                while (!isStopped) {
                    queueManager.offerDecoder(this);

                    if (queueManager.waitForTurn(this)) {
                        VideoData data = videoData;

                        if (data != null) {
                            String filePath = data.videoUri.getPath();

                            if (readyExtractor(filePath)) {
                                MediaFormat format = readyVideoDecoder();

                                if (format != null) {
                                    if (data.textureMatrix == null) {
                                        data.textureMatrix =
                                                makeTextureMatrix(filePath, format, outSize);
                                    }

                                    if (data == videoData) { //dataが変更されていたら再生しない
                                        handler.sendMessage(handler.obtainMessage(
                                                DecodeHandler.MSG_DECODE_READY,
                                                data.textureMatrix));

                                        playVideo();
                                    }

                                    finishDecode();
                                }
                            }
                        }
                    }

                    queueManager.removeDecoder(this);
                }
            }

            /**
             * ファイルからビデオを読み込み、デコードする
             */
            private void decodeVideoFrame() {
                // 読み込みが完了していなかったら、ビデオファイルを読み込み、デコーダにデータを挿入する
                if (!inputDone) {
                    extractVideoFile();
                    // TODO: returnする？
                }

                if (!decodeDone) {
                    decodeVideoBuffer();
                }
            }

            /**
             * ビデオファイルを読み込み、デコーダにデータを挿入する
             */
            private void extractVideoFile() {
                int decodeBufIndex = decoder.dequeueInputBuffer(BUFFER_TIMEOUT_USEC);

                if (decodeBufIndex >= 0) {
                    ByteBuffer buffer = getInputBuffer(decoder, decodeBufIndex);

                    int readLength = extractor.readSampleData(buffer, 0);
                    long sampleTime = extractor.getSampleTime();
                    int flags = extractor.getSampleFlags();

                    if (readLength < 0) {
                        // 読み込み完了
                        Log.d(TAG, "saw decode EOS.");
                        inputDone = true;

                        decoder.queueInputBuffer(decodeBufIndex, 0, 0,
                                sampleTime, MediaCodec.BUFFER_FLAG_END_OF_STREAM);

                    } else {
                        buffer.limit(readLength);
                        decoder.queueInputBuffer(decodeBufIndex, 0, readLength,
                                sampleTime, flags);
                        extractor.advance();
                    }
                }
            }

            private ByteBuffer getInputBuffer(MediaCodec codec, int bufferIndex) {
                ByteBuffer buffer;

                if (Build.VERSION.SDK_INT < 21) {
                    buffer = codec.getInputBuffers()[bufferIndex];
                    buffer.clear();
                } else {
                    buffer = codec.getInputBuffer(bufferIndex);
                }

                return buffer;
            }

            /**
             * デコーダを準備する
             */
            private MediaFormat readyVideoDecoder() {

                MediaFormat srcVideoFormat = selectTrack(extractor);

                Log.d(TAG, "startVideoDecoder format:" + srcVideoFormat);

                if (srcVideoFormat == null) {
                    return null;
                }

                try {
                    //TODO HW decoderがハングアップしている場合がある
                    //その場合createDecoderByTypeで止まってしまう
                    decoder = MediaCodec.createDecoderByType(
                            srcVideoFormat.getString(MediaFormat.KEY_MIME));

                    //decoder.configure( srcVideoFormat, null, null, 0 );
                    decoder.configure(srcVideoFormat, outSurface, null, 0);
                    decoder.start();

                } catch (Exception e) {
                    e.printStackTrace();
                    Log.d(TAG, "Failed to start decoder id:" + id);

                    if (decoder != null) {
                        decoder.release();
                    }

                    srcVideoFormat = null;

                    //TODO 同じファイルに対して規定回数 or 規定秒数エラーを起こした場合、
                    //あきらめる( filePathをNullにする )
                }

                return srcVideoFormat;
            }


            private MediaFormat selectTrack(MediaExtractor extractor) {
                int trackCount = extractor.getTrackCount();
                Log.d(TAG, "trackCount :" + trackCount);

                MediaFormat format;
                for (int i = 0; i < trackCount; i++) {
                    extractor.selectTrack(i);
                    format = extractor.getTrackFormat(i);
                    Log.d(TAG, "Track media format :" + format.toString());

                    String mime = format.getString(MediaFormat.KEY_MIME);
                    if (mime.startsWith("video/")) {
                        return format;
                    }
                }

                return null;
            }

            /**
             * 動画を出力サイズに変換するためのMatrixを生成する
             *
             * @param filePath 動画ファイルパス
             * @param decodeFormat MediaFormat
             * @param textureSize 動画の出力サイズ
             * @return 動画を出力サイズに変換するためのMatrix
             */
            private Matrix makeTextureMatrix(String filePath, MediaFormat decodeFormat,
                                             int textureSize) {
                long startTime = System.currentTimeMillis(); // 計測ログ用

                Matrix mtx = new Matrix();
                MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                retriever.setDataSource(filePath);

                // 動画の向きを取得
                String rotationVal = retriever.extractMetadata( //SDK 17から
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);

                int orientation = 0;
                if (rotationVal != null && TextUtils.isDigitsOnly(rotationVal)) {
                    orientation = Integer.valueOf(rotationVal);
                }

                // rotation-degreesがセットされている場合、デコーダが映像の回転を行うのでMatrixでは回転しない
                boolean isRotateWithDecoder = false;
                if (decodeFormat.containsKey("rotation-degrees")) {

                    int rotateDegree = decodeFormat.getInteger("rotation-degrees");
                    int r = (rotateDegree + 360) % 360;

                    if (r == 90 || r == 270) {
                        isRotateWithDecoder = true;
                    }
                }

                int rotate = (isRotateWithDecoder ? 0 : orientation); // 回転角
                float pivot = (float) textureSize / 2; // 回転する際の中心座標
                mtx.setRotate(rotate, pivot, pivot);

                int width = 0; // 動画のオリジナル幅
                int height = 0; // 動画のオリジナル高
                if (decodeFormat.containsKey(MediaFormat.KEY_WIDTH)) {
                    width = decodeFormat.getInteger(MediaFormat.KEY_WIDTH);
                }
                if (decodeFormat.containsKey(MediaFormat.KEY_HEIGHT)) {
                    height = decodeFormat.getInteger(MediaFormat.KEY_HEIGHT);
                }

                if (width > 0 && height > 0) {
                    if (orientation == 90 || orientation == 270) {
                        if (width > height) {
                            float scale = (float) width / height;
                            mtx.postScale(1.0f, scale, pivot, pivot);
                        } else {
                            float scale = (float) height / width;
                            mtx.postScale(scale, 1.0f, pivot, pivot);
                        }
                    } else {
                        if (width > height) {
                            float scale = (float) width / height;
                            mtx.postScale(scale, 1.0f, pivot, pivot);
                        } else {
                            float scale = (float) height / width;
                            mtx.postScale(1.0f, scale, pivot, pivot);
                        }
                    }
                }

                Log.d(TAG, "makeTextureMatrix cost:" + (System.currentTimeMillis() - startTime));

                return mtx;
            }

            /**
             * 動画をデコード(再生)する
             */
            private void decodeVideoBuffer() {
                // MediaCodecからデコード結果を受け取る
                int decodeStatus = decoder.dequeueOutputBuffer(bufferInfo, BUFFER_TIMEOUT_USEC);

                if (checkDecoderStatus(decodeStatus)) {
                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
                            != 0) {
                        // コンフィグ部分を読み込んだ( 未だデコードは行っていない )
                        Log.d(TAG, "decoder configured (" + bufferInfo.size + " bytes)");
                    } else if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            != 0) {
                        // 末尾までデコードされた
                        Log.d(TAG, "Decoder gets BUFFER_FLAG_END_OF_STREAM. ");
                        decodeDone = true;
                    } else if (bufferInfo.presentationTimeUs > 0) {
                        //( 動画のタイムスタンプ > 実際の経過時間 )になるまで待つ
                        while (bufferInfo.presentationTimeUs / 1000 >
                                System.currentTimeMillis() - startMs) {
                            try {
                                Thread.sleep(10);
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                        }

                        // デコードされたバッファをサーフィスに送信(動画の再生)
                        decoder.releaseOutputBuffer(decodeStatus, true);
                    }
                }
            }

            /**
             * MediaCodec#dequeueOutputBuffer()の戻り値のチェック
             *
             * @param decoderStatus MediaCodec#dequeueOutputBuffer()の戻り値
             * @return true: デコード処理が行われた
             */
            private boolean checkDecoderStatus(int decoderStatus) {
                if (decoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // dequeueOutputBufferの呼び出しがタイムアウト
                    // TODO inputDoneしてからequeueOutputBufferを呼び出す？
                    if (inputDone) {
                        Log.d(TAG, "no output from decoder available BUT the input is done.");
                    }
                } else if (decoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    Log.d(TAG, "decoder output buffers changed");
                } else if (decoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    Log.d(TAG, "decoder output format changed");
                } else if (decoderStatus < 0) {
                    Log.d(TAG, "unexpected result from encoder.dequeueOutputBuffer: "
                            + decoderStatus);
                } else {
                    return true;
                }

                return false;
            }
        }
    }


    /**
     * ピックアップビデオ準備完了
     */
    @Override
    public void onPrepared(MediaPlayer mp) {
        int height = mp.getVideoHeight();
        int width = mp.getVideoWidth();

        int[] outSize = calcVideoSize(width, height);

        ViewGroup.LayoutParams layoutParams = mPickedVideoView.getLayoutParams();
        layoutParams.width = outSize[0];
        layoutParams.height = outSize[1];

        mRootView.requestLayout();

        mp.start();

        Handler handler = new Handler();
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                mPickedVideoView.setVisibility(View.VISIBLE);
                mPickedVideoView.startAnimation(
                        AnimationUtils.loadAnimation(
                                getActivity().getApplicationContext(), R.animator.fadein_anim));
            }
        }, 200);
    }

    /**
     * ピックアップビデオを末尾まで再生
     */
    @Override
    public void onCompletion(MediaPlayer mp) {
        try {
            mp.seekTo(0);
            mp.start();
        } catch (IllegalStateException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        Log.d(TAG, "onSurfaceTextureAvailable w:" + width + " h:" + height);
        mPlayer.setSurface(new Surface(surface));
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        Log.d(TAG, "onSurfaceTextureSizeChanged w:" + width + " h:" + height);
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        Log.d(TAG, "onSurfaceTextureDestroyed");
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
        // nop
    }

    @Override
    public boolean onBackPressed() {
        boolean doContinue = true;

        try {
            if (mPlayer != null && mPlayer.isPlaying()) {
                mPlayer.stop();
                doContinue = false;
            }

            hidePickedVideo();
        } catch (java.lang.IllegalStateException e) {
            e.printStackTrace();
        }

        return doContinue;
    }
}
