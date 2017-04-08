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
    private boolean inputDone = false;
    /**
     * 末尾までデコードが完了した
     */
    private boolean decodeDone = false;
    /**
     * スレッドが停止されたか否か
     */
    private boolean isStopped = false;

    private DecoderSurface.VideoData videoData;

    private MediaExtractor extractor;
    private MediaCodec decoder;
    private MediaCodec.BufferInfo bufferInfo;
    private Surface outSurface;
    private DecoderSurface.DecodeHandler handler;
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
    DecodeThread(int surfaceNumber, DecoderSurface.DecodeHandler handler, Surface outSurface,
                 DecodeQueueManager queueManager, int outSize) {
        this.setName("Thread_SF_" + surfaceNumber);

        this.surfaceNumber = surfaceNumber;
        this.outSurface = outSurface;
        this.handler = handler;
        this.queueManager = queueManager;
        this.outSize = outSize;
    }

    void setDecodeDone(boolean decodeDone) {
        this.decodeDone = decodeDone;
    }

    void setVideoData(DecoderSurface.VideoData videoData) {
        this.videoData = videoData;
        this.inputDone = true;
        this.decodeDone = true;
    }

    int getSurfaceNumber() {
        return surfaceNumber;
    }

    /**
     * 現在のfilePathを使ってExtractorを作成する
     */
    private boolean readyExtractor(String filePath) {
        boolean isOk = false;
        bufferInfo = new MediaCodec.BufferInfo();

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
                            handler.obtainMessage(DecoderSurface.DecodeHandler.MSG_FAILED_TO_DECODE,
                                    "再生に失敗しました。再生数を減らしてください"));

                    Log.d(TAG, "Failed to playVideo id:" + surfaceNumber + " msg:" + e.getMessage());
                    break;
                }

                //前のVideoの画像が残っている場合があるので、
                //数ミリ秒再生後にサムネイルを消す
                if (bufferInfo.presentationTimeUs > 200 * 1000 && isFirst) {
                    handler.sendEmptyMessage(DecoderSurface.DecodeHandler.MSG_DECODE_START);
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

    void stopDecode() {
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
                DecoderSurface.VideoData data = videoData;

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
                                        DecoderSurface.DecodeHandler.MSG_DECODE_READY,
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
