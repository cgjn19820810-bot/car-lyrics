import com.wbhome.carlyrics.WindowPicker;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;

/**
 * 用**真实音乐**验证取窗策略，而不是只拿构造数据自证。
 *
 * 样本 ex.pcm：16kHz / 单声道 / 16bit / 40 秒，由真实歌曲文件转出来的
 * （生成命令写在 test/README.md 里）。之所以要拿真音乐跑：静音阈值、谷底门限这类东西
 * 用理想方波测永远漂亮，真录音的动态范围、底噪、乐句留白才是车里真正遇到的样子。
 *
 * 这里调的就是 App 线上跑的那一份 {@link WindowPicker#trimValley}，
 * 不是测试里另写一份近似实现 —— 否则测过了也不代表线上对。
 *
 * 跑法（在本目录）：
 *   "D:/Apps/jdk17/bin/javac.exe" -encoding UTF-8 -d out -sourcepath ../java L.java
 *   "D:/Apps/jdk17/bin/java.exe"  -cp out L
 */
public class L {

    static final int RATE = 16000;
    static final int BPS = RATE * 2;          // 每秒字节数
    static final int BLOCK = BPS / 2;         // 0.5 秒一块
    static final int WIN = 20;                // 10 秒窗口（对应默认送检时长）
    static final int MIN_SEND_SEC = 4;        // 与 AudioRecorder.MIN_SEND_SEC 保持一致

    static int pass = 0;
    static int fail = 0;

    public static void main(String[] a) throws Exception {
        byte[] base = readAll(a.length > 0 ? a[0] : "ex.pcm");
        int totalSec = base.length / BPS;
        System.out.println("样本：" + totalSec + " 秒真实音乐（" + (base.length / 1024) + " KB）");
        System.out.println("窗口：" + (WIN * BLOCK / (double) BPS) + " 秒，块粒度 0.5 秒，"
                + "有效判据 = 块 RMS ≥ 窗口内最大块的 " + WindowPicker.FLOOR_PCT + "%（地板 "
                + WindowPicker.FLOOR_MIN + "）");
        System.out.println();

        // ---- 场景 1：一切正常，正在放歌 ----
        scenario("场景 1｜正常播放（无留白）", base, 20, null,
                "送检秒数与旧策略一致，且有效率应接近 100%");

        // ---- 场景 2：间奏刚结束 —— 最近 10 秒的头 4 秒是留白 ----
        // ★ 留白必须落在窗口内才测得出东西。窗口 = 缓冲的最后 WIN 块 = 缓冲第 10~20 秒，
        //   所以「窗头 4 秒留白」是第 10~14 秒，不是第 6~10 秒（写错会得到新旧数字完全相同
        //   的假绿 —— 裁了个寂寞，还一切「通过」）。
        scenario("场景 2｜间奏刚结束（窗头 4 秒留白）", base, 20,
                new double[] {10, 14, 0.02d},
                "裁掉窗头留白后，送出去的应当全是有效音乐（有效率 100%）");

        // ---- 场景 3：切歌后新歌还没起来 —— 最近 10 秒的后 6 秒是留白 ----
        scenario("场景 3｜切歌后新歌未起（窗尾 6 秒留白）", base, 20,
                new double[] {14, 20, 0.02d},
                "谷底就在眼前 ⇒ 新策略应判定「本轮没有有效音乐」而不发；"
                        + "旧策略会把上一首的尾巴当音乐送出去，很可能滚成上一首的歌词");

        // ---- 场景 4：切歌瞬间 —— 上一首很响、中间留白、新歌只有前奏 ----
        scenario4(base);

        System.out.println();
        System.out.println("通过 " + pass + " 项，失败 " + fail + " 项");
        if (fail > 0) System.exit(1);
    }

    // ---------------------------------------------------------------- 场景

    /**
     * @param tailSec 取样本末尾多少秒当「缓冲里的内容」
     * @param dip     {起秒, 止秒, 系数}（相对缓冲段起点），把这段压成留白；可为 null
     */
    static void scenario(String name, byte[] base, int tailSec, double[] dip, String note) {
        byte[] pcm = tail(base, tailSec);

        int totalBlocks = pcm.length / BLOCK;
        int wFrom = totalBlocks - WIN;                 // 最近 10 秒
        if (wFrom < 0) wFrom = 0;
        int n = Math.min(WIN, totalBlocks);

        if (dip != null) {
            scale(pcm, (int) dip[0], (int) dip[1], dip[2]);
            // 自检：留白段必须真的落进窗口，否则新旧策略数字会完全相同 —— 看着「全过」，其实什么都没测到
            boolean overlaps = dip[1] * 2 > wFrom && dip[0] * 2 < wFrom + n;
            check(name + "：留白确实落进窗口内（否则这组对照无效）", overlaps);
        }

        int[] br = new int[n];
        WindowPicker.blockRms(pcm, wFrom * BLOCK, n * BLOCK, BLOCK, br);

        int floor = WindowPicker.floorOf(br, n);
        int oldEff = count(br, 0, n, floor);

        int[] keep = WindowPicker.trimValley(br, n);
        int keepFrom = keep[0], keepB = keep[1];
        int newSend, newEff;
        if (keepB >= WIN) {
            int s = WindowPicker.pick(br, n, WIN, 0, keepFrom);
            if (s < 0) s = n - WIN;
            newSend = WIN;
            newEff = count(br, s, s + WIN, floor);
        } else {
            newSend = keepB;
            newEff = count(br, keepFrom, keepFrom + keepB, floor);
        }

        System.out.println(name);
        System.out.println("  旧（固定最近 10 秒）  送 " + sec(n) + " 秒   有效 "
                + sec(oldEff) + " 秒   有效率 " + pct(oldEff, n));
        System.out.println("  新（裁尾留白）        送 " + sec(newSend) + " 秒   有效 "
                + sec(newEff) + " 秒   有效率 " + pct(newEff, newSend)
                + (newSend == 0 ? "   ← 不发这一轮" : ""));
        System.out.println("  说明：" + note);
        System.out.println();

        if (dip == null) {
            // 正常播放：不该比旧策略少送（真音乐里若真有乐句留白，裁掉是对的，但绝不能裁到没送）
            check(name + "：正常播放时不多裁、不少送", newSend >= n - 1);
            check(name + "：正常播放时送出去的全是有效音乐", newSend > 0 && newEff == newSend);
            return;
        }
        if (dip[1] - dip[0] >= 5) {
            // 留白在窗尾（切歌后新歌未起）：新策略应当不发或只发极少，避免把上一首当新歌
            check(name + "：窗尾留白时不再把上一首当有效音乐送出去", newSend <= MIN_SEND_SEC - 1 || newEff <= MIN_SEND_SEC - 1);
            return;
        }
        // 留白在窗头（间奏刚结束）：裁完之后有效率应当拉满
        check(name + "：裁完有效率 100%", newSend > 0 && newEff == newSend);
        check(name + "：裁完送检密度高于旧策略",
                density(newEff, newSend) >= density(oldEff, n));
    }

    /** 切歌：上一首很响 → 留白 → 新歌只有前奏。关键是「一块都不许碰上一首」 */
    static void scenario4(byte[] base) {
        byte[] pcm = tail(base, 36);
        scale(pcm, 26, 28, 0.002d);      // 切歌留白
        scale(pcm, 28, 32, 0.05d);       // 新歌前奏（很轻）
        // 32~36 秒保持原样：新歌正歌

        int totalBlocks = pcm.length / BLOCK;            // 72 块
        int n = Math.min(WIN, totalBlocks);
        int wFrom = totalBlocks - n;
        int[] br = new int[n];
        WindowPicker.blockRms(pcm, wFrom * BLOCK, n * BLOCK, BLOCK, br);

        int floor = WindowPicker.floorOf(br, n);
        int[] keep = WindowPicker.trimValley(br, n);
        int keepFromAbs = wFrom + keep[0];

        // 上一首占绝对块 0..51，留白从 52 开始 ⇒ 裁后起点必须 >= 52
        System.out.println("场景 4｜切歌瞬间（上一首响 → 留白 → 新歌前奏）");
        System.out.println("  窗口绝对块区间 [" + wFrom + "," + totalBlocks + ")，上一首占 [0,52)");
        System.out.println("  谷底门限 " + floor + "，裁后起点（绝对块）= " + keepFromAbs
                + "，保留 " + keep[1] + " 块 = " + sec(keep[1]) + " 秒");
        System.out.println("  说明：只按音量挑窗口会整段落回上一首（它比新歌前奏响得多），"
                + "屏幕上就滚成上一首的歌词 —— 比「认不出」糟得多");
        System.out.println();

        check("场景 4：窗口内确有谷底被裁掉（否则这组对照无效）", keep[0] > 0);
        check("场景 4：裁后一块都不碰上一首", keepFromAbs >= 52);
        check("场景 4：谷底之后还有正歌可送（裁完不是直接归零）", keep[1] > 0);
    }

    // ---------------------------------------------------------------- 工具

    static byte[] tail(byte[] src, int sec) {
        int bytes = sec * BPS;
        if (bytes > src.length) bytes = (src.length / BLOCK) * BLOCK;
        byte[] o = new byte[bytes];
        System.arraycopy(src, src.length - bytes, o, 0, bytes);
        return o;
    }

    static void scale(byte[] pcm, int fromSec, int toSec, double k) {
        int from = fromSec * BPS;
        int to = Math.min(toSec * BPS, pcm.length);
        for (int i = from; i + 1 < to; i += 2) {
            int v = (pcm[i] & 0xFF) | (pcm[i + 1] << 8);
            v = (int) (v * k);
            if (v > 32767) v = 32767;
            else if (v < -32768) v = -32768;
            pcm[i] = (byte) (v & 0xFF);
            pcm[i + 1] = (byte) ((v >> 8) & 0xFF);
        }
    }

    /** [from, to) 里 RMS 达到门限的块数 —— 「有效音乐秒数」的度量 */
    static int count(int[] br, int from, int to, int floor) {
        int c = 0;
        int hi = Math.min(to, br.length);
        for (int i = Math.max(0, from); i < hi; i++) if (br[i] >= floor) c++;
        return c;
    }

    static double density(int eff, int send) {
        return send <= 0 ? 0d : (double) eff / (double) send;
    }

    static String sec(int blocks) {
        return String.format("%.1f", blocks * BLOCK / (double) BPS);
    }

    static String pct(int eff, int send) {
        if (send <= 0) return "—";
        return String.format("%.0f%%", density(eff, send) * 100d);
    }

    static void check(String name, boolean ok) {
        if (ok) {
            pass++;
            System.out.println("  ok   " + name);
        } else {
            fail++;
            System.out.println("  FAIL " + name);
        }
    }

    static byte[] readAll(String path) throws Exception {
        InputStream in = new FileInputStream(path);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        byte[] all = bo.toByteArray();
        return (all.length / BLOCK) * BLOCK == all.length ? all : java.util.Arrays.copyOf(all, (all.length / BLOCK) * BLOCK);
    }
}
