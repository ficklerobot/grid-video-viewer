/*
 *
 * Copyright 2015 FickleRobot LLC.
 *
 */

package com.ficklerobot.gridvideoviewer;

import android.app.Activity;
import android.app.Fragment;
import android.app.FragmentTransaction;
import android.content.pm.ActivityInfo;
import android.os.Build;
import android.os.Bundle;

public class GalleryActivity extends Activity {
    /** 列数 */
    public static final String EXT_COL_COUNT = "colCount";
    /** 同時再生数 */
    public static final String EXT_PLAY_COUNT = "playCount";
    /** タップ時の動作 TAP_ACTION_FLOAT|TAP_ACTION_THUMBNAIL */
    public static final String EXT_TAP_ACTION = "tapAction";

    private static final String FRAGMENT_GRID = "gridFragment";
    /** バックキー押下時の動作 */
    private OnBackPressListener mBackPressListener;

    public interface OnBackPressListener {

        /**
         * @return true:ActivityでBackPressイベントを処理する false:処理しない
         */
        boolean onBackPressed();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_gallery);

        int orientation;
        if (Build.VERSION.SDK_INT < 18) {
            orientation = ActivityInfo.SCREEN_ORIENTATION_USER;
        } else {
            orientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED;
        }

        setRequestedOrientation(orientation);
        changeFragment(FRAGMENT_GRID);
    }

    @Override
    public void onBackPressed() {
        boolean doBack = true;

        if (mBackPressListener != null) {
            doBack = mBackPressListener.onBackPressed();
        }

        if (doBack) {
            super.onBackPressed();
        }
    }

    public void changeFragment(String tag) {
        mBackPressListener = null;
        Fragment fragment = null;

        if (FRAGMENT_GRID.equals(tag)) {
            fragment = new VideoGridFragment();
            fragment.setArguments(getIntent().getExtras());
            mBackPressListener = (VideoGridFragment) fragment;
        }

        if (fragment != null) {
            FragmentTransaction transaction = getFragmentManager().beginTransaction();
            transaction.replace(R.id.container, fragment, tag);
            transaction.commit();
        }
    }
}
