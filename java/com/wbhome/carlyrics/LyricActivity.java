package com.wbhome.carlyrics;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

public class LyricActivity extends Activity {

    private final Handler h = new Handler(Looper.getMainLooper());
    private LyricView view;
    private TextView tvStatus;
    private GestureDetector gestures;
    private long flashUntil = 0L;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (view != null) view.invalidate();
            if (tvStatus != null) {
                String s;
                if (System.currentTimeMillis() < flashUntil) {
                    s = "已要求重新识别…";
                } else {
                    s = PlayerState.status;
                    if (PlayerState.detail != null && !PlayerState.detail.isEmpty()) {
                        s = s + "   ·   " + PlayerState.detail;
                    }
                }
                tvStatus.setText(s);
            }
            h.postDelayed(this, 120);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_lyric);

        view = (LyricView) findViewById(R.id.lyricView);
        tvStatus = (TextView) findViewById(R.id.tvStatus);

        gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                PlayerState.requestNow();
                flashUntil = System.currentTimeMillis() + 2500L;
                return true;
            }
        });

        findViewById(R.id.btnOrientation).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Config c = Config.get(LyricActivity.this);
                c.setLandscape(!c.landscape());
                applyOrientation();
            }
        });

        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        applyOrientation();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        gestures.onTouchEvent(event);
        return super.onTouchEvent(event);
    }

    private void applyOrientation() {
        boolean land = Config.get(this).landscape();
        setRequestedOrientation(land
                ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideBars();
        if (!PlayerState.running) {
            Intent it = new Intent(this, LyricService.class);
            it.setAction(LyricService.ACTION_START);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(it);
            else startService(it);
        }
        h.removeCallbacks(tick);
        h.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        h.removeCallbacks(tick);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideBars();
    }

    private void hideBars() {
        View d = getWindow().getDecorView();
        d.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }
}
