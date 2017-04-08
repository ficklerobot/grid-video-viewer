package com.ficklerobot.gridvideoviewer;

import android.util.Log;

import java.util.LinkedList;

/**
 * デコードキューの管理クラス<br>
 * デコードスレッドのキューに持ち、同時再生数だけ順次取り出して実行する<br>
 */
class DecodeQueueManager {
    private static final String TAG = "VideoGrid";
    private static final int DEFAULT_MAX_RUN_COUNT = 3;
    private final LinkedList<DecodeThread> mWaitQueue = new LinkedList<>();
    /** 同時再生数 */
    private int mMaxRunCount;

    private static DecodeQueueManager sMe;

    private DecodeQueueManager() {
        this.mMaxRunCount = DEFAULT_MAX_RUN_COUNT;
    }

    static DecodeQueueManager getInstance() {
        if(sMe == null){
            sMe = new DecodeQueueManager();
        }
        return sMe;
    }

    @Override
    synchronized public String toString() {
        StringBuilder bf = new StringBuilder();
        for(DecodeThread dec : mWaitQueue){
            bf.append(String.valueOf(dec.getSurfaceNumber())).append(",");
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
        this.mMaxRunCount = maxRunCount;
    }

    synchronized void clear() {
        mWaitQueue.clear();
    }

    /**
     * 再生待ちキューの末尾に追加
     *
     * @param inDs DecodeThread
     */
    synchronized void offerDecoder(DecodeThread inDs) {
        if (!mWaitQueue.contains(inDs)) {
            mWaitQueue.offer(inDs);
        }
    }

    /**
     * 再生待ちキューから削除
     *
     * @param inDs DecodeThread
     */
    synchronized void removeDecoder(DecodeThread inDs) {
        mWaitQueue.remove(inDs);

        synchronized (mWaitQueue) {
            mWaitQueue.notifyAll();
        }
    }

    /**
     * 割り込んで再生する<br>
     * 動画再生数が最大数であれば、最初に再生された動画を停止して新たに動画を再生する
     *
     * @param inDs DecodeThread
     */
    synchronized void interrupt(DecodeThread inDs) {
        Log.d(TAG, "BEFORE interrupt id:" + inDs.getSurfaceNumber() + " list:" + toString());

        mWaitQueue.remove(inDs);

        int insertIndex = mMaxRunCount;
        if (insertIndex >= mWaitQueue.size()) {
            insertIndex = mWaitQueue.size();
        }

        mWaitQueue.add(insertIndex, inDs);

        DecodeThread headDs = mWaitQueue.poll();
        if (headDs != null) {
            headDs.setDecodeDone(true);
        }

        Log.d(TAG, "AFTER interrupt id:" + inDs.getSurfaceNumber() + " list:" + toString());
    }

    /**
     * いずれかの動画の再生が停止したら呼び出す<br>
     * notifyAll()を呼び出し、再生待ちスレッドが実行されるようにする
     */
    void notifyStop() {
        synchronized (mWaitQueue) {
            mWaitQueue.notifyAll();
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
            int index = mWaitQueue.indexOf(ds);

            if (index < 0) {
                return false;
            } else if (index < mMaxRunCount) {
                return true;
            }

            synchronized (mWaitQueue) {
                try {
                    mWaitQueue.wait();
                } catch (InterruptedException e) {
                    // nop
                }
            }
        }
    }
}