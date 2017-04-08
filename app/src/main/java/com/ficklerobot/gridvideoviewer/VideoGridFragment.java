package com.ficklerobot.gridvideoviewer;

import android.annotation.SuppressLint;
import android.app.Fragment;
import android.app.ProgressDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnCompletionListener;
import android.media.MediaPlayer.OnPreparedListener;
import android.media.ThumbnailUtils;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.provider.MediaStore;
import android.provider.MediaStore.Images;
import android.provider.MediaStore.Video;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.SparseArray;
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

import java.util.ArrayList;
import java.util.Locale;

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

    private ArrayList<DecoderSurface.VideoData> mVideoUriList;
    private SparseArray<DecoderSurface> mSurfaceArray;
    /** DecoderSurfaceの番号 */
    private int mSurfaceNumber;
    private ThumbnailCache mThumbnailCache;
    /** 画面サイズ */
    private int[] mWindowSize;

    private DecodeQueueManager mQueueManager;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mQueueManager = DecodeQueueManager.getInstance();
    }

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
        mSurfaceArray = new SparseArray<>();

        mQueueManager.setMaxRunCount(playCount);

        return mRootView;
    }

    @Override
    public void onResume() {
        super.onResume();

        synchronized (this) {
            mVideoUriList.clear();
            mThumbnailCache.clearCache();
            mSurfaceArray.clear();
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

            for(int i = 0; i < mSurfaceArray.size(); i++) {
                int key = mSurfaceArray.keyAt(i);
                mSurfaceArray.get(key).release();
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

            mSurfaceArray.clear();
        }

        mQueueManager.clear();
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
                        mVideoUriList.add(new DecoderSurface.VideoData(name, Uri.parse(path), id));
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
        public DecoderSurface.VideoData getItem(int position) {

            if (position < mVideoUriList.size()) {
                return mVideoUriList.get(position);
            }

            return null;
        }

        @Override
        public long getItemId(int position) {
            return 0;
        }

        @SuppressLint("InflateParams")
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
                        mSurfaceArray.put(surfaceNum, ds);
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
                final DecoderSurface.VideoData data = getItem(dataPosition);

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
                if (ds != null && ds.getVideoData() != null) {
                    String filePath = ds.getVideoData().videoUri.getPath();
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

                DecoderSurface.VideoData data = null;

                synchronized (this) {
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

    private static class ThumbnailCache extends BitmapLruCache<DecoderSurface.VideoData> {
        private static final int CACHE_SIZE = 64 * 1024 * 1024;
        private Context context;

        ThumbnailCache(Context context) {
            super(CACHE_SIZE);
            this.context = context;
        }

        @Override
        protected Bitmap createBitmap(DecoderSurface.VideoData data) {
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
                                getActivity().getApplicationContext(), R.anim.fadein_anim));
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
