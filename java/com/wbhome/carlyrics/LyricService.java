package com.wbhome.carlyrics;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;

import java.util.Locale;

/**
 * 核心循环：听 → 识别 → 拉歌词 → 校准进度 → 按歌曲剩余时长安排下一次听。
 *
 * 四个提识别率/提速的设计：
 *  1. {@link AudioBuffer} 常驻录音，识别时直接倒带取 → 不用现等录音，切歌后即刻可识别；
 *  2. {@link WindowPicker#trimValley} 把窗口尾部的留白裁掉再送 → 官方只识别前 12 秒，
 *     送进去的每一秒都必须是音乐，否则等于白烧额度（实测：窗头 4 秒留白的情形，
 *     送检有效率从 60% 拉到 100%）；
 *  3. {@link Recognizer} ACRCloud 主 + AudD 兜底 → 两家曲库互补；
 *  4. 太短/太静的样本**不发**（既省额度，也避免失败把退避期拉长、错过好窗口）。
 */
public class LyricService extends Service {

    public static final String ACTION_START = "com.wbhome.carlyrics.START";
    public static final String ACTION_STOP = "com.wbhome.carlyrics.STOP";

    private static final String CH_ID = "car_lyrics";
    private static final int N_ID = 1024;

    /**
     * 连续失败时允许「往回看」多深 —— 但只在**裁完留白之后剩下的有效段仍比窗口长**时才起作用
     * （{@link AudioBuffer#takeWindow}：段内已无谷底，才允许挑更响的等长子窗，跨不到上一首去）。
     * 每连续失败一次多回看 4 秒，最多 20 秒。
     * 上限 20 秒是为了不超出预录音缓冲（40 秒）减去最长送检段（12 秒）的余量。
     */
    private static final int LOOK_BASE = 4;
    private static final int LOOK_STEP = 4;
    private static final int LOOK_MAX = 20;

    private volatile boolean running = false;
    private Thread worker;
    private Config cfg;

    /** 预录音缓冲启动失败只提示一次，别每轮刷屏 */
    private boolean bufferWarned = false;

    /** 未配置 ACRCloud 时提示一次（Shazam 免费可用，ACRCloud 只是兜底） */
    private boolean keyWarned = false;

    @Override
    public void onCreate() {
        super.onCreate();
        cfg = Config.get(this);
        createChannel();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();

        if (ACTION_STOP.equals(action)) {
            running = false;
            PlayerState.running = false;
            PlayerState.status = "已停止";
            PlayerState.addLog("停止识别");
            AudioBuffer.stop();
            try { stopForeground(true); } catch (Exception ignore) { }
            stopSelf();
            return START_NOT_STICKY;
        }

        startFg("正在启动…");
        ensureBuffer();

        if (!running) {
            running = true;
            PlayerState.running = true;
            PlayerState.addLog("开始识别");
            worker = new Thread(new Runnable() {
                @Override public void run() { loop(); }
            }, "lyric-worker");
            worker.setDaemon(true);
            worker.start();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        PlayerState.running = false;
        AudioBuffer.stop();
        super.onDestroy();
    }

    // ------------------------------------------------------------ 预录音缓冲

    /**
     * 保证常驻录音在跑。每轮调一次；已经在起或已经在跑就立刻返回。
     * 关掉该功能（省电）时会主动停掉缓冲，退回现场录音。
     */
    private void ensureBuffer() {
        if (!cfg.preBuffer()) {
            if (AudioBuffer.alive()) {
                AudioBuffer.stop();
                PlayerState.addLog("预录音缓冲已关闭（改为每次现场录音）");
            }
            return;
        }
        if (AudioBuffer.alive()) return;      // 起中（含链路自检）也算占着设备，别重复 start

        AudioBuffer.start();
        if (AudioBuffer.alive()) {
            bufferWarned = false;
            PlayerState.addLog("预录音缓冲启动中：先自检录音链路（约 2 秒），之后麦克风常驻，切歌可直接倒带");
        } else if (!bufferWarned) {
            bufferWarned = true;
            PlayerState.addLog("预录音缓冲启动失败（" + AudioBuffer.error() + "），改为现场录音");
        }
    }

    // ------------------------------------------------------------ 主循环

    private void loop() {
        while (running) {
            long t0 = SystemClock.elapsedRealtime();
            PlayerState.round++;

            try {
                // Shazam 免费不限次，是主力引擎，不依赖任何密钥；ACRCloud / AudD 只是可选兜底。
                // 所以这里不再用「没填 ACRCloud 密钥就干等」拦人 —— 没填也能用，只是少一道兜底。
                if (!cfg.hasKey() && !keyWarned) {
                    keyWarned = true;
                    PlayerState.addLog("未配置 ACRCloud：仅用免费 Shazam 识别（可在设置页补填 ACRCloud / AudD 作兜底）");
                }

                ensureBuffer();

                int recSec = cfg.recordSec();
                // 连续失败时逐步加大「段内回看」余量：换一段窗口再试。
                // 同一段音频只隔十几秒再取一次，内容高度重叠，重复失败是必然的 ——
                // 换个窗口等于换一次机会，而额度只多花一份、识别率却实打实地上去。
                int look = LOOK_BASE + PlayerState.missStreak * LOOK_STEP;
                if (look > LOOK_MAX) look = LOOK_MAX;

                PlayerState.status = "正在听…";
                PlayerState.detail = AudioBuffer.running()
                        ? "预录音缓冲倒带取最近 " + recSec + " 秒（已裁掉留白，只送真在放歌的部分）"
                        : "现场录音 " + recSec + " 秒";

                AudioRecorder.Rec rec = AudioRecorder.record(recSec, look);
                if (!running) break;

                if (rec.bufferFailed) {
                    PlayerState.addLog("常驻缓冲这轮没给到音频，已退回现场录音");
                }

                if (rec.wav == null && rec.error != null) {
                    PlayerState.status = "麦克风不可用";
                    PlayerState.detail = rec.error;
                    PlayerState.addLog("麦克风失败: " + rec.error);
                    waitMs(3000);
                    continue;
                }

                // 缓冲还没攒够 —— 宁可不发。短样本匹配率低，失败了还会把退避期拉长，
                // 结果更久才再试一次，是「白烧额度 + 顺延机会」的双输。不计入识别失败次数。
                if (rec.tooShort) {
                    PlayerState.status = "还在攒音频";
                    PlayerState.detail = "缓冲里才 " + (rec.shazamSentMs / 1000)
                            + " 秒，还没攒够 Shazam 要的 12 秒，本轮不发";
                    if (PlayerState.round % 5 == 1) {
                        PlayerState.addLog("缓冲才 " + (rec.shazamSentMs / 1000)
                                + " 秒（Shazam 要 12 秒），没攒够，本轮不发");
                    }
                    waitMs(1200);
                    continue;
                }

                if (rec.silent) {
                    PlayerState.status = "车里很安静";
                    PlayerState.detail = "没听到音乐，本轮跳过（不消耗识别额度）· 音量 "
                            + (int) rec.rms + " · 峰值 " + rec.peak
                            + (rec.trimmedSec > 0 ? " · 裁掉留白 " + rec.trimmedSec + " 秒" : "");
                    if (PlayerState.round % 8 == 1) {
                        PlayerState.addLog("本轮没有有效音乐：" + diag(rec));
                    }
                    waitMs(1500);
                    continue;
                }

                String diag = diag(rec);
                PlayerState.status = "识别中…";
                PlayerState.detail = diag;

                AcrCloud.Result res = Recognizer.identify(rec.wav, rec.shazamPcm, cfg);

                if (!res.ok) {
                    PlayerState.miss++;
                    PlayerState.missStreak++;
                    PlayerState.status = "没听出来";
                    PlayerState.detail = res.message + " ｜ " + diag;
                    PlayerState.addLog("未识别: " + res.message + " ｜ " + diag);
                } else {
                    PlayerState.missStreak = 0;
                    applyResult(res.track, rec);
                }
            } catch (Throwable e) {
                PlayerState.status = "出错";
                PlayerState.detail = e.getClass().getSimpleName() + ": " + e.getMessage();
                PlayerState.addLog("异常: " + PlayerState.detail);
            }

            waitMs(computeWait(SystemClock.elapsedRealtime() - t0));
        }
    }

    /** 把这一轮「到底送出去了什么」摆成一行 —— 出问题时能一眼分清是没发、还是发了没中 */
    private static String diag(AudioRecorder.Rec rec) {
        StringBuilder d = new StringBuilder();
        d.append("音量 ").append((int) rec.rms)
         .append(" · 峰值 ").append(rec.peak)
         .append(" · 送 ").append(rec.sentMs / 1000).append(" 秒");
        if (rec.shazamSentMs > rec.sentMs) d.append("（Shazam 送 ").append(rec.shazamSentMs / 1000).append(" 秒）");
        if (rec.gainPct != 100) d.append(" · 增益 ").append(rec.gainPct).append("%");
        if (rec.lookbackMs >= 1000) d.append(" · 回看 ").append(rec.lookbackMs / 1000).append(" 秒");
        d.append(" · ").append(rec.fromBuffer ? "预录音缓冲" : "现场录音")
         .append('(').append(AudioBuffer.sourceName()).append(')');
        return d.toString();
    }

    /**
     * 智能排期：知道歌名和时长后，只在「这首歌快结束」和「该换歌」时才去听。
     * 一首歌通常只需 2~3 次识别，免费额度才够用。
     * 还不知道在放什么歌时按 searchSec() 周期试探，积极识别档探得更勤。
     */
    private long computeWait(long usedMs) {
        long base;
        TrackInfo t = PlayerState.track;
        long maxSleep = cfg.maxSleepSec() * 1000L;

        if (t != null && t.hasDuration() && PlayerState.lrc != null && PlayerState.lrc.usable()) {
            long remain = t.durationMs - t.positionMs();
            if (remain <= 0L) {
                base = 2500L;                     // 这首该结束了，马上去探下一首
            } else if (remain < 30000L) {
                base = 12000L;                    // 快结束，加密探测，别漏掉换歌
            } else {
                base = remain - 25000L;           // 睡到「还剩 25 秒」再醒
                if (base > maxSleep) base = maxSleep;
                if (base < 15000L) base = 15000L;
            }
        } else {
            base = cfg.searchSec() * 1000L;       // 还不知道在放什么，周期试探
        }

        if (PlayerState.missStreak >= cfg.missStreakLimit()) {
            int back = cfg.missBackoffMs();       // 连续听不出来就退避，省额度
            if (base < back) base = back;
        }

        long wait = base - usedMs;
        return wait < 800L ? 800L : wait;
    }

    private void applyResult(TrackInfo t, AudioRecorder.Rec rec) {
        TrackInfo prev = PlayerState.track;
        boolean same = sameSong(prev, t);

        if (!same) {
            PlayerState.status = "换歌了，正在找歌词…";
            PlayerState.detail = t.display();

            Lrc lrc = LyricsFetcher.fetch(getApplicationContext(), t);
            PlayerState.lrc = lrc;

            // AudD 不返回歌曲总时长 —— 用歌词最后一行的位置 + 15 秒尾奏估一个。
            // 不估的话排期只能退化成固定间隔试探，白白多调接口。
            if (!t.hasDuration() && lrc != null && lrc.synced && !lrc.lines.isEmpty()) {
                long last = lrc.lines.get(lrc.lines.size() - 1).t;
                t.durationMs = last + 15000L;
                t.durationGuessed = true;
            }

            PlayerState.addLog("识别(" + t.engine + "): " + t.artist + " - " + t.title
                    + " | 歌里第 " + (t.playOffsetMs / 1000) + " 秒"
                    + " | 歌词: " + (lrc == null ? "未找到"
                        : lrc.source + (lrc.synced ? " 带时间轴" : " 纯文本"))
                    + (t.durationGuessed ? " | 时长按歌词估算 " + (t.durationMs / 1000) + " 秒" : ""));
        } else {
            // 同一首歌：沿用已有歌词，只更新进度校准
            if (PlayerState.lrc == null || !PlayerState.lrc.usable()) {
                PlayerState.lrc = LyricsFetcher.fetch(getApplicationContext(), t);
            }
            if (!t.hasDuration() && prev != null && prev.hasDuration()) {
                t.durationMs = prev.durationMs;
                t.durationGuessed = prev.durationGuessed;
            }
            PlayerState.addLog("校准(" + t.engine + "): " + t.title
                    + " -> 歌里第 " + (t.playOffsetMs / 1000) + " 秒");
        }

        // 校准基准：baseLocalMs 这个本地时刻，歌曲正好播到 baseOffsetMs 这个位置。
        //
        // 关键修正 1（锚点对齐）：baseLocalMs 取「片段末端播完的时刻」，那 baseOffsetMs 就必须取
        // 「片段末端在歌里的位置」= playOffsetMs + sentMs。之前错写成 playOffsetMs + sentMs/2
        // （片段中点位置配终点时刻），锚点错位 → 歌词系统性慢 sentMs/2。
        // Shazam 的 offset 是「片段起点」，12 秒 → 慢 6 秒，超出 shiftMs ±3 秒微调范围，所以「调了也没用」。
        // ACRCloud/AudD 的 play_offset_ms/timecode 语义按历史经验（ACRCloud 时代整体好用）保持 +sentMs/2 不变。
        //
        // 关键修正 2：baseLocalMs 必须是「那一段音频播完的时刻」（= 取样本的时刻），
        // 不能取「网络请求返回、歌词也拉完之后的此刻」。后者把 HTTP 往返 + 拉歌词的耗时
        // 一起算进基准里，会让歌词系统性慢好几秒 —— 而这段耗时每次都不一样。
        // 校准用的「送检长度」和「片段末端时刻」：Shazam 吃的是完整 12 秒不裁留白，
        // 与 ACRCloud/AudD 吃的裁留白短音频不是同一段，时长/时刻基准必须各用各的。
        long sentMs;
        long endReal;
        if ("Shazam".equals(t.engine) && rec.shazamSentMs > 0) {
            sentMs = rec.shazamSentMs;
            endReal = rec.shazamEndRealtime > 0L
                    ? rec.shazamEndRealtime : SystemClock.elapsedRealtime();
        } else {
            sentMs = rec.sentMs > 0 ? rec.sentMs : rec.seconds * 1000L;
            endReal = rec.endRealtime > 0L
                    ? rec.endRealtime : SystemClock.elapsedRealtime();
        }
        if ("Shazam".equals(t.engine)) {
            t.baseOffsetMs = t.playOffsetMs + sentMs;
        } else {
            t.baseOffsetMs = t.playOffsetMs + sentMs / 2L;
        }
        t.baseLocalMs = endReal;

        PlayerState.track = t;
        PlayerState.hits++;

        Lrc lrc = PlayerState.lrc;
        boolean noLrc = lrc == null || !lrc.usable();
        if (noLrc) {
            // 别说成「这首歌没有歌词」—— 很可能是同名版本太多、都对不上歌手名被我们筛掉了。
            // 《上班族狂想曲》在国内平台有 28 个版本、歌词各不相同，就是这个情况。
            PlayerState.status = "没找到匹配这一版的歌词";
        } else if (lrc.synced) {
            PlayerState.status = "歌词同步中";
        } else {
            PlayerState.status = "只能按比例估算（无时间轴歌词）";
        }

        StringBuilder d = new StringBuilder();
        d.append(t.display())
         .append("   ·   歌里第 ").append(t.playOffsetMs / 1000).append(" 秒");
        if (t.score > 0d) d.append("   ·   匹配度 ").append((int) t.score);
        if (t.durationGuessed) d.append("   ·   时长估算 ").append(t.durationMs / 1000).append(" 秒");
        if (noLrc) {
            d.append("   ·   三个歌词源都没有「").append(t.artist).append("」这一版");
        }
        PlayerState.detail = d.toString();

        updateNotif(t);
    }

    /**
     * 是不是同一首歌。不能只看 acrid —— 换识别引擎时 acrid 会变，
     * 那样会误判成「换歌」，白白重新拉一次歌词。
     */
    private static boolean sameSong(TrackInfo a, TrackInfo b) {
        if (a == null || b == null) return false;
        if (a.acrid != null && !a.acrid.isEmpty() && a.acrid.equals(b.acrid)) return true;
        String at = norm(a.title);
        if (at.isEmpty() || !at.equals(norm(b.title))) return false;
        return norm(a.artist).equals(norm(b.artist));
    }

    private static String norm(String s) {
        if (s == null) return "";
        return s.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    /** 可被「双击屏幕立刻重听」打断的等待 */
    private void waitMs(long ms) {
        long end = SystemClock.elapsedRealtime() + ms;
        while (running) {
            if (PlayerState.forceNow) {
                PlayerState.forceNow = false;
                return;
            }
            long left = end - SystemClock.elapsedRealtime();
            if (left <= 0L) return;
            synchronized (PlayerState.LOCK) {
                if (PlayerState.forceNow) {
                    PlayerState.forceNow = false;
                    return;
                }
                try {
                    PlayerState.LOCK.wait(Math.min(left, 500L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    // ------------------------------------------------------------ 通知

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (nm.getNotificationChannel(CH_ID) != null) return;
        NotificationChannel ch = new NotificationChannel(CH_ID, "车机歌词识别", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        ch.enableVibration(false);
        ch.setSound(null, null);
        nm.createNotificationChannel(ch);
    }

    private Notification build(String title, String text) {
        Intent open = new Intent(this, LyricActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int piFlag = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) piFlag |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 1, open, piFlag);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, CH_ID);
        } else {
            b = new Notification.Builder(this);
        }
        b.setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pi);
        return b.build();
    }

    private void startFg(String text) {
        Notification n = build("车机歌词", text);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(N_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(N_ID, n);
        }
    }

    private void updateNotif(TrackInfo t) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(N_ID, build(t.title == null ? "车机歌词" : t.title, t.display()));
        } catch (Exception ignore) { }
    }
}
