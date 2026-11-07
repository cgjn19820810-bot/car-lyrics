import java.io.*;

/**
 * 标定静音阈值：真实音乐在不同音量下的 RMS 是多少？
 * 如果 App 的阈值(110)离真实音乐的实际电平太近，车机音量小一点就会被误判成「车里很安静」而不去识别。
 */
public class C {

    static double rmsOf(byte[] pcm, int from, int len, double gain) {
        double sumSq = 0d; long cnt = 0;
        for (int i = from; i + 1 < from + len && i + 1 < pcm.length; i += 2) {
            short v = (short) ((pcm[i] & 0xFF) | (pcm[i + 1] << 8));
            double x = v * gain;
            sumSq += x * x; cnt++;
        }
        return cnt > 0 ? Math.sqrt(sumSq / cnt) : 0d;
    }

    public static void main(String[] a) throws Exception {
        File f = new File("ex.pcm");
        FileInputStream in = new FileInputStream(f);
        byte[] pcm = new byte[(int) f.length()];
        int off = 0;
        while (off < pcm.length) { int n = in.read(pcm, off, pcm.length - off); if (n <= 0) break; off += n; }
        in.close();
        System.out.println("样本：" + (pcm.length / 32000.0) + " 秒真实音乐\n");

        // 整段 vs 逐秒，看波动
        System.out.println("整段 RMS = " + (int) rmsOf(pcm, 0, pcm.length, 1.0));
        System.out.print("逐秒 RMS =");
        for (int s = 0; s + 32000 <= pcm.length; s += 32000) {
            System.out.print("  " + (int) rmsOf(pcm, s, 32000, 1.0));
        }
        System.out.println("\n");

        System.out.println("--- 不同音量下会读到多少 RMS ---");
        double[] gains = {1.0, 0.7, 0.5, 0.35, 0.25, 0.15, 0.1, 0.05, 0.02, 0.01};
        for (double g : gains) {
            int r = (int) rmsOf(pcm, 0, pcm.length, g);
            String verdict = r < 110 ? "→ 会被当成静音，跳过识别 ✗" : "→ 正常送识别 ✓";
            System.out.printf("音量 %4.0f%%    RMS = %4d   %s%n", g * 100, r, verdict);
        }
    }
}
