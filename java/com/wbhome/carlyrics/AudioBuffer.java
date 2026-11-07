package com.wbhome.carlyrics;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.SystemClock;

import java.util.Arrays;

/**
 * 常驻录音环形缓冲（预录音 / prerecording）。
 *
 * App 运行期间一直把麦克风声音卷进一个环形缓冲，保留最近 {@link #KEEP_SEC} 秒。
 * 要识别时直接从缓冲里「倒带」取音频发出去，不用现等录音 —— 车机切歌后几乎立刻就能出结果。
 * 这是相对「现场录一段」最大的提速点。
 *
 * 麦克风是独占资源，一个进程只能有一个 AudioRecord，所以由本类统一持有，
 * {@link AudioRecorder} 不再自己开录音（只在缓冲确实不可用时才退回现场录）。
 *
 * 本类干三件与「识别率」直接相关的事，不只是缓冲区：
 *  1. 挑录音链路（{@link #SOURCES}）—— 尽量绕开系统为语音优化的降噪/AGC；
 *  2. 按**实际取出的那段**算音量与峰值（{@link #takeWindow}），不拿全局平滑值顶替；
 *  3. 送检前把窗口尾部的留白裁掉（{@link WindowPicker#trimValley}），只送真在放歌的部分。
 */
public final class AudioBuffer {

    public static final int RATE = AudioRecorder.RATE;
    private static final int BYTES_PER_SEC = RATE * 2;   // 单声道 16bit

    /**
     * 环形缓冲保留多少秒。
     * 40 秒 = 最长送检 12 秒 + 最多回看 20 秒（LyricService.LOOK_MAX）+ 余量，
     * 保证「段内挑更响子窗」时仍有得挑，又不至于旧到把上一首捞回来。
     */
    public static final int KEEP_SEC = 40;

    /** 分块粒度：0.5 秒一块，供窗口挑选用 */
    private static final int BLOCK = BYTES_PER_SEC / 2;

    private static final byte[] RING = new byte[BYTES_PER_SEC * KEEP_SEC];

    /** RING / sWritePos / sTotalWritten 只允许在这把锁里动 */
    private static final Object LOCK = new Object();
    private static int sWritePos = 0;
    private static long sTotalWritten = 0L;
    /** 最近一次写入的时刻（elapsedRealtime），用来把「窗口末端」换算回本地时间 */
    private static volatile long sLastWriteRealtime = 0L;

    /** 最近一小块的音量（指数平滑过），只用于界面显示；静音判定不用它 */
    private static volatile double sRms = 0d;

    /** 0=停/失败 1=正在起（含链路自检） 2=正常跑 */
    private static volatile int sState = 0;
    private static volatile String sError = "";
    private static Thread sThread;
    private static AudioRecord sRecord;
    private static volatile int sSource = -1;

    /**
     * 代数计数器。stop() 时自增，让还在跑的旧录音线程立刻「退休」。
     *
     * 没有它会有个隐蔽 bug：旧线程阻塞在 read() 上，stop() 之后 read 返回错误，
     * 循环回到 while 判断时如果已经 start() 过了，sState 又是「正在起」，
     * 旧线程就会接着读新录音设备 —— 两个线程一起 read 同一个 AudioRecord，数据会花。
     */
    private static volatile long sEpoch = 0L;

    /**
     * 原始采集链路（API 24+；SDK 31 起常量名从 VOICE_UNPROCESSED 改成 UNPROCESSED，值不变）。
     * 拿的是**未经系统前处理**的采集数据。
     */
    private static final int SRC_UNPROCESSED = MediaRecorder.AudioSource.UNPROCESSED;

    /**
     * 录音链路候选，按优先级排。
     *
     * 默认的 MIC 在不少机型（荣耀/华为尤其明显）上会走一整套为「语音通话」优化的链路：
     * 回声消除 + AI 降噪 + AGC。它们会削掉音乐成分、压平动态 ——
     * 表现就是「明明听见在放歌，却老是认不出」，而且日志里看不出任何异常。
     * 音频指纹要的是原始波形，所以优先试 UNPROCESSED。
     *
     * 但这条链路在部分机型上要么打不开、要么给出数字静音，
     * 所以必须带自检 + 自动回退（{@link #selfCheck}），绝不能让它把 App 变成「永远静音」。
     */
    private static final int[] SOURCES = new int[] { SRC_UNPROCESSED, MediaRecorder.AudioSource.MIC };

    /** 自检读多久（毫秒）。太短判不准，太长会拖慢首次识别 */
    private static final long SELF_CHECK_MS = 2000L;

    /**
     * 自检峰值低于这个值就认定「这条链路出的是数字零」，换下一条。
     *
     * 阈值定得极低（16bit 满刻度 32767）：真实麦克风即使在没有声音的房间里也有底噪，
     * 两秒内峰值几乎不可能低于 32。定高了会在「安静环境 + 车机没放歌」时误判，
     * 把一条本来好好的链路踢掉，反而更糟。
     */
    private static final int DEAD_AIR_PEAK = 32;

    private AudioBuffer() { }

    /** 真正在往缓冲里写数据了 */
    public static boolean running() { return sState == 2; }

    /** 正在起（含 2 秒链路自检），此时还没数据，但设备已被本进程占住，别去另开录音 */
    public static boolean starting() { return sState == 1; }

    /** 设备被本类占着（起中或跑中）—— AudioRecorder 用它决定要不要退回现场录 */
    public static boolean alive() { return sState != 0; }

    public static String error() { return sError == null ? "" : sError; }

    public static double rms() { return sRms; }

    public static int source() { return sSource; }

    /** 给人看的链路名，出问题时一眼能看出走的是哪条 */
    public static String sourceName() {
        if (sSource == SRC_UNPROCESSED) return "原始采集链路";
        if (sSource == MediaRecorder.AudioSource.MIC) return "默认麦克风链路";
        return "链路未定";
    }

    /** 缓冲里已经攒了多少秒 */
    public static int availableSec() {
        synchronized (LOCK) {
            long total = Math.min(sTotalWritten, (long) RING.length);
            return (int) (total / BYTES_PER_SEC);
        }
    }

    /** 起常驻录音。可重复调用；已经在起或已经在跑就什么都不做。 */
    public static void start() {
        synchronized (AudioBuffer.class) {
            if (sState != 0) return;
            sError = "";
            sSource = -1;
            sState = 1;

            final long myEpoch = ++sEpoch;
            sThread = new Thread(new Runnable() {
                @Override public void run() { openAndPump(myEpoch); }
            }, "audio-buffer");
            sThread.setDaemon(true);
            sThread.start();
        }
    }

    public static void stop() {
        synchronized (AudioBuffer.class) {
            sEpoch++;          // 让正在跑的旧线程立刻退休，别去碰下一代的设备
            sState = 0;
            sSource = -1;
            AudioRecord ar = sRecord;
            sRecord = null;
            release(ar);
            sThread = null;
            sRms = 0d;
        }
    }

    /** 清空缓冲（换设备/重新开始时用） */
    public static void clear() {
        synchronized (LOCK) {
            sWritePos = 0;
            sTotalWritten = 0L;
            sLastWriteRealtime = 0L;
            Arrays.fill(RING, (byte) 0);
        }
    }

    private static void release(AudioRecord ar) {
        if (ar == null) return;
        try { ar.stop(); } catch (Exception ignore) { }
        try { ar.release(); } catch (Exception ignore) { }
    }

    /**
     * 打开一条录音链路。
     * 注意：这个 SDK 版本里 startRecording() 是 void（旧签名），拿不到返回码，
     * 所以「这条链路到底出不出声」全靠随后的 {@link #selfCheck} 用实测数据判 ——
     * 这本来也比信返回码可靠。
     */
    private static AudioRecord open(int source) {
        AudioRecord ar = null;
        try {
            int minBuf = AudioRecord.getMinBufferSize(
                    RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minBuf <= 0) minBuf = RATE;
            int need = Math.max(minBuf * 2, RATE);      // ≥0.5 秒，读的时候不容易溢出

            ar = new AudioRecord(source, RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, need);
            if (ar.getState() != AudioRecord.STATE_INITIALIZED) {
                sError = "麦克风初始化失败";
                release(ar);
                return null;
            }
            ar.startRecording();
            return ar;
        } catch (SecurityException e) {
            sError = "没有录音权限";
            release(ar);
            return null;
        } catch (Exception e) {
            sError = e.getClass().getSimpleName() + ": " + e.getMessage();
            release(ar);
            return null;
        }
    }

    // ------------------------------------------------------------ 线程主体

    /** 一条链路一条链路试：打开 → 自检 → 正式卷数据。全失败才置错退出。 */
    private static void openAndPump(long myEpoch) {
        byte[] buf = new byte[Math.max(RATE / 4, 2048)];   // 约 125ms 一块

        for (int si = 0; si < SOURCES.length; si++) {
            int src = SOURCES[si];
            AudioRecord ar = open(src);
            if (ar == null) continue;
            sRecord = ar;

            Probe p = new Probe();
            if (!selfCheck(ar, buf, myEpoch, p)) {
                release(ar);
                sRecord = null;
                if (sEpoch != myEpoch) return;              // 被 stop 掉了，体面退出
                continue;                                     // 这条链路不行，试下一条
            }

            sSource = src;
            sState = 2;
            pumpLoop(ar, buf, myEpoch, p);
            release(ar);
            sRecord = null;

            if (sEpoch != myEpoch) return;                  // 被 stop 掉了
            if (sState == 0) return;
            if (!p.chainBroken) return;                     // 正常收尾，不必换链路
            // 链路跑着跑着坏了 —— 还有机会用后备链路救回来
        }

        synchronized (AudioBuffer.class) {
            if (sEpoch == myEpoch) {
                sState = 0;
                sSource = -1;
                if (sError.isEmpty()) sError = "录音设备打不开";
            }
        }
    }

    /** 自检期间的统计量 */
    private static final class Probe {
        int maxAbs = 0;
        int readErrors = 0;
        boolean chainBroken = false;
    }

    /**
     * 起录音后先读 2 秒，确认这条链路真的在出信号。
     * 这 2 秒的数据照样写进环形缓冲，不浪费 —— 自检通过后马上就能拿去识别。
     */
    private static boolean selfCheck(AudioRecord ar, byte[] buf, long myEpoch, Probe p) {
        long end = SystemClock.elapsedRealtime() + SELF_CHECK_MS;
        while (SystemClock.elapsedRealtime() < end) {
            if (sEpoch != myEpoch || sState != 1) return false;
            int n;
            try {
                n = ar.read(buf, 0, buf.length);
            } catch (Exception e) {
                p.chainBroken = true;
                sError = e.getClass().getSimpleName() + ": " + e.getMessage();
                return false;
            }
            if (n <= 0) {
                if (++p.readErrors > 8) { p.chainBroken = true; sError = "读不到录音数据"; return false; }
                continue;
            }
            writeRing(buf, n);
            feed(buf, n, p);
        }
        if (sEpoch != myEpoch) return false;
        if (p.maxAbs < DEAD_AIR_PEAK) {
            sError = "链路无信号（峰值 " + p.maxAbs + "）";
            return false;
        }
        return true;
    }

    /** 正式卷数据。myEpoch 是本线程所属世代，换代即退出。 */
    private static void pumpLoop(AudioRecord ar, byte[] buf, long myEpoch, Probe p) {
        while (sState == 2 && sEpoch == myEpoch) {
            int n;
            try {
                n = ar.read(buf, 0, buf.length);
            } catch (Exception e) {
                if (sEpoch == myEpoch) sError = e.getClass().getSimpleName() + ": " + e.getMessage();
                p.chainBroken = true;
                return;
            }
            if (n <= 0) {
                if (++p.readErrors > 30) {
                    if (sEpoch == myEpoch) sError = "读不到录音数据";
                    p.chainBroken = true;
                    return;
                }
                continue;
            }
            p.readErrors = 0;
            writeRing(buf, n);
            feed(buf, n, p);
        }
    }

    /** 写进环形缓冲（覆盖最旧数据） */
    private static void writeRing(byte[] buf, int n) {
        synchronized (LOCK) {
            for (int i = 0; i < n; i++) {
                RING[sWritePos] = buf[i];
                sWritePos++;
                if (sWritePos >= RING.length) sWritePos = 0;
            }
            sTotalWritten += n;
        }
        sLastWriteRealtime = SystemClock.elapsedRealtime();
    }

    /** 统计峰值（交给 Probe）+ 更新界面用的平滑音量 */
    private static void feed(byte[] buf, int n, Probe p) {
        long sumSq = 0L;
        int cnt = 0;
        for (int i = 0; i + 1 < n; i += 2) {
            int v = (buf[i] & 0xFF) | (buf[i + 1] << 8);
            int a = v < 0 ? -v : v;
            if (a > p.maxAbs) p.maxAbs = a;
            sumSq += (long) v * (long) v;
            cnt++;
        }
        if (cnt > 0) {
            double r = Math.sqrt((double) sumSq / (double) cnt);
            sRms = sRms * 0.6d + r * 0.4d;     // 平滑，别被单块抖动带跑
        }
    }

    // ------------------------------------------------------------ 取音频

    public static class Snap {
        public byte[] pcm = new byte[0];
        /** 这段音频自己的真实 RMS —— 静音判定用它，不用全局平滑值 */
        public double rms;
        /** 这段音频的峰值，用于决定要不要抬增益 */
        public int peak;
        /** 裁完之后真正送得出去的秒数（有效音乐秒数） */
        public int seconds;
        /** 实际送检长度（毫秒，比 seconds 准） */
        public int sentMs;
        /** 裁留白之前缓冲里实际可用的秒数 —— 用来区分「还没攒够」和「攒够了但都是留白」 */
        public int rawSec;
        /** 被裁掉的留白秒数（诊断用） */
        public int trimmedSec;
        /** 送检段末端距「现在」多久（毫秒）；正常为 0，段内改选更响子窗时才 >0 */
        public long lookbackMs;
        /** 送检段末端对应的本地时刻（elapsedRealtime），用于歌词进度校准 */
        public long endRealtime;
    }

    /** 取缓冲里最近 seconds 秒 */
    public static Snap take(int seconds) {
        return takeWindow(seconds, 0);
    }

    /** 完整窗口（不裁留白）的音频 + 这段末端对应的本地时刻，专供 Shazam 用 */
    public static class Full {
        public byte[] pcm = new byte[0];
        /** 这段 12 秒窗口末端对应的本地时刻（elapsedRealtime），歌词进度校准用 */
        public long endRealtime;
    }

    /**
     * 取缓冲里最近 seconds 秒的**完整原始 PCM（不裁留白）**，供 Shazam 用。
     *
     * 为什么 Shazam 要走单独一路、还不裁留白：
     *  Shazam 的指纹匹配**严格要求 ~12 秒完整音频** —— 实测 10 秒都匹配不到（0 命中）、
     *  12 秒才命中。而 ACRCloud 那路为了省额度会裁尾部留白，把 10 秒裁成 4~8 秒，
     *  对 Shazam 等于直接废掉。Shazam 算法本身对静音/留白不敏感（静音段没有指纹峰值），
     *  所以直接给它完整窗口即可，不必裁。
     *
     * 缓冲还没攒够 seconds 秒时，返回已攒到的全部（可能不足，Shazam 会匹配不到，
     * 但那是「还没攒够」的正常现象，不是错误）。
     */
    public static Full takeFull(int seconds) {
        Full f = new Full();
        int want = Math.min(seconds, KEEP_SEC) * BYTES_PER_SEC;
        want &= ~1;
        if (want <= 0) return f;
        synchronized (LOCK) {
            long total = Math.min(sTotalWritten, (long) RING.length);
            int avail = (int) (total & ~1L);
            if (avail < want) want = avail;
            if (want <= 0) return f;
            byte[] out = new byte[want];
            readBackLocked(out, 0L, want);
            f.pcm = out;
            f.endRealtime = sLastWriteRealtime;
            return f;
        }
    }

    /**
     * 取一段适合送识别的音频：**最近 seconds 秒里、不跨过最近一次能量谷底的那段连续音乐**。
     *
     * 为什么是「裁尾部留白」而不是「往回挑最响的窗口」—— 这点值得写清楚，因为它推翻了
     * 一开始的直觉方案：
     *  - 往回挑最响，在切歌瞬间会精准挑中**上一首**（上一首通常比新歌的前奏响得多），
     *    屏幕就滚成上一首的歌词 —— 比「认不出」糟得多。补上「不跨谷底」护栏之后，
     *    这条策略在真实车里几乎捞不到好处（护栏正好把收益场景都挡住了），风险却一直在。
     *  - 而把窗口尾部的留白/间奏尾巴裁掉是**纯收益**：ACRCloud 官方明写「接口只识别前
     *    12 秒，多余部分丢弃」，送 10 秒里有 4 秒是留白，等于只送 6 秒去匹配；
     *    裁完那 6 秒全是有效指纹点，密度实打实地上去。
     *  - 裁完还太短就干脆不发（{@link AudioRecorder} 那边管）：短样本认不出，
     *    失败了还会把退避期拉长，是「白烧额度 + 顺延机会」的双输。
     *
     * @param seconds     要多长
     * @param lookbackSec 裁后有效段比窗口还长时，允许在该段内往回找更响的等长子窗。
     *                    段内已经没有谷底，这一步跨不到上一首去，安全
     */
    public static Snap takeWindow(int seconds, int lookbackSec) {
        Snap s = new Snap();
        int want = Math.min(seconds, KEEP_SEC) * BYTES_PER_SEC;
        want &= ~1;
        if (want <= 0) { s.rms = sRms; s.endRealtime = sLastWriteRealtime; return s; }

        int winB = Math.max(1, want / BLOCK);
        byte[] raw;
        int nb;
        synchronized (LOCK) {
            long total = Math.min(sTotalWritten, (long) RING.length);
            int avail = (int) (total & ~1L);
            s.endRealtime = sLastWriteRealtime;
            if (avail < BLOCK) { s.rms = sRms; return s; }

            nb = Math.min(avail / BLOCK, winB);        // 只取最近这一段，绝不往更远处捞
            raw = new byte[nb * BLOCK];
            readBackLocked(raw, 0L, raw.length);
        }
        s.rawSec = (int) ((long) raw.length / (long) BYTES_PER_SEC);

        int[] br = new int[nb];
        WindowPicker.blockRms(raw, 0, raw.length, BLOCK, br);

        // 谷底之前的数据属于上一首 / 上一乐句 —— 不取。这一刀就是「不认错歌」的护栏。
        // 算法与回归测试共用 WindowPicker.trimValley，免得两处各写一份跑偏。
        int[] keep = WindowPicker.trimValley(br, nb);
        int keepFrom = keep[0];
        int keepB = keep[1];
        s.trimmedSec = (int) ((long) keepFrom * (long) BLOCK / (long) BYTES_PER_SEC);

        if (keepB <= 0) {                               // 整窗都是留白
            s.rms = WindowPicker.rmsOf(raw, 0, raw.length);
            s.peak = WindowPicker.peakOf(raw, 0, raw.length);
            return s;                                    // pcm 为空 → 上层按「没有有效音乐」处理
        }

        int start;
        int len;
        if (keepB >= winB) {
            // 有效段比窗口长：段内没有谷底，挑最响的等长子窗是安全的
            int backB = Math.max(0, lookbackSec) * BYTES_PER_SEC / BLOCK;
            int cand = WindowPicker.pick(br, nb, winB, backB, keepFrom);
            start = cand < 0 ? nb - winB : cand;
            if (start < keepFrom) start = keepFrom;
            len = winB;
        } else {
            start = keepFrom;                           // 有效段不足一个窗口：全给出去，够不够由上层判
            len = keepB;
        }

        int from = start * BLOCK;
        int bytes = len * BLOCK;
        if (from + bytes > raw.length) {
            bytes = raw.length - from;
            if (bytes < 0) bytes = 0;
            bytes &= ~1;
        }
        s.pcm = new byte[bytes];
        System.arraycopy(raw, from, s.pcm, 0, bytes);

        s.rms = WindowPicker.rmsOf(s.pcm, 0, bytes);
        s.peak = WindowPicker.peakOf(s.pcm, 0, bytes);
        s.sentMs = (int) ((long) bytes * 1000L / (long) BYTES_PER_SEC);
        s.seconds = bytes / BYTES_PER_SEC;

        long backBytes = (long) (raw.length - (from + bytes));
        s.lookbackMs = backBytes * 1000L / (long) BYTES_PER_SEC;
        s.endRealtime = sLastWriteRealtime - s.lookbackMs;
        return s;
    }

    /**
     * 把「以最新数据往回 endOffset 字节为终点、长 len 字节」的音频拷进 out。
     * 调用方须在 LOCK 里、并保证 len ≤ RING.length 且 endOffset + len ≤ 有效数据量。
     */
    private static void readBackLocked(byte[] out, long endOffset, int len) {
        long total = Math.min(sTotalWritten, (long) RING.length);
        if (len <= 0 || len > RING.length || endOffset + len > total) return;
        long startAbs = sTotalWritten - endOffset - len;
        int m = (int) (startAbs % RING.length);
        if (m < 0) m += RING.length;
        int first = Math.min(len, RING.length - m);
        System.arraycopy(RING, m, out, 0, first);
        if (len > first) System.arraycopy(RING, 0, out, first, len - first);
    }
}
