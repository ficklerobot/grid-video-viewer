package com.ficklerobot.gridvideoviewer;

import android.graphics.Matrix;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;

import static android.R.attr.id;

/**
 * デコード処理を行うスレッド
 */
class DecodeThread extends Thread {
    private static final String TAG = "VideoGrid";
    /** デコード処理のタイムアウト時間(マイクロ秒) */
    private static final int BUFFER_TIMEOUT_USEC = 100000;
    /** 動画をループ再生するか */
    private static final boolean DO_LOOP_VIDEO = false;
    /**
     * ファイルからのビデオ読み込みが完了したか否か
     */
    private boolean mInputDone = false;
    /**
     * 末尾までデコードが完了した
     */
    private boolean mDecodeDone = false;
    /**
     * スレッドが停止されたか否か
     */
    private boolean mIsStopped = false;

    private DecoderSurface.VideoData mVideoData;

    private MediaExtractor mExtractor;
    private MediaCodec mDecoder;
    private MediaCodec.BufferInfo mBufferinfo;
    private Surface mOutSurface;
    private DecoderSurface.DecodeHandler mHandler;
    private DecodeQueueManager mQueueManager;
    /** 動画の再生サイズ(縦横) */
    private int mOutSize;
    /** DecoderSurfaceの番号 */
    private int mSurfaceNumber;

    private long mStartMs;

    /**
     *
     * @param surfaceNumber DecoderSurfaceの番号
     * @param handler DecodeHandler
     * @param outSurface 動画出力先Surface
     * @param queueManager DecodeQueueManager
     * @param outSize 動画の再生サイズ(縦横)
     */
    DecodeThread(int surfaceNumber, DecoderSurface.DecodeHandler handler, Surface outSurface,
                 DecodeQueueManager queueManager, int outSize) {
        this.setName("Thread_SF_" + surfaceNumber);

        this.mSurfaceNumber = surfaceNumber;
        this.mOutSurface = outSurface;
        this.mHandler = handler;
        this.mQueueManager = queueManager;
        this.mOutSize = outSize;
    }

    void setDecodeDone(boolean decodeDone) {
        this.mDecodeDone = decodeDone;
    }

    void setVideoData(DecoderSurface.VideoData videoData) {
        this.mVideoData = videoData;
        this.mInputDone = true;
        this.mDecodeDone = true;
    }

    int getSurfaceNumber() {
        return mSurfaceNumber;
    }

    /**
     * 現在のfilePathを使ってExtractorを作成する
     */
    private boolean readyExtractor(String filePath) {
        boolean isOk = false;
        mBufferinfo = new MediaCodec.BufferInfo();

        try {
            mExtractor = new MediaExtractor();
            mExtractor.setDataSource(filePath);
            isOk = true;

        } catch (IOException e) {
            e.printStackTrace();
            Log.d(TAG, "Failed to setDataSource id:" + id);
            mVideoData = null;
        }

        return isOk;
    }

    /**
     * 動画を開始地点から末尾まで再生する
     * DO_LOOP_VIDEOがtrueの場合、ループ再生する
     */
    private void playVideo() {
        do {
            mExtractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
            mInputDone = false;
            mDecodeDone = false;

            mStartMs = System.currentTimeMillis();

            boolean isFirst = true;

            while (!mDecodeDone && !mIsStopped) {
                try {
                    decodeVideoFrame();
                } catch (Exception e) {
                    mHandler.sendMessage(
                            mHandler.obtainMessage(DecoderSurface.DecodeHandler.MSG_FAILED_TO_DECODE,
                                    "再生に失敗しました。再生数を減らしてください"));

                    Log.d(TAG, "Failed to playVideo id:" + mSurfaceNumber + " msg:" + e.getMessage());
                    break;
                }

                //前のVideoの画像が残っている場合があるので、
                //数ミリ秒再生後にサムネイルを消す
                if (mBufferinfo.presentationTimeUs > 200 * 1000 && isFirst) {
                    mHandler.sendEmptyMessage(DecoderSurface.DecodeHandler.MSG_DECODE_START);
                    isFirst = false;
                }
            }

        } while (!mIsStopped && DO_LOOP_VIDEO);
    }

    /**
     * デコーダとextractorを停止し、破棄する
     */
    private void finishDecode() {

        if (mDecoder != null) {
            try {
                mDecoder.stop();
            } catch (IllegalStateException e) {
                e.printStackTrace();
            }

            mDecoder.release();
            mDecoder = null;
        }

        if (mExtractor != null) {
            mExtractor.release();
            mExtractor = null;
        }
    }

    void stopDecode() {
        Log.d(TAG, "stopDecode id:" + mSurfaceNumber);

        mIsStopped = true;
        mVideoData = null;
        mQueueManager.notifyStop();
    }

    @Override
    public void run() {
        mIsStopped = false;

        while (!mIsStopped) {
            mQueueManager.offerDecoder(this);

            if (mQueueManager.waitForTurn(this)) {
                DecoderSurface.VideoData data = mVideoData;

                if (data != null) {
                    String filePath = data.videoUri.getPath();

                    if (readyExtractor(filePath)) {
                        MediaFormat format = readyVideoDecoder();

                        if (format != null) {
                            if (data.textureMatrix == null) {
                                data.textureMatrix =
                                        makeTextureMatrix(filePath, format, mOutSize);
                            }

                            if (data == mVideoData) { //dataが変更されていたら再生しない
                                mHandler.sendMessage(mHandler.obtainMessage(
                                        DecoderSurface.DecodeHandler.MSG_DECODE_READY,
                                        data.textureMatrix));

                                playVideo();
                            }

                            finishDecode();
                        }
                    }
                }
            }

            mQueueManager.removeDecoder(this);
        }
    }

    /**
     * ファイルからビデオを読み込み、デコードする
     */
    private void decodeVideoFrame() {
        // 読み込みが完了していなかったら、ビデオファイルを読み込み、デコーダにデータを挿入する
        if (!mInputDone) {
            extractVideoFile();
        }

        if (!mDecodeDone) {
            decodeVideoBuffer();
        }
    }

    /**
     * ビデオファイルを読み込み、デコーダにデータを挿入する
     */
    private void extractVideoFile() {
        int decodeBufIndex = mDecoder.dequeueInputBuffer(BUFFER_TIMEOUT_USEC);

        if (decodeBufIndex >= 0) {
            ByteBuffer buffer = getInputBuffer(mDecoder, decodeBufIndex);

            int readLength = mExtractor.readSampleData(buffer, 0);
            long sampleTime = mExtractor.getSampleTime();
            int flags = mExtractor.getSampleFlags();

            if (readLength < 0) {
                // 読み込み完了
                Log.d(TAG, "saw decode EOS.");
                mInputDone = true;

                mDecoder.queueInputBuffer(decodeBufIndex, 0, 0,
                        sampleTime, MediaCodec.BUFFER_FLAG_END_OF_STREAM);

            } else {
                buffer.limit(readLength);
                mDecoder.queueInputBuffer(decodeBufIndex, 0, readLength,
                        sampleTime, flags);
                mExtractor.advance();
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

        MediaFormat srcVideoFormat = selectTrack(mExtractor);

        Log.d(TAG, "startVideoDecoder format:" + srcVideoFormat);

        if (srcVideoFormat == null) {
            return null;
        }

        try {
            //TODO HW decoderがハングアップしている場合がある
            //その場合createDecoderByTypeで止まってしまう
            mDecoder = MediaCodec.createDecoderByType(
                    srcVideoFormat.getString(MediaFormat.KEY_MIME));

            //mDecoder.configure( srcVideoFormat, null, null, 0 );
            mDecoder.configure(srcVideoFormat, mOutSurface, null, 0);
            mDecoder.start();

        } catch (Exception e) {
            e.printStackTrace();
            Log.d(TAG, "Failed to start mDecoder id:" + id);

            if (mDecoder != null) {
                mDecoder.release();
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
        int decodeStatus = mDecoder.dequeueOutputBuffer(mBufferinfo, BUFFER_TIMEOUT_USEC);

        if (checkDecoderStatus(decodeStatus)) {
            if ((mBufferinfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
                    != 0) {
                // コンフィグ部分を読み込んだ( 未だデコードは行っていない )
                Log.d(TAG, "mDecoder configured (" + mBufferinfo.size + " bytes)");
            } else if ((mBufferinfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    != 0) {
                // 末尾までデコードされた
                Log.d(TAG, "Decoder gets BUFFER_FLAG_END_OF_STREAM. ");
                mDecodeDone = true;
            } else if (mBufferinfo.presentationTimeUs > 0) {
                //( 動画のタイムスタンプ > 実際の経過時間 )になるまで待つ
                while (mBufferinfo.presentationTimeUs / 1000 >
                        System.currentTimeMillis() - mStartMs) {
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }

                // デコードされたバッファをサーフィスに送信(動画の再生)
                mDecoder.releaseOutputBuffer(decodeStatus, true);
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
            if (mInputDone) {
                Log.d(TAG, "no output from mDecoder available BUT the input is done.");
            }
        } else if (decoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
            Log.d(TAG, "mDecoder output buffers changed");
        } else if (decoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            Log.d(TAG, "mDecoder output format changed");
        } else if (decoderStatus < 0) {
            Log.d(TAG, "unexpected result from encoder.dequeueOutputBuffer: "
                    + decoderStatus);
        } else {
            return true;
        }

        return false;
    }
}
