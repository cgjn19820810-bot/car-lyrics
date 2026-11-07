package com.wbhome.carlyrics;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private EditText etHost;
    private EditText etAccessKey;
    private EditText etAccessSecret;
    private EditText etAudd;
    private SeekBar sbRecord;
    private SeekBar sbSleep;
    private SeekBar sbShift;
    private TextView tvRecordVal;
    private TextView tvSleepVal;
    private TextView tvShiftVal;
    private TextView tvStatus;
    private TextView btnPrebuf;
    private TextView btnAggr;

    private final Handler h = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        etHost = (EditText) findViewById(R.id.etHost);
        etAccessKey = (EditText) findViewById(R.id.etAccessKey);
        etAccessSecret = (EditText) findViewById(R.id.etAccessSecret);
        etAudd = (EditText) findViewById(R.id.etAudd);
        sbRecord = (SeekBar) findViewById(R.id.sbRecord);
        sbSleep = (SeekBar) findViewById(R.id.sbInterval);
        sbShift = (SeekBar) findViewById(R.id.sbShift);
        tvRecordVal = (TextView) findViewById(R.id.tvRecordVal);
        tvSleepVal = (TextView) findViewById(R.id.tvIntervalVal);
        tvShiftVal = (TextView) findViewById(R.id.tvShiftVal);
        tvStatus = (TextView) findViewById(R.id.tvStatus);
        btnPrebuf = (TextView) findViewById(R.id.btnPrebuf);
        btnAggr = (TextView) findViewById(R.id.btnAggr);

        loadIntoUi();

        sbRecord.setOnSeekBarChangeListener(new OnSeek() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                tvRecordVal.setText(recordSec() + " 秒");
            }
        });
        sbSleep.setOnSeekBarChangeListener(new OnSeek() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                tvSleepVal.setText(maxSleepSec() + " 秒");
            }
        });
        sbShift.setOnSeekBarChangeListener(new OnSeek() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                tvShiftVal.setText(String.format("%.1f 秒", shiftMs() / 1000.0));
            }
        });

        btnPrebuf.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Config c = Config.get(MainActivity.this);
                c.setPreBuffer(!c.preBuffer());
                showPrebuf();
            }
        });

        btnAggr.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Config c = Config.get(MainActivity.this);
                c.setAggressive(!c.aggressive());
                showAggr();
            }
        });

        // 凭据没填时一键跳到控制台，省得用户自己找
        findViewById(R.id.btnConsole).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                openUrl(Config.get(MainActivity.this).hasKey()
                        ? "https://console.acrcloud.com/"
                        : "https://console.acrcloud.com/signup");
            }
        });

        findViewById(R.id.btnStart).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startLyrics(); }
        });

        findViewById(R.id.btnTest).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { testOnce(); }
        });

        findViewById(R.id.btnBattery).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { askIgnoreBattery(); }
        });

        findViewById(R.id.btnAutostart).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openStartupManager(); }
        });

        tvStatus.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                showLog();
                return true;
            }
        });

        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    // ---------------------------------------------------------- 参数读写

    /** 单次送检时长：4 + progress 秒（滑条 max=8 ⇒ 4~12 秒，官方上限就是 12） */
    private int recordSec() {
        return 4 + sbRecord.getProgress();
    }

    /** 最长静默间隔：15 + progress*15，范围 15~300 秒 */
    private int maxSleepSec() {
        return 15 + sbSleep.getProgress() * 15;
    }

    private int shiftMs() {
        return (sbShift.getProgress() - 12) * 250;
    }

    private void loadIntoUi() {
        Config c = Config.get(this);
        etHost.setText(c.host());
        etAccessKey.setText(c.accessKey());
        etAccessSecret.setText(c.accessSecret());
        etAudd.setText(c.auddToken());
        sbRecord.setProgress(clamp(c.recordSec() - 4, 0, 8));
        sbSleep.setProgress(clamp((c.maxSleepSec() - 15) / 15, 0, 19));
        sbShift.setProgress(clamp(c.shiftMs() / 250 + 12, 0, 24));
        tvRecordVal.setText(recordSec() + " 秒");
        tvSleepVal.setText(maxSleepSec() + " 秒");
        tvShiftVal.setText(String.format("%.1f 秒", shiftMs() / 1000.0));
        showPrebuf();
        showAggr();
    }

    private void showPrebuf() {
        boolean on = Config.get(this).preBuffer();
        btnPrebuf.setText(on ? "预录音缓冲：开（切歌即刻识别）" : "预录音缓冲：关（现场录音，省电）");
    }

    private void showAggr() {
        boolean on = Config.get(this).aggressive();
        btnAggr.setText(on ? "积极识别：开（认不出时每 12 秒重听一次）" : "积极识别：关（每 20 秒，更省电）");
    }

    private void save() {
        Config.get(this).save(
                etAccessKey.getText().toString(),
                etAccessSecret.getText().toString(),
                etHost.getText().toString(),
                etAudd.getText().toString(),
                recordSec(), maxSleepSec(), shiftMs());
    }

    private void refreshStatus() {
        String s;
        if (PlayerState.running) {
            s = "运行中 · " + PlayerState.status
                    + "\n本轮第 " + PlayerState.round + " 次 · 成功 " + PlayerState.hits
                    + " · 未识别 " + PlayerState.miss;
            if (AudioBuffer.running()) {
                s = s + "\n预录音缓冲：" + AudioBuffer.availableSec() + " 秒 · " + AudioBuffer.sourceName();
            } else if (AudioBuffer.starting()) {
                s = s + "\n预录音缓冲：正在自检录音链路…";
            } else if (Config.get(this).preBuffer()) {
                s = s + "\n预录音缓冲：未启动";
            }
            if (PlayerState.track != null) s = s + "\n当前：" + PlayerState.track.display();
        } else {
            s = "未运行。点「开始 · 进入全屏歌词」。\n识别走免费 Shazam；ACRCloud / AudD 是可选兜底，填上可进一步提高命中率。";
        }
        s = s + "\n\n（长按本框可查看识别日志，里面写着每轮送出去的音量、秒数、走的哪条录音链路）";
        tvStatus.setText(s);
    }

    // ---------------------------------------------------------- 动作

    private void startLyrics() {
        save();
        if (!hasMic()) {
            requestPerms();
            return;
        }
        actuallyStart();
    }

    private void actuallyStart() {
        PlayerState.reset();
        Intent it = new Intent(this, LyricService.class);
        it.setAction(LyricService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(it);
        else startService(it);
        startActivity(new Intent(this, LyricActivity.class));
    }

    private void testOnce() {
        save();
        if (!hasMic()) {
            requestPerms();
            return;
        }

        final Config c = Config.get(this);
        final int sec = c.recordSec();
        tvStatus.setText("测试中：正在取 " + Math.max(sec, AudioRecorder.SHAZAM_SEC)
                + " 秒音频…（Shazam 要完整 12 秒，把车里的音乐放开一点）");

        new Thread(new Runnable() {
            @Override public void run() {
                final StringBuilder sb = new StringBuilder();

                // 测试也走常驻缓冲：先起来等它自检 + 攒够，这样测出来的行为与正式跑一致
                boolean startedHere = false;
                if (c.preBuffer() && !AudioBuffer.alive()) {
                    AudioBuffer.start();
                    startedHere = AudioBuffer.alive();
                }

                AudioRecorder.Rec rec = AudioRecorder.record(sec, 6);
                boolean srcBuf = rec.fromBuffer;
                String err = rec.error;
                AcrCloud.Result r = null;

                if (rec.wav != null && !rec.silent && !rec.tooShort) {
                    r = Recognizer.identify(rec.wav, rec.shazamPcm, c);
                }

                if (startedHere) AudioBuffer.stop();

                if (rec.error != null && rec.wav == null) {
                    sb.append("取音频失败：").append(err);
                } else if (rec.tooShort) {
                    sb.append("缓冲里只攒到 ").append(rec.seconds)
                      .append(" 秒，太短没发出去（短样本认不出，还白占额度）。等两三秒再测一次。");
                } else if (rec.silent) {
                    sb.append("环境太安静（音量 ").append((int) rec.rms)
                      .append("、峰值 ").append(rec.peak)
                      .append("），没听到音乐。请把车里的音乐放大声再测。");
                } else {
                    sb.append(diagOf(rec)).append(srcBuf ? "（来自预录音缓冲，零等待）" : "（现场录制）")
                      .append("\n");

                    if (r != null && r.ok) {
                        sb.append("识别成功：").append(r.track.display()).append('\n')
                          .append("引擎：").append(r.track.engine).append('\n')
                          .append("歌里位置：第 ").append(r.track.playOffsetMs / 1000).append(" 秒");
                        if (r.track.hasDuration()) {
                            sb.append("（总长 ").append(r.track.durationMs / 1000).append(" 秒）");
                        } else {
                            sb.append("（此引擎不返回总时长）");
                        }
                        sb.append('\n');
                        if (r.track.score > 0d) {
                            sb.append("匹配度：").append((int) r.track.score).append('\n');
                        }
                        Lrc lrc = LyricsFetcher.fetch(MainActivity.this, r.track);
                        if (lrc == null || !lrc.usable()) {
                            sb.append("歌词：没找到");
                        } else {
                            sb.append("歌词：").append(lrc.source)
                              .append(lrc.synced ? "（带时间轴，" + lrc.lines.size() + " 行）"
                                                 : "（纯文本，" + lrc.plainLines.size() + " 行）");
                        }
                    } else if (r != null) {
                        sb.append("识别失败：").append(r.message);
                    } else {
                        sb.append("识别失败");
                    }
                }

                h.post(new Runnable() {
                    @Override public void run() { tvStatus.setText(sb.toString()); }
                });
            }
        }, "test-once").start();
    }

    private static String diagOf(AudioRecorder.Rec rec) {
        StringBuilder d = new StringBuilder();
        d.append("音量 ").append((int) rec.rms)
         .append(" · 峰值 ").append(rec.peak)
         .append(" · 取到 ").append(rec.sentMs / 1000).append(" 秒");
        if (rec.gainPct != 100) d.append(" · 已抬增益 ").append(rec.gainPct).append("%");
        if (rec.lookbackMs >= 1000) d.append(" · 回看 ").append(rec.lookbackMs / 1000).append(" 秒挑的窗口");
        d.append(" · ").append(AudioBuffer.sourceName());
        return d.toString();
    }

    private void askIgnoreBattery() {        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception e2) {
                Toast.makeText(this, "请手动到 设置→电池 里放行", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void openStartupManager() {
        String[][] targets = new String[][]{
                {"com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
                {"com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"},
                {"com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"},
        };
        for (String[] t : targets) {
            try {
                Intent i = new Intent();
                i.setComponent(new ComponentName(t[0], t[1]));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                return;
            } catch (Exception ignore) { }
        }
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
            Toast.makeText(this, "请在「耗电管理/启动管理」里允许自启动和后台活动", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "请手动到 设置→应用→车机歌词 里放行", Toast.LENGTH_LONG).show();
        }
    }

    private void openUrl(String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "没找到浏览器，手动打开：" + url, Toast.LENGTH_LONG).show();
        }
    }

    private void showLog() {
        String log = PlayerState.log;
        if (log == null || log.isEmpty()) log = "（还没有日志）";

        final String text = log;
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextIsSelectable(true);          // 长按可选中复制
        tv.setTextSize(13f);
        tv.setLineSpacing(2f, 1f);
        int pad = dp(16);
        tv.setPadding(pad, pad, pad, pad);

        ScrollView sv = new ScrollView(this);
        sv.addView(tv);

        new AlertDialog.Builder(this)
                .setTitle("识别日志（长按文字可复制）")
                .setView(sv)
                .setPositiveButton("复制全部", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        copyText(text);
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void copyText(String s) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("识别日志", s));
                Toast.makeText(this, "已复制，去微信/短信里长按粘贴即可", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "复制失败：" + e.getClass().getSimpleName(), Toast.LENGTH_SHORT).show();
        }
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    // ---------------------------------------------------------- 权限

    private boolean hasMic() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPerms() {
        List<String> want = new ArrayList<String>();
        want.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            want.add("android.permission.POST_NOTIFICATIONS");
        }
        requestPermissions(want.toArray(new String[0]), 1001);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code != 1001) return;
        if (hasMic()) {
            actuallyStart();
        } else {
            Toast.makeText(this, "没有录音权限就没法听歌，请到系统设置里打开", Toast.LENGTH_LONG).show();
        }
    }

    // ---------------------------------------------------------- 工具

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private abstract static class OnSeek implements SeekBar.OnSeekBarChangeListener {
        @Override public void onStartTrackingTouch(SeekBar sb) { }
        @Override public void onStopTrackingTouch(SeekBar sb) { }
    }
}
