package com.wbhome.carlyrics;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * 取一段环境音，输出标准 WAV（ACRCloud / AudD 都直接能吃）。
 *
 * 这个类是「识别率」的直接责任人，四道关口都在这儿：
 *  1. **静音判定按实际取出的那段算**（{@link WindowPicker#rmsOf}）。
 *     早先版本拿的是全局指数平滑音量 —— 那只是最近约 0.1 秒的值，
 *     乐句之间一个正常的气口就能把它带到阈值以，把整段有音乐的音频判成静音直接跳过，
 *     用户体感就是「识别不到」，而日志里一条请求都看不到。
 *  2. **太短不发**。两秒的样本匹配率显著低于十秒，失败了还会把退避期拉长，
 *     反而更久才再试一次 —— 短样本是「白烧额度 + 顺延机会」的双输。
 *  3. **裁掉留白再送**（{@link AudioBuffer#takeWindow}）。
 *     ACRCloud 官方明写「只识别前 12 秒，多余部分丢弃」，窗口里的静音是纯浪费。
 *  4. **太轻的录音抬一下增益**。车机音量小、手机离得远时峰值可能只有几百，
 *     16bit 里有效位深太少、底噪占比高，指纹提取质量下降。
 *     指纹引擎对线性增益本身不敏感，所以这一步只在「确实很轻」时才动，且限幅防爆音。
 *
 * 两条路：
 *  1. {@link AudioBuffer} 在跑 → 直接从环形缓冲倒带取，**立刻返回**（快）；
 *  2. 缓冲没起来/拿不到 → 退回现场录一段（慢，但不至于完全瘫痪）。
 */
public final class AudioRecorder {

    public static final int RATE = 16000;

    /**
     * 静音判定阈值（RMS）。
     *
     * 这个值原来定的是 110，2026-09-15 实测发现是个坑：
     * 真实音乐在「文件满电平」时 RMS 才 119，音量降到 70% 就只有 83 —— 会被误判成静音直接跳过。
     *
     * 改成 40：只挡「真的一个字节的声音都没有」这种情况。
     * 省额度主要交给按歌曲剩余时长排期那套逻辑，不靠这个阈值硬卡 ——
     * 漏掉音乐的代价（功能不可用）远大于多发几次请求的代价。
     */
    private static final double SILENT_RMS = 40d;

    /** 至少要有这么多秒的音频才值得发出去识别 */
    private static final int MIN_SEC = 2;

    /**
     * 裁掉留白之后，**有效音乐**至少要这么多秒才发。
     * 低于它宁可不发：短样本匹配率明显低，失败了还要把退避期拉长，
     * 结果更久才再试一次 —— 是「白烧额度 + 顺延机会」的双输。
     * 4 秒是折中：ACRCloud 官方 SDK 的 rec_length 默认 10、允许下限也远小于 4，
     * 但那是「文件里整段都是音乐」的前提；车里带留白的场景要更保守。
     */
    private static final int MIN_SEND_SEC = 4;

    /**
     * Shazam 要的完整音频长度（秒）。
     * 实测：Shazam 的指纹匹配严格要求完整 ~12 秒 —— 10 秒都 0 命中、12 秒才命中。
     * 这与 ACRCloud「只识别前 12 秒、送 10 秒即可」完全不同，所以 Shazam 单独取这路，
     * 不裁留白（它算法对静音不敏感）。缓冲/现场录都必须保证给够这 12 秒。
     */
    public static final int SHAZAM_SEC = 12;

    /**
     * 缓冲「攒够了」的判据：实际可用秒数要达到请求时长的这个比例。
     * 注意管的是**攒够没有**，不是「有效内容够不够」—— 后者由 {@link #MIN_SEND_SEC} 管。
     * 0.7 是折中：太严会在刚启动时干等好几轮，太松又等于没管住短样本。
     */
    private static final double MIN_FILL = 0.7d;

    /** 峰值低于这个值才考虑抬增益（够响就不动，避免无谓改动原始波形） */
    private static final int TARGET_PEAK = 24000;

    /** 抬增益的上限（8 倍 ≈ +18dB）。再高只会把底噪一起端起来，没好处 */
    private static final int MAX_GAIN_PCT = 800;

    /** 增益不足 20% 就不折腾 */
    private static final int MIN_GAIN_PCT = 120;

    public static class Rec {
        public byte[] wav;
        /** 送检那段音频的真实 RMS */
        public double rms;
        /** 送检那段音频的峰值 */
        public int peak;
        public boolean silent;
        /** 缓冲还没攒够，本轮没发出去（不算识别失败，不该累加退避） */
        public boolean tooShort;
        public String error;
        /** 裁完之后真正送出去的秒数（有效音乐秒数） */
        public int seconds;
        /** 裁留白之前缓冲里可用的秒数（诊断用：区分「没攒够」和「攒够了但都是留白」） */
        public int rawSec;
        /** 被裁掉的留白秒数（诊断用） */
        public int trimmedSec;
        /** 实际送检长度（毫秒） */
        public int sentMs;
        /** true 表示这段音频是从常驻缓冲里倒带取的（没花等待时间） */
        public boolean fromBuffer;
        /** true 表示常驻缓冲这次没给到音频，本轮退回现场录了 */
        public boolean bufferFailed;
        /** 送检段末端距「现在」多久（毫秒）；正常为 0 */
        public long lookbackMs;
        /** 送检段末端对应的本地时刻（elapsedRealtime），歌词进度校准要用 */
        public long endRealtime;
        /** 实际生效的增益百分比（100 = 没抬） */
        public int gainPct = 100;
        /** Shazam 专用的完整 ~12 秒原始 PCM（16kHz mono s16，short[]，未裁留白、未抬增益） */
        public short[] shazamPcm;
        /** 实际送给 Shazam 的音频长度（毫秒；缓冲没攒够 12 秒时会不足） */
        public int shazamSentMs;
        /** 送给 Shazam 那段音频末端对应的本地时刻（elapsedRealtime） */
        public long shazamEndRealtime;
    }

    public static Rec record(int seconds) {
        return record(seconds, 0);
    }

    /**
     * @param seconds     想送多长
     * @param lookbackSec 有效段比窗口长时，允许在段内往回找更响的等长子窗
     */
    public static Rec record(int seconds, int lookbackSec) {
        if (AudioBuffer.alive()) {
            AudioBuffer.Snap snap = waitBuffer(seconds, lookbackSec);
            AudioBuffer.Full full = waitFull(SHAZAM_SEC);

            // 常驻缓冲明明标着「在跑」，却连 MIN_SEC 秒都拿不出来 —— 它已经坏了，停掉转现场录。
            // 注意判据用 rawSec（裁留白之前的可用量）：整窗都是留白时 pcm 也会很短，
            // 那属于「环境没在放歌」，不是缓冲坏掉，不能拿它当判据误杀。
            if (snap.rawSec < MIN_SEC && AudioBuffer.running()) {
                AudioBuffer.stop();
                Rec fresh = live(Math.max(seconds, SHAZAM_SEC));
                fresh.bufferFailed = true;
                return fresh;
            }

            Rec out = new Rec();
            out.rms = snap.rms;
            out.peak = snap.peak;
            out.rawSec = snap.rawSec;
            out.trimmedSec = snap.trimmedSec;
            out.lookbackMs = snap.lookbackMs;
            out.endRealtime = snap.endRealtime;
            out.seconds = snap.pcm.length / (RATE * 2);
            out.sentMs = snap.sentMs;
            out.fromBuffer = true;
            out.shazamPcm = toShorts(full.pcm);
            out.shazamSentMs = (int) ((long) full.pcm.length * 1000L / (long) (RATE * 2));
            out.shazamEndRealtime = full.endRealtime;

            // 攒够判据：Shazam 是主力，必须给够完整 12 秒才发；ACRCloud/AudD 那路窗口
            // 在 12 秒都攒够后必然也够了（seconds ≤ 12）。没攒够就等下一轮，别硬发短样本。
            if (full.pcm.length < SHAZAM_SEC * (RATE * 2)) {
                out.tooShort = true;
                return out;
            }

            // 攒够了，可裁掉留白之后没剩下多少有效音乐 —— 当作「本轮没在放歌」，不发、也不算失败
            if (out.seconds < MIN_SEND_SEC) {
                out.silent = true;
                return out;
            }

            out.silent = snap.rms < SILENT_RMS;
            if (!out.silent) {
                byte[] pcm = gain(snap.pcm, snap.peak, out);
                out.wav = wav(pcm, RATE, 1, 16);
            }
            return out;
        }

        return live(Math.max(seconds, SHAZAM_SEC));
    }

    /**
     * 从缓冲里等攒够。刚启动那几秒缓冲是空的（链路自检也要 2 秒），所以要等它攒。
     * 等的是 rawSec（可用量），不是裁后长度 —— 整窗留白是合法结果，不该一直空等。
     */
    private static AudioBuffer.Snap waitBuffer(int seconds, int lookbackSec) {
        int needFill = Math.max(MIN_SEC, (int) Math.ceil(seconds * MIN_FILL));
        long deadline = SystemClock.elapsedRealtime() + (seconds + 8) * 1000L;
        AudioBuffer.Snap snap = AudioBuffer.takeWindow(seconds, lookbackSec);
        while (snap.rawSec < needFill) {
            if (!AudioBuffer.alive()) break;
            if (SystemClock.elapsedRealtime() > deadline) break;
            try {
                Thread.sleep(120L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            snap = AudioBuffer.takeWindow(seconds, lookbackSec);
        }
        return snap;
    }

    /**
     * 等缓冲攒够 sec 秒的**完整音频（不裁留白）**，供 Shazam 用。
     * Shazam 严格要求完整 ~12 秒，所以 deadline 给足（sec + 8 秒），
     * 别跟 ACRCloud 那路「0.7 就够」的宽松判据混在一起。
     */
    private static AudioBuffer.Full waitFull(int sec) {
        long deadline = SystemClock.elapsedRealtime() + (sec + 8) * 1000L;
        AudioBuffer.Full full = AudioBuffer.takeFull(sec);
        while (full.pcm.length < sec * (RATE * 2)) {
            if (!AudioBuffer.alive()) break;
            if (SystemClock.elapsedRealtime() > deadline) break;
            try {
                Thread.sleep(120L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            full = AudioBuffer.takeFull(sec);
        }
        return full;
    }

    /** 现场录一段（降级路径，只在常驻缓冲不可用时走） */
    private static Rec live(int seconds) {
        Rec out = new Rec();
        AudioRecord ar = null;
        try {
            int minBuf = AudioRecord.getMinBufferSize(
                    RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minBuf <= 0) minBuf = RATE * 2;
            int chunk = Math.max(minBuf, RATE); // 每次读约 0.5 秒

            // 常驻缓冲选中的链路优先沿用；它没起来时（返回 -1）用默认 MIC
            int src = AudioBuffer.source();
            if (src < 0) src = MediaRecorder.AudioSource.MIC;

            ar = new AudioRecord(src, RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, chunk * 2);
            if (ar.getState() != AudioRecord.STATE_INITIALIZED) {
                out.error = "麦克风初始化失败";
                return out;
            }

            byte[] buf = new byte[chunk];
            ByteArrayOutputStream pcm = new ByteArrayOutputStream(RATE * 2 * seconds);
            ar.startRecording();

            long end = SystemClock.elapsedRealtime() + seconds * 1000L;
            while (SystemClock.elapsedRealtime() < end) {
                int n = ar.read(buf, 0, buf.length);
                if (n <= 0) continue;
                pcm.write(buf, 0, n);
            }
            byte[] all = pcm.toByteArray();

            out.rms = WindowPicker.rmsOf(all, 0, all.length);
            out.peak = WindowPicker.peakOf(all, 0, all.length);
            out.silent = out.rms < SILENT_RMS;
            out.seconds = all.length / (RATE * 2);
            out.sentMs = (int) ((long) all.length * 1000L / (long) (RATE * 2));
            out.endRealtime = SystemClock.elapsedRealtime();
            out.lookbackMs = 0L;
            out.shazamPcm = toShorts(all);
            out.shazamSentMs = out.sentMs;
            out.shazamEndRealtime = out.endRealtime;
            byte[] adj = gain(all, out.peak, out);
            out.wav = wav(adj, RATE, 1, 16);
        } catch (SecurityException e) {
            out.error = "没有录音权限";
        } catch (Exception e) {
            out.error = e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            if (ar != null) {
                try { ar.stop(); } catch (Exception ignore) { }
                try { ar.release(); } catch (Exception ignore) { }
            }
        }
        return out;
    }

    /**
     * 太轻的录音抬到接近满刻度。只在峰值确实很低时动，且限幅，避免把底噪顶穿。
     * 指纹引擎对线性增益不敏感，这一步的收益点在「有效位深」而不是「响度」。
     */
    private static byte[] gain(byte[] pcm, int peak, Rec out) {
        if (pcm == null || pcm.length == 0) return pcm;
        if (peak <= 0 || peak >= TARGET_PEAK) return pcm;
        int pct = (int) ((long) TARGET_PEAK * 100L / (long) peak);
        if (pct > MAX_GAIN_PCT) pct = MAX_GAIN_PCT;
        if (pct < MIN_GAIN_PCT) return pcm;

        if (out != null) out.gainPct = pct;
        byte[] o = Arrays.copyOf(pcm, pcm.length);
        for (int i = 0; i + 1 < o.length; i += 2) {
            int v = (o[i] & 0xFF) | (o[i + 1] << 8);
            v = v * pct / 100;
            if (v > 32767) v = 32767;
            else if (v < -32768) v = -32768;
            o[i] = (byte) (v & 0xFF);
            o[i + 1] = (byte) ((v >> 8) & 0xFF);
        }
        return o;
    }

    private static byte[] wav(byte[] pcm, int rate, int ch, int bits) {
        int byteRate = rate * ch * bits / 8;
        int dataLen = pcm.length;
        byte[] h = new byte[44];
        put(h, 0, "RIFF");
        putInt(h, 4, 36 + dataLen);
        put(h, 8, "WAVE");
        put(h, 12, "fmt ");
        putInt(h, 16, 16);
        putShort(h, 20, 1);
        putShort(h, 22, ch);
        putInt(h, 24, rate);
        putInt(h, 28, byteRate);
        putShort(h, 32, ch * bits / 8);
        putShort(h, 34, bits);
        put(h, 36, "data");
        putInt(h, 40, dataLen);
        byte[] out = new byte[44 + dataLen];
        System.arraycopy(h, 0, out, 0, 44);
        System.arraycopy(pcm, 0, out, 44, dataLen);
        return out;
    }

    /** 纯 PCM byte[]（16kHz mono s16 小端，无 WAV 头）转 short[]，供 Shazam 指纹用 */
    static short[] toShorts(byte[] pcm) {
        if (pcm == null) return new short[0];
        int n = pcm.length / 2;
        short[] s = new short[n];
        for (int i = 0; i < n; i++) {
            s[i] = (short) ((pcm[i * 2] & 0xff) | (pcm[i * 2 + 1] << 8));
        }
        return s;
    }

    private static void put(byte[] b, int off, String s) {
        for (int i = 0; i < s.length(); i++) b[off + i] = (byte) s.charAt(i);
    }

    private static void putInt(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xFF);
        b[off + 1] = (byte) ((v >> 8) & 0xFF);
        b[off + 2] = (byte) ((v >> 16) & 0xFF);
        b[off + 3] = (byte) ((v >> 24) & 0xFF);
    }

    private static void putShort(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xFF);
        b[off + 1] = (byte) ((v >> 8) & 0xFF);
    }
}
