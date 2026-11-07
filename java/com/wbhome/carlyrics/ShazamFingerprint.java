package com.wbhome.carlyrics;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

/**
 * Shazam 音频指纹生成器（Wang 2003 算法，移植自 SongRec / shazamio-core 的 Rust 实现）。
 *
 * 纯逻辑、不依赖 Android，可在 PC 上直接回归（见 test/ShazamFpTest.java）。
 * 输入：16 kHz 单声道 s16le 原始 PCM（short[]）。
 * 输出：data:audio/vnd.shazam.sig;base64,... 数据 URI，可直接 POST 到 Shazam 识曲接口。
 *
 * 算法要点（与 rs_algorithm.rs 逐行对齐）：
 *  - 每 128 样本推进一次 2048 点 FFT（Hanning 窗），功率谱 (re²+im²)/2¹⁷ 下限 1e-10；
 *  - 频域扩展（相邻 3 bin 取 max）+ 时域扩展（往前 1/3/6 帧取 max）；
 *  - 峰值识别（bin 10..1014，频域邻域 {-10,-7,-4,-3,1,2,5,8}、时域邻域 {-53,-45,165..249}）；
 *  - 4 个频段 250-520 / 520-1450 / 1450-3500 / 3500-5500 Hz；
 *  - 峰值编码成二进制（48 字节头 + CRC32 + TLV delta）+ base64。
 */
public final class ShazamFingerprint {

    private static final int SAMPLE_RATE = 16000;
    private static final float[] HANNING = Hanning.WINDOW_2048;

    /** 单个峰值。 */
    public static final class Peak {
        public final int fftPassNumber;
        public final int peakMagnitude;   // u16
        public final int correctedFreqBin; // u16
        Peak(int f, int m, int c) {
            fftPassNumber = f;
            peakMagnitude = m;
            correctedFreqBin = c;
        }
    }

    // —— 状态（对齐 SignatureGenerator）——
    private final short[] ring = new short[2048];
    private int ringIndex = 0;
    private final float[] reordered = new float[2048];
    private final float[][] fftOutputs = new float[256][1025];
    private int fftIndex = 0;
    private final float[][] spreadOutputs = new float[256][1025];
    private int spreadIndex = 0;
    private int numSpreadFftsDone = 0;
    private final List<Peak>[] bands;

    // FFT 暂存（double 内部，最终 cast 到 float 与 Rust f32 域对齐）
    private final double[] fftRe = new double[2048];
    private final double[] fftIm = new double[2048];

    @SuppressWarnings("unchecked")
    private ShazamFingerprint() {
        bands = new List[4];
        for (int i = 0; i < 4; i++) bands[i] = new ArrayList<Peak>();
    }

    /** 生成完整数据 URI（可直接作为 Shazam 识曲请求的 sample_bytes）。 */
    public static String makeSignatureUri(short[] pcm16kMono) {
        return "data:audio/vnd.shazam.sig;base64,"
                + java.util.Base64.getEncoder().encodeToString(makeSignatureBinary(pcm16kMono));
    }

    /** 生成原始二进制指纹（48 字节头 + TLV，含 CRC32）。 */
    public static byte[] makeSignatureBinary(short[] pcm16kMono) {
        ShazamFingerprint g = new ShazamFingerprint();
        int numChunks = pcm16kMono.length / 128; // 丢弃不足 128 的尾块（与 Rust as_chunks 一致）
        for (int c = 0; c < numChunks; c++) {
            g.doFft(pcm16kMono, c * 128);
            g.doPeakSpreading();
            g.numSpreadFftsDone++;
            if (g.numSpreadFftsDone >= 46) {
                g.doPeakRecognition();
            }
        }
        return g.encode(pcm16kMono.length);
    }

    private void doFft(short[] pcm, int offset) {
        System.arraycopy(pcm, offset, ring, ringIndex, 128);
        ringIndex = (ringIndex + 128) & 2047;

        for (int j = 0; j < 2048; j++) {
            reordered[j] = ring[(j + ringIndex) & 2047] * HANNING[j];
        }

        for (int i = 0; i < 2048; i++) {
            fftRe[i] = reordered[i];
            fftIm[i] = 0.0;
        }
        fft2048(fftRe, fftIm);

        float[] out = fftOutputs[fftIndex];
        for (int bin = 0; bin <= 1024; bin++) {
            float re = (float) fftRe[bin];
            float im = (float) fftIm[bin];
            float power = (re * re + im * im) / 131072.0f;
            out[bin] = power > 1e-10f ? power : 1e-10f;
        }
        fftIndex = (fftIndex + 1) & 255;
    }

    private void doPeakSpreading() {
        float[] real = fftOutputs[(fftIndex - 1) & 255];
        float[] spread = spreadOutputs[spreadIndex];
        System.arraycopy(real, 0, spread, 0, 1025);

        // 频域扩展：spread[pos] = max(spread[pos], spread[pos+1], spread[pos+2])
        for (int pos = 0; pos <= 1022; pos++) {
            float a = spread[pos];
            float b = spread[pos + 1];
            float c = spread[pos + 2];
            float m = a > b ? a : b;
            if (c > m) m = c;
            spread[pos] = m;
        }

        // 时域扩展：把当前帧（已频域扩展）回填到前 1/3/6 帧
        for (int pos = 0; pos <= 1024; pos++) {
            float v = spread[pos];
            spreadOutputs[(spreadIndex - 1) & 255][pos] =
                    max(spreadOutputs[(spreadIndex - 1) & 255][pos], v);
            spreadOutputs[(spreadIndex - 3) & 255][pos] =
                    max(spreadOutputs[(spreadIndex - 3) & 255][pos], v);
            spreadOutputs[(spreadIndex - 6) & 255][pos] =
                    max(spreadOutputs[(spreadIndex - 6) & 255][pos], v);
        }

        spreadIndex = (spreadIndex + 1) & 255;
    }

    private void doPeakRecognition() {
        float[] fftMinus46 = fftOutputs[(fftIndex - 46) & 255];
        float[] fftMinus49 = spreadOutputs[(spreadIndex - 49) & 255];

        int[] freqNeighbors = {-10, -7, -4, -3, 1, 2, 5, 8};
        int[] timeNeighbors = {-53, -45, 165, 172, 179, 186, 193, 200, 214, 221, 228, 235, 242, 249};

        for (int bin = 10; bin <= 1014; bin++) {
            if (fftMinus46[bin] >= 1.0f / 64.0f && fftMinus46[bin] >= fftMinus49[bin - 1]) {
                // 频域局部最小
                float maxNeighbor = 0.0f;
                for (int off : freqNeighbors) {
                    float v = fftMinus49[bin + off];
                    if (v > maxNeighbor) maxNeighbor = v;
                }
                if (fftMinus46[bin] > maxNeighbor) {
                    // 时域局部最小
                    float maxOther = maxNeighbor;
                    for (int off : timeNeighbors) {
                        float v = spreadOutputs[(spreadIndex + off) & 255][bin - 1];
                        if (v > maxOther) maxOther = v;
                    }
                    if (fftMinus46[bin] > maxOther) {
                        int fftPassNumber = numSpreadFftsDone - 46;

                        float peakMag = peakMagnitude(fftMinus46[bin]);
                        float peakMagBefore = peakMagnitude(fftMinus46[bin - 1]);
                        float peakMagAfter = peakMagnitude(fftMinus46[bin + 1]);

                        float variation1 = peakMag * 2.0f - peakMagBefore - peakMagAfter;
                        float variation2 = (peakMagAfter - peakMagBefore) * 32.0f / variation1;

                        int correctedBin = bin * 64 + (int) variation2;
                        if (correctedBin < 0) correctedBin = 0;
                        if (correctedBin > 65535) correctedBin = 65535;

                        float frequencyHz = correctedBin * (16000.0f / 2.0f / 1024.0f / 64.0f);
                        int freqInt = (int) frequencyHz;

                        int band;
                        if (freqInt >= 250 && freqInt <= 519) band = 0;
                        else if (freqInt >= 520 && freqInt <= 1449) band = 1;
                        else if (freqInt >= 1450 && freqInt <= 3499) band = 2;
                        else if (freqInt >= 3500 && freqInt <= 5500) band = 3;
                        else continue;

                        int magInt = (int) peakMag;
                        if (magInt < 0) magInt = 0;
                        if (magInt > 65535) magInt = 65535;

                        bands[band].add(new Peak(fftPassNumber, magInt, correctedBin));
                    }
                }
            }
        }
    }

    /** max(ln(v), 1/64) * 1477.3 + 6144，对齐 Rust 的 f32 计算。 */
    private static float peakMagnitude(float v) {
        float ln = (float) Math.log((double) v);
        if (ln < 0.015625f) ln = 0.015625f;
        return ln * 1477.3f + 6144.0f;
    }

    private static float max(float a, float b) {
        return a > b ? a : b;
    }

    // —— 二进制编码（对齐 rs_signature_format.rs encode_to_binary）——

    private byte[] encode(int numberSamples) {
        byte[][] bandBuf = new byte[4][];
        for (int b = 0; b < 4; b++) {
            List<Peak> peaks = bands[b];
            if (peaks.isEmpty()) continue;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            int fftPass = 0;
            for (Peak p : peaks) {
                if (p.fftPassNumber - fftPass >= 255) {
                    bos.write(0xff);
                    writeU32LE(bos, p.fftPassNumber);
                    fftPass = p.fftPassNumber;
                }
                bos.write(p.fftPassNumber - fftPass);
                writeU16LE(bos, p.peakMagnitude);
                writeU16LE(bos, p.correctedFreqBin);
                fftPass = p.fftPassNumber;
            }
            bandBuf[b] = bos.toByteArray();
        }

        int total = 48 + 8;
        for (int b = 0; b < 4; b++) {
            if (bandBuf[b] != null) {
                total += 8 + bandBuf[b].length + ((4 - bandBuf[b].length % 4) % 4);
            }
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(total);
        writeU32LE(out, 0xcafe2580);           // magic1
        writeU32LE(out, 0);                     // crc32 占位
        writeU32LE(out, total - 48);            // size_minus_header
        writeU32LE(out, 0x94119c00);            // magic2
        writeU32LE(out, 0);
        writeU32LE(out, 0);
        writeU32LE(out, 0);                     // void1 x3
        writeU32LE(out, 3 << 27);               // 采样率 16000 -> id 3
        writeU32LE(out, 0);
        writeU32LE(out, 0);                     // void2 x2
        writeU32LE(out, numberSamples + (int) (16000.0f * 0.24f));
        writeU32LE(out, (15 << 19) + 0x40000);  // fixed_value 0x7c0000
        writeU32LE(out, 0x40000000);
        writeU32LE(out, total - 48);

        for (int b = 0; b < 4; b++) {
            if (bandBuf[b] == null) continue;
            writeU32LE(out, 0x60030040 + b);
            writeU32LE(out, bandBuf[b].length);
            out.write(bandBuf[b], 0, bandBuf[b].length);
            int pad = (4 - bandBuf[b].length % 4) % 4;
            for (int i = 0; i < pad; i++) out.write(0);
        }

        byte[] data = out.toByteArray();
        CRC32 crc = new CRC32();
        crc.update(data, 8, data.length - 8);
        long c = crc.getValue();
        data[4] = (byte) (c & 0xff);
        data[5] = (byte) ((c >>> 8) & 0xff);
        data[6] = (byte) ((c >>> 16) & 0xff);
        data[7] = (byte) ((c >>> 24) & 0xff);
        return data;
    }

    private static void writeU16LE(ByteArrayOutputStream out, int v) {
        out.write(v & 0xff);
        out.write((v >>> 8) & 0xff);
    }

    private static void writeU32LE(ByteArrayOutputStream out, long v) {
        out.write((int) (v & 0xff));
        out.write((int) ((v >>> 8) & 0xff));
        out.write((int) ((v >>> 16) & 0xff));
        out.write((int) ((v >>> 24) & 0xff));
    }

    // —— 迭代 radix-2 复数 FFT（decimation-in-time），N=2048 ——

    static void fft2048(double[] re, double[] im) {
        int n = re.length;
        // bit-reversal
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double tr = re[i]; re[i] = re[j]; re[j] = tr;
                double ti = im[i]; im[i] = im[j]; im[j] = ti;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2.0 * Math.PI / len;
            double wRe = Math.cos(ang);
            double wIm = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double curRe = 1.0, curIm = 0.0;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k;
                    int b = i + k + len / 2;
                    double tRe = curRe * re[b] - curIm * im[b];
                    double tIm = curRe * im[b] + curIm * re[b];
                    re[b] = re[a] - tRe;
                    im[b] = im[a] - tIm;
                    re[a] = re[a] + tRe;
                    im[a] = im[a] + tIm;
                    double nRe = curRe * wRe - curIm * wIm;
                    curIm = curRe * wIm + curIm * wRe;
                    curRe = nRe;
                }
            }
        }
    }
}
