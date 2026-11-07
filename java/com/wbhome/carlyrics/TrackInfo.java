package com.wbhome.carlyrics;

import android.os.SystemClock;

public class TrackInfo {
    public String acrid = "";
    public String title = "";
    public String artist = "";
    public String album = "";
    public long durationMs;
    public long playOffsetMs;
    public double score;

    /** 哪个引擎认出来的：ACRCloud / AudD */
    public String engine = "";

    /** 时长是不是「猜」出来的（AudD 不给总时长，用歌词末行时间估的） */
    public boolean durationGuessed;

    /** 校准基准：本地时刻 baseLocalMs 时，歌曲位于第 baseOffsetMs 毫秒 */
    public long baseOffsetMs;
    public long baseLocalMs;

    public long positionMs() {
        if (baseLocalMs == 0L) return 0L;
        long p = baseOffsetMs + (SystemClock.elapsedRealtime() - baseLocalMs);
        return p < 0 ? 0 : p;
    }

    public boolean hasDuration() {
        return durationMs > 20000L;
    }

    public String display() {
        String s;
        if (artist == null || artist.isEmpty()) s = title == null ? "" : title;
        else s = artist + " · " + title;
        if (engine != null && !engine.isEmpty()) s = s + "  [" + engine + "]";
        return s;
    }
}
