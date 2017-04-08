/*
 *
 * Copyright 2015 FickleRobot LLC.
 *
 */

package com.ficklerobot.gridvideoviewer;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.ArrayAdapter;
import android.widget.Spinner;

public class MainActivity extends Activity {

    private Spinner mColSpinner;
    private Spinner mPlaySpinner;
    private Spinner mActionSpinner;

    public static final int TAP_ACTION_FLOAT = 1;
    public static final int TAP_ACTION_THUMBNAIL = 2;

    private static final int[] TAP_ACTION_LIST =
            {TAP_ACTION_FLOAT, TAP_ACTION_THUMBNAIL};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        findViewById(R.id.goButton).setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {

                int colCount = Integer.valueOf((String) mColSpinner.getSelectedItem());
                int playCount = Integer.valueOf((String) mPlaySpinner.getSelectedItem());
                int tapAction = TAP_ACTION_LIST[mActionSpinner.getSelectedItemPosition()];

                Intent intent = new Intent(getApplicationContext(), GalleryActivity.class);
                intent.putExtra(GalleryActivity.EXT_COL_COUNT, colCount);
                intent.putExtra(GalleryActivity.EXT_PLAY_COUNT, playCount);
                intent.putExtra(GalleryActivity.EXT_TAP_ACTION, tapAction);

                startActivity(intent);
            }
        });

        mColSpinner = (Spinner) findViewById(R.id.colCountSpinner);
        String[] cols = {"1", "2", "3", "4", "5"};
        mColSpinner.setAdapter(new ArrayAdapter<String>(
                this, android.R.layout.simple_list_item_1, cols));
        mColSpinner.setSelection(2);

        mPlaySpinner = (Spinner) findViewById(R.id.playCountSpinner);
        String[] playCounts = {"1", "2", "3", "4", "5", "6", "7", "8", "9",};

        mPlaySpinner.setAdapter(new ArrayAdapter<String>(
                this, android.R.layout.simple_list_item_1, playCounts));
        mPlaySpinner.setSelection(1);

        mActionSpinner = (Spinner) findViewById(R.id.tapActionSpinner);
        String[] actions = {"拡大して再生", "サムネイル再生"};
        mActionSpinner.setAdapter(new ArrayAdapter<String>(
                this, android.R.layout.simple_list_item_1, actions));
        mActionSpinner.setSelection(0);
    }
}
