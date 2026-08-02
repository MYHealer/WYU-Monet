package com.wps.enhancer;

import android.app.Activity;
import android.os.Bundle;
import android.widget.VideoView;
import android.widget.LinearLayout;
import android.view.WindowManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import java.io.File;

public class DoujieActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.BLACK));
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT));
        VideoView vv = new VideoView(this);
        vv.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT));
        layout.addView(vv);
        setContentView(layout);
        File f = new File("/data/local/tmp/doujie.mp4");
        if (f.exists()) {
            vv.setVideoPath(f.getAbsolutePath());
            vv.setOnPreparedListener(mp -> { mp.setLooping(false); vv.start(); });
            vv.setOnCompletionListener(m -> finish());
            vv.setOnErrorListener((mp, what, extra) -> { finish(); return true; });
            vv.start();
        } else {
            finish();
        }
    }
}