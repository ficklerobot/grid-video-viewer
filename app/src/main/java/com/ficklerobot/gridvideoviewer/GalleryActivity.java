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

    public static final String EXT_COL_COUNT = "colCount";
    public static final String EXT_PLAY_COUNT = "playCount";
    public static final String EXT_TAP_ACTION = "tapAction";

    private static final String FRAGMENT_GRID = "gridFragment";

    public static final String TAG = "VideoGrid";

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

        int orientation = (Build.VERSION.SDK_INT < 18
                ? ActivityInfo.SCREEN_ORIENTATION_USER
                : ActivityInfo.SCREEN_ORIENTATION_LOCKED);

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
        boolean addBackStack = false;

        if (FRAGMENT_GRID.equals(tag)) {
            fragment = new VideoGridFragment();
            fragment.setArguments(getIntent().getExtras());
            addBackStack = false;
            mBackPressListener = (VideoGridFragment) fragment;
        }

        if (fragment != null) {

            FragmentTransaction transaction = getFragmentManager().beginTransaction();
            transaction.replace(R.id.container, fragment, tag);
            if (addBackStack) {
                transaction.addToBackStack(null);
            }
            transaction.commit();
        }
    }
}
