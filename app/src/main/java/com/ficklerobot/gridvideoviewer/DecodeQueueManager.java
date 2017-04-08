package com.ficklerobot.gridvideoviewer;

import android.util.Log;

import java.util.LinkedList;

/**
 * デコードキューの管理クラス<br>
 * デコードスレッドのキューに持ち、同時再生数だけ順次取り出して実行する<br>
 *
 */
class DecodeQueueManager {
    private static final String TAG = "VideoGrid";
    private static int MAX_RUN_COUNT = 3;
    private LinkedList<DecodeThread> waitQueue;
    /** 同時再生数 */
    private int maxRunCount;

    private static DecodeQueueManager sMe;

    private DecodeQueueManager() {
        this.maxRunCount = MAX_RUN_COUNT;
        waitQueue = new LinkedList<>();
    }

    static DecodeQueueManager getInstance() {
        if(sMe == null){
            sMe = new DecodeQueueManager();
        }
        return sMe;
    }

    @Override
    synchronized public String toString() {
        StringBuffer bf = new StringBuffer();
        for (int i = 0; i < waitQueue.size(); i++) {
            bf.append(String.valueOf(waitQueue.get(i).getSurfaceNumber()) + ",");
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
        Log.d(TAG, "BEFORE interrupt id:" + inDs.getSurfaceNumber() + " list:" + toString());

        waitQueue.remove(inDs);

        int insertIndex = maxRunCount;
        if (insertIndex >= waitQueue.size()) {
            insertIndex = waitQueue.size();
        }

        waitQueue.add(insertIndex, inDs);

        DecodeThread headDs = waitQueue.poll();
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