import com.wbhome.carlyrics.WindowPicker;

/**
 * WindowPicker 回归测试 —— 纯逻辑，PC 上直接跑，不需要手机也不需要 Android 依赖。
 *
 * 跑法（在本目录）：
 *   "D:/Apps/jdk17/bin/javac.exe" -encoding UTF-8 -d out -sourcepath ../java WP.java
 *   "D:/Apps/jdk17/bin/java.exe"  -cp out WP
 */
public class WP {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] a) {
        // ---------- pick：窗口长度 / 回看余量 / 边界 ----------
        eq("数据不够返回 -1", WindowPicker.pick(new int[] {5, 5}, 2, 3, 4), -1);
        eq("win<=0 返回 -1", WindowPicker.pick(new int[] {5, 5}, 2, 0, 4), -1);
        eq("rms 为 null 返回 -1", WindowPicker.pick(null, 5, 2, 1), -1);
        eq("刚好够一块窗口", WindowPicker.pick(new int[] {1, 2, 3}, 3, 3, 0), 0);

        // back=0 ⇒ 窗口末端必须压在最新数据上，只能从 n-win 开始
        eq("back=0 只能取最新一段", WindowPicker.pick(new int[] {99, 1, 1, 1}, 4, 2, 0), 2);

        // 前响后静：允许回看时应抓住前面的响段
        eq("回看时挑最响的段", WindowPicker.pick(new int[] {900, 900, 10, 10}, 4, 2, 2), 0);
        // 回看余量只有 1 块时够不到起点 0，但起点 1 的窗口 [1,2] 末端只回看 1 块，
        // 合法且仍含着一个响块 —— 这正是「护栏之内尽量挑响」该有的行为
        eq("回看受限时取护栏内最响", WindowPicker.pick(new int[] {900, 900, 10, 10}, 4, 2, 1), 1);
        // 响段完全落在回看护栏之外 ⇒ 老老实实退回最新一段，绝不捞旧歌
        eq("响段在护栏外则退回最新",
                WindowPicker.pick(new int[] {900, 900, 10, 10, 10, 10}, 6, 2, 1), 4);

        // 整段一样响 ⇒ 取更靠后的（更贴近当下，歌词校准更准）
        eq("同音量取更靠后", WindowPicker.pick(new int[] {500, 500, 500, 500}, 4, 2, 2), 2);

        // 中间最响：应落在中间那个起点
        eq("中间最响", WindowPicker.pick(new int[] {10, 800, 800, 10}, 4, 2, 2), 1);

        // back 比可用范围还大时不能越界（firstStart 夹到 0）
        eq("back 过大不越界", WindowPicker.pick(new int[] {1, 2, 3, 4, 5}, 5, 2, 99), 3);

        // 只看前 n 个，后面的脏数据不能参与
        eq("超出 n 的数据不参与",
                WindowPicker.pick(new int[] {1, 1, 1, 9999, 9999}, 3, 2, 1), 1);

        // 单块窗口：直接取最响那一块（在回看余量内）
        eq("单块窗口取最响", WindowPicker.pick(new int[] {700, 1, 1, 300}, 4, 1, 3), 0);

        // 全零（真静音）也要给出一个合法起点，不能返回 -1 —— 由上层按 RMS 判静音
        eq("全零仍给合法起点", WindowPicker.pick(new int[] {0, 0, 0}, 3, 2, 1), 1);

        // ---------- 谷底护栏：切歌瞬间不许挑中上一首 ----------
        // 块 = 0.5 秒。上一首 52 块(26 秒,2000) | 切歌留白 4 块(5)
        // | 新歌前奏 8 块(150) | 新歌正歌 8 块(1800)
        int[] cut = new int[72];
        for (int i = 0; i < 52; i++) cut[i] = 2000;
        for (int i = 52; i < 56; i++) cut[i] = 5;
        for (int i = 56; i < 64; i++) cut[i] = 150;
        for (int i = 64; i < 72; i++) cut[i] = 1800;

        // 没有护栏：窗口整段落进上一首（它比新歌前奏响得多）—— 这就是必须加护栏的原因
        eq("无护栏会整窗挑中上一首", WindowPicker.pick(cut, 72, 20, 40, 0), 32);
        // 有护栏（起点不得越过留白）：只能取到当下这一段，一块都不碰上一首
        eq("有护栏退回最近一段", WindowPicker.pick(cut, 72, 20, 40, 56), 52);
        eq("护栏过紧也仍能取到数据", WindowPicker.pick(cut, 72, 20, 40, 70), 52);

        // ---------- floorOf / trimValley ----------
        eq("floorOf 按比例给", WindowPicker.floorOf(new int[] {100, 2000, 50}, 3), 300);
        eq("floorOf 有绝对地板", WindowPicker.floorOf(new int[] {10, 20, 5}, 3), 60);
        eq("floorOf 空数组给地板", WindowPicker.floorOf(new int[0], 0), 60);

        int[] t1 = WindowPicker.trimValley(cut, 72);
        // 前奏(150) 也低于门限(300) ⇒ 一并当谷裁掉，只留正歌那 8 块 = 4 秒
        eq("trimValley 连前奏一起裁掉", t1[0], 64);
        eq("trimValley 只留正歌 8 块", t1[1], 8);

        int[] t2 = WindowPicker.trimValley(new int[] {500, 600, 700}, 3);
        eq("无谷时起点为 0", t2[0], 0);
        eq("无谷时块数不减", t2[1], 3);

        int[] t3 = WindowPicker.trimValley(new int[] {5, 5, 5}, 3);
        eq("整窗留白则留零块", t3[1], 0);

        // 间奏刚结束：最近 10 秒里前 4 秒是留白、后 6 秒是音乐 ⇒ 裁掉留白，送出去的 6 秒全有效
        int[] gap = new int[20];
        for (int i = 0; i < 8; i++) gap[i] = 5;
        for (int i = 8; i < 20; i++) gap[i] = 1500;
        int[] t4 = WindowPicker.trimValley(gap, 20);
        eq("裁掉窗头留白", t4[0], 8);
        eq("裁后剩 12 块 = 6 秒", t4[1], 12);

        // ---------- blockRms：分块 / 数值 / 尾巴 ----------
        int[] out = new int[8];

        // 16 字节、块 4 字节 ⇒ 4 块；不足一块的尾巴要丢掉
        byte[] sixteen = new byte[16];
        eq("块数按整块取", WindowPicker.blockRms(sixteen, 0, 16, 4, out), 4);
        eq("尾巴不足一块不计", WindowPicker.blockRms(sixteen, 0, 14, 4, out), 3);
        eq("不足一块返回 0", WindowPicker.blockRms(sixteen, 0, 3, 4, out), 0);
        eq("out 装不下就截断", WindowPicker.blockRms(sixteen, 0, 16, 4, new int[2]), 2);

        // [100, -200, 300, -400] → sqrt((1e4+4e4+9e4+16e5)/4) = sqrt(75000) ≈ 273.86 → 273
        byte[] known = le(new short[] {(short) 100, (short) -200, (short) 300, (short) -400});
        int[] one = new int[1];
        WindowPicker.blockRms(known, 0, known.length, known.length, one);
        eq("块 RMS 数值正确", one[0], 273);

        // ---------- rmsOf / peakOf ----------
        near("整段 RMS 正确", WindowPicker.rmsOf(known, 0, known.length), 273.8613d, 0.01d);
        eq("峰值取绝对值最大", WindowPicker.peakOf(known, 0, known.length), 400);
        eq("空数组 RMS 为 0", (int) WindowPicker.rmsOf(new byte[0], 0, 0), 0);
        eq("空数组峰值为 0", WindowPicker.peakOf(new byte[0], 0, 0), 0);
        eq("null RMS 为 0", (int) WindowPicker.rmsOf(null, 0, 0), 0);
        // 从中间开始算，前面的数据不能混进来：只算 [1000, 0] → sqrt((1000²+0)/2) = 707.1
        byte[] two = le(new short[] {(short) 0, (short) 0, (short) 1000, (short) 0});
        eq("rmsOf 按 from 偏移起算", (int) WindowPicker.rmsOf(two, 4, 4), 707);
        eq("peakOf 按 from 偏移起算", WindowPicker.peakOf(two, 4, 4), 1000);

        System.out.println();
        System.out.println("通过 " + pass + " 项，失败 " + fail + " 项");
        if (fail > 0) System.exit(1);
    }

    // ---------- 工具 ----------

    private static byte[] le(short[] samples) {
        byte[] b = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            b[i * 2] = (byte) (samples[i] & 0xFF);
            b[i * 2 + 1] = (byte) ((samples[i] >> 8) & 0xFF);
        }
        return b;
    }

    private static void eq(String name, int got, int want) {
        if (got == want) {
            pass++;
            System.out.println("  ok   " + name);
        } else {
            fail++;
            System.out.println("  FAIL " + name + " —— 期望 " + want + "，实得 " + got);
        }
    }

    private static void near(String name, double got, double want, double tol) {
        if (Math.abs(got - want) <= tol) {
            pass++;
            System.out.println("  ok   " + name);
        } else {
            fail++;
            System.out.println("  FAIL " + name + " —— 期望 " + want + "，实得 " + got);
        }
    }
}
