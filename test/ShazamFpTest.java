import com.wbhome.carlyrics.ShazamFingerprint;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 用 golden.raw（shazamio-core 对同一段 12 秒 16kHz 音频生成的真实指纹）验证 Java 移植。
 *
 * 跑法（在本目录）：
 *   "D:/Apps/jdk17/bin/javac.exe" -encoding UTF-8 -d out -sourcepath ../java ShazamFpTest.java
 *   "D:/Apps/jdk17/bin/java.exe"  -cp out ShazamFpTest
 *
 * 验收口径（两层）：
 *   1) 结构正确：header 字段、CRC32 自洽、TLV 可完整解码（这是硬要求）；
 *   2) 峰值接近：总峰数、四频段分布、逐峰一致率 —— 由于 FFT 内部用 double 而非 Rust 的
 *      realfft f32 算法，个别边界峰值可能有 ±1 差异，属预期；真正判死刑的是结构错。
 */
public class ShazamFpTest {

    static int pass = 0, fail = 0;

    public static void main(String[] a) throws Exception {
        short[] samples = readPcm(a.length > 0 ? a[0] : "sample16.pcm");
        System.out.println("样本：" + samples.length + " 样本 = " + (samples.length / 16000.0) + " 秒 @16kHz mono");

        byte[] mine = ShazamFingerprint.makeSignatureBinary(samples);
        String uri = ShazamFingerprint.makeSignatureUri(samples);
        byte[] golden = readAll("golden.raw");

        // 落盘，供识曲联调脚本使用
        java.nio.file.Files.write(java.nio.file.Paths.get("mine.raw"), mine);
        java.nio.file.Files.write(java.nio.file.Paths.get("mine.uri.txt"), uri.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        System.out.println("我的指纹：" + mine.length + " 字节，golden：" + golden.length + " 字节");
        System.out.println("URI 前缀：" + uri.substring(0, 33) + "…");
        System.out.println();

        // ---- 结构校验 ----
        check("长度接近 golden（差 " + Math.abs(mine.length - golden.length) + " 字节，峰值数略异导致）",
                Math.abs(mine.length - golden.length) <= 64);

        long m1 = u32(mine, 0), g1 = u32(golden, 0);
        long m2 = u32(mine, 12), g2 = u32(golden, 12);
        long mRate = u32(mine, 28), gRate = u32(golden, 28);
        long mSamples = u32(mine, 40), gSamples = u32(golden, 40);
        long mFixed = u32(mine, 44), gFixed = u32(golden, 44);
        check(String.format("magic1=0x%08x（期望 0xcafe2580）", m1), m1 == 0xcafe2580L);
        check(String.format("magic2=0x%08x（期望 0x94119c00）", m2), m2 == 0x94119c00L);
        check("采样率 id=3（16000）", (mRate >>> 27) == 3);
        check("number_samples_plus=195840（192000 + 16000*0.24）", mSamples == 195840);
        check("fixed_value=0x7c0000", mFixed == 0x7c0000L);
        check("size_minus_header = len-48", u32(mine, 8) == mine.length - 48);

        long crcMine = u32(mine, 4);
        long crcCalc = crc32(mine, 8, mine.length - 8);
        check("CRC32 自洽", crcMine == crcCalc);

        // ---- 峰值统计 ----
        List<List<long[]>> minePeaks = decodePeaks(mine);
        List<List<long[]>> goldenPeaks = decodePeaks(golden);

        int[] mc = new int[4], gc = new int[4];
        int mt = 0, gt = 0;
        for (int b = 0; b < 4; b++) {
            mc[b] = minePeaks.get(b).size();
            gc[b] = goldenPeaks.get(b).size();
            mt += mc[b];
            gt += gc[b];
        }
        System.out.println();
        System.out.println("频段峰值分布（我 / golden）："
                + "band0=" + mc[0] + "/" + gc[0]
                + "  band1=" + mc[1] + "/" + gc[1]
                + "  band2=" + mc[2] + "/" + gc[2]
                + "  band3=" + mc[3] + "/" + gc[3]
                + "  合计 " + mt + "/" + gt);

        check("总峰值数与 golden 相差 ≤ 5（" + mt + " vs " + gt + "）", Math.abs(mt - gt) <= 5);

        // ---- 逐峰一致率 ----
        // ---- 逐峰一致率（按 (fft_pass, 频率bin) 集合比，别按下标比）----
        // 下标逐位比会在个别峰值增删后全线错位、给出吓人的假低分；按集合比才是真相。
        int setTotal = 0, setMatch = 0;
        for (int b = 0; b < 4; b++) {
            List<long[]> mp = minePeaks.get(b);
            java.util.Set<String> gs = new java.util.HashSet<String>();
            for (long[] q : goldenPeaks.get(b)) gs.add(q[0] + "," + q[2]);
            for (long[] p : mp) {
                setTotal++;
                if (gs.contains(p[0] + "," + p[2])) setMatch++;
            }
        }
        double setRate = setTotal == 0 ? 0 : setMatch * 100.0 / setTotal;
        System.out.println();
        System.out.println("逐峰集合对照：(fft_pass, 频率bin) 精确命中 " + setMatch + "/" + setTotal
                + " = " + String.format("%.1f", setRate) + "%"
                + "（余下差异是 FFT 实现舍入：double vs realfft f32，高配频段更敏感，属预期）");

        check("逐峰集合一致率 ≥ 85%（真正的判据是 Shazam 能认出来）", setRate >= 85.0);

        System.out.println();
        System.out.println("通过 " + pass + " 项，失败 " + fail + " 项");
        if (fail > 0) System.exit(1);
    }

    /** 解码二进制指纹，返回每频段峰值列表；每条 long[]{fftPassNumber, peakMagnitude, correctedFreqBin}。 */
    static List<List<long[]>> decodePeaks(byte[] data) {
        List<long[]>[] bands = new List[4];
        for (int i = 0; i < 4; i++) bands[i] = new ArrayList<long[]>();

        int pos = 48;
        // 跳过 0x40000000 + size
        pos += 8;
        while (pos < data.length) {
            long bandId = u32(data, pos);
            long size = u32(data, pos + 4);
            pos += 8;
            int band = (int) (bandId - 0x60030040L);
            int end = pos + (int) size;
            long fftPass = 0;
            while (pos < end) {
                int off = data[pos] & 0xff;
                pos++;
                if (off == 0xff) {
                    fftPass = u32(data, pos);
                    pos += 4;
                } else {
                    fftPass += off;
                    long mag = u16(data, pos);
                    long freq = u16(data, pos + 2);
                    pos += 4;
                    bands[band].add(new long[]{fftPass, mag, freq});
                }
            }
            pos = end + ((4 - (int) (size % 4)) % 4);
        }
        List<List<long[]>> out = new ArrayList<List<long[]>>();
        for (int i = 0; i < 4; i++) out.add(bands[i]);
        return out;
    }

    static short[] readPcm(String path) throws Exception {
        byte[] all = readAll(path);
        short[] s = new short[all.length / 2];
        for (int i = 0; i < s.length; i++) {
            s[i] = (short) ((all[i * 2] & 0xff) | (all[i * 2 + 1] << 8));
        }
        return s;
    }

    static long u16(byte[] b, int o) { return (b[o] & 0xffL) | ((b[o + 1] & 0xffL) << 8); }
    static long u32(byte[] b, int o) {
        return (b[o] & 0xffL) | ((b[o + 1] & 0xffL) << 8)
                | ((b[o + 2] & 0xffL) << 16) | ((b[o + 3] & 0xffL) << 24);
    }

    static long crc32(byte[] b, int off, int len) {
        java.util.zip.CRC32 c = new java.util.zip.CRC32();
        c.update(b, off, len);
        return c.getValue();
    }

    static byte[] readAll(String path) throws Exception {
        InputStream in = new FileInputStream(path);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return bo.toByteArray();
    }

    static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("  ok   " + name); }
        else { fail++; System.out.println("  FAIL " + name); }
    }
}
