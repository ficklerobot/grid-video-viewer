package com.ficklerobot.gridvideoviewer;

import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.net.Uri;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;
import android.widget.Toast;

import static android.R.attr.id;

/**
 * 動画データ、動画のデコード処理、動画表示Viewをまとめたクラス
 */
class DecoderSurface implements TextureView.SurfaceTextureListener {
    private static final String TAG = "VideoGrid";

    /** サムネイル画像表示用 */
    private ImageView imageView;
    /** 動画再生用 */
    private TextureView textureView;
    private DecodeThread decodeThread = null;
    private VideoData videoData;
    private DecodeQueueManager mManager;

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
        mManager = DecodeQueueManager.getInstance();
    }

    void setVideoData(VideoData videoData) {
        this.videoData = videoData;
        play(false);
    }

    VideoData getVideoData(){
        return videoData;
    }

    /**
     * 動画を再生する
     * @param interrupt true:割り込んで再生する false:再生キューに追加する
     */
    void play(boolean interrupt) {
        Log.d(TAG, "play :" + surfaceNumber);

        if (decodeThread != null && decodeThread.isAlive()) {
            decodeThread.setVideoData(videoData);
        }

        if (interrupt) {
            mManager.interrupt(decodeThread);
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
                new Surface(surface), mManager, width);
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

    class DecodeHandler extends Handler {
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
                                        R.anim.fadeout_anim));
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

    static class VideoData {
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
}