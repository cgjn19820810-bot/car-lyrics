package com.wbhome.carlyrics;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 进程内共享状态：LyricService 写，界面读。 */
public final class PlayerState {

    public static final Object LOCK = new Object();

    public static volatile TrackInfo track;
    public static volatile Lrc lrc;
    public static volatile String status = "未启动";
    public static volatile String detail = "";
    public static volatile boolean running = false;
    public static volatile int round = 0;
    public static volatile int miss = 0;
    public static volatile int hits = 0;
    public static volatile int missStreak = 0;
    /** 双击歌词屏后置位，让正在等待的服务立刻再听一次 */
    public static volatile boolean forceNow = false;
    public static volatile String log = "";

    public static void addLog(String s) {
        String t = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + "  " + s;
        String l = t + "\n" + log;
        if (l.length() > 6000) l = l.substring(0, 6000);
        log = l;
    }

    /** 请求立刻重新识别一次 */
    public static void requestNow() {
        forceNow = true;
        synchronized (LOCK) {
            LOCK.notifyAll();
        }
    }

    public static void reset() {
        track = null;
        lrc = null;
        round = 0;
        miss = 0;
        hits = 0;
        missStreak = 0;
        forceNow = false;
        log = "";
    }
}
