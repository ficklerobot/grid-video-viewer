/*
 *
 * Copyright 2015 FickleRobot LLC.
 *
 */

package com.ficklerobot.gridvideoviewer;

import android.graphics.Bitmap;
import android.util.LruCache;

public abstract class BitmapLruCache<T> {

    protected LruCache<T, Bitmap> cache;

    public BitmapLruCache(int cacheSize) {

        cache = new LruCache<T, Bitmap>(cacheSize) {
            @Override
            protected int sizeOf(T id, Bitmap value) {
                return value.getByteCount();
            }

            @Override
            protected void entryRemoved(boolean evicted, T key, Bitmap oldValue,
                                        Bitmap newValue) {

                if (oldValue.isMutable()) {
                    oldValue.recycle();
                }
            }
        };
    }

    public void stockBitmapToMemoryCache(T id, Bitmap bitmap) {
        cache.put(id, bitmap);
    }

    public void removeBitmapFromCache(T id) {
        cache.remove(id);
    }

    public Bitmap getBitmapFromMemCache(T id) {

        Bitmap bmp = cache.get(id);
        if (bmp == null) {

            bmp = createBitmap(id);
            if (bmp != null) {
                stockBitmapToMemoryCache(id, bmp);
            }
        }

        return bmp;
    }

    public void clearCache() {
        cache.evictAll();
    }

    protected abstract Bitmap createBitmap(T id);
}
