package com.wbhome.carlyrics;

/**
 * 纯逻辑：从「每块音量」序列里挑出最有音乐感的一段连续窗口。
 *
 * 为什么需要它 —— 原来固定取「最近 N 秒」，可车里最常见的三种时刻恰恰最坑：
 *  1. 车机刚切歌：最近几秒里混着上一首的尾奏 + 切歌空白；
 *  2. 前奏渐入 / 纯留白：整段能量很低，有效音乐秒数根本填不满窗口；
 *  3. 间奏、念白：音乐成分稀薄。
 * ACRCloud 官方明写「接口只识别前 12 秒，多余部分丢弃」，所以窗口里的静音是**纯浪费**：
 * 送 10 秒但只有 4 秒有音乐，等于只送 4 秒去匹配，识别率自然掉。
 *
 * 于是很自然地想「在回看余量内挑平均音量最高的连续窗口」—— 音量高基本等价于「音乐正密」。
 * 但这条策略最后被推翻了，理由见下；本类现在真正在线上跑的是 {@link #trimValley}。
 *
 * ★ 「只挑最响」有个必须先堵住的坑，而且是会直接坑到用户体验的那种：
 *   切歌的瞬间，缓冲里**上一首往往比新歌的前奏响得多**。只按音量挑，会精准地挑中上一首，
 *   于是屏幕上滚的是**上一首的歌词** —— 这比「识别不到」糟得多。
 *   护栏是 {@link #lastValley}：歌与歌之间必然有一段留白/谷底，
 *   窗口起点不许跨过最近的那个谷底往回伸。
 *
 * ★★ 可一旦加上这道护栏，「往回挑最响」的收益就基本被护栏自己挡光了：
 *   收益场景（回看找到更响的一段）几乎全在护栏另一侧，够不着；风险却常驻。
 *   所以最终收敛成一条更简单也更管用的策略：
 *     **固定取最近 N 秒 → 用 {@link #trimValley} 裁掉尾部的留白 → 按裁后的有效秒数决定发不发**
 *   （太短就宁可不发，见 {@link AudioRecorder#MIN_SEND_SEC}）。
 *   {@link #pick} 只在「裁完剩下的有效段仍比窗口长」时被调用，用来在段内挑更响的等长子窗 ——
 *   段内已经没有谷底，这一步跨不到上一首去，是安全的。
 *
 * 真音乐实测（test/L.java，同一份代码）：窗头 4 秒留白的情形，送检有效率 60% → 100%；
 * 窗尾 6 秒留白（切歌后新歌未起）的情形，旧策略送 10 秒里只有 4 秒是音乐、且很可能滚成
 * 上一首的歌词，新策略直接判「本轮没有有效音乐」不发。
 *
 * 这个类不碰任何 Android 类，可以像 test/W.java 那样在 PC 上直接跑回归
 * （test/WP.java 跑算法边界，test/L.java 用真实音乐跑场景对照）。
 */
public final class WindowPicker {

    private WindowPicker() { }

    /**
     * @param rms  每块音量（RMS）；下标 0 最旧，n-1 最新
     * @param n    rms 里的有效长度
     * @param win  窗口块数（要挑多长）
     * @param back 窗口末端最多允许「回看」几块 —— 这道约束保证挑中的仍是**当下还在放**的声音
     * @return 窗口起始块下标；数据不够返回 -1
     *
     * 一样响的时候选更靠后的（更贴近现在），所以比较用 >=。
     */
    public static int pick(int[] rms, int n, int win, int back) {
        return pick(rms, n, win, back, 0);
    }

    /**
     * @param minStart 起点下界（含）—— 由 {@link #lastValley} 算出，用来挡住「跨过谷底捞到上一首」
     *
     * 若 minStart 高到放不下一个完整窗口，就丢掉这道护栏、退回「取最近一段」——
     * 认不出总比认错歌强，但也不能因为护栏而彻底取不到数据。
     */
    public static int pick(int[] rms, int n, int win, int back, int minStart) {
        if (rms == null || win <= 0 || n < win) return -1;

        int lastStart = n - win;                       // 窗口末端正好压在最新数据上
        int firstStart = lastStart - (back < 0 ? 0 : back);
        if (firstStart < 0) firstStart = 0;

        int lo = minStart > firstStart ? minStart : firstStart;
        if (lo > lastStart) lo = lastStart;            // 护栏太紧 ⇒ 退回最近一段

        // 前缀和：窗口内块音量之和，O(1) 取任意区间，避免 O(n*win)
        long[] pre = new long[n + 1];
        for (int i = 0; i < n; i++) {
            int v = rms[i];
            pre[i + 1] = pre[i] + (v > 0 ? (long) v : 0L);
        }

        int best = -1;
        long bestSum = -1L;
        for (int s = lo; s <= lastStart; s++) {
            long sum = pre[s + win] - pre[s];
            if (sum >= bestSum) {                      // >= ⇒ 同音量时取更靠后的
                bestSum = sum;
                best = s;
            }
        }
        return best;
    }

    /**
     * 从最新一块往回扫，找**第一个**「明显比周围安静」的块 —— 也就是最近的一次留白/谷底。
     *
     * 物理依据：歌与歌之间、乐句与乐句之间一定有能量塌陷；而「换歌」这件事在能量上
     * 的表征就是这样一个谷底。谷底的**前面**属于上一首，不该再往里捞。
     *
     * @param floor 低于这个 RMS 就算谷底（调用方按全段最大块音量比例给，别给绝对值 ——
     *              车机音量大小会让整段电平整体平移，绝对阈值必然两头出错）
     * @return 谷底块下标；一路都没谷底返回 -1（表示整段连贯，不必加护栏）
     */
    public static int lastValley(int[] rms, int n, int floor) {
        if (rms == null || n <= 0) return -1;
        for (int i = n - 1; i >= 0; i--) {
            if (rms[i] < floor) return i;
        }
        return -1;
    }

    /** 整段里的最大块音量（用来按比例定谷底门限） */
    public static int maxOf(int[] rms, int n) {
        if (rms == null || n <= 0) return 0;
        int m = 0;
        int lim = n < rms.length ? n : rms.length;
        for (int i = 0; i < lim; i++) if (rms[i] > m) m = rms[i];
        return m;
    }

    /** 谷底门限：窗口内最大块音量的 15%，再垫一个很低的绝对地板 */
    public static final int FLOOR_PCT = 15;
    public static final int FLOOR_MIN = 60;

    /**
     * 谷底门限按**窗口内**最大块音量的比例给，不给绝对值：
     * 车机音量大小会把整段电平整体平移，绝对阈值必然两头出错。
     * 地板 FLOOR_MIN 兜住「整段都极轻」时比例门限也几乎为零、以致把正常音乐当谷的情况。
     */
    public static int floorOf(int[] rms, int n) {
        int f = (int) (maxOf(rms, n) * (FLOOR_PCT / 100d));
        return f < FLOOR_MIN ? FLOOR_MIN : f;
    }

    /**
     * 裁掉窗口尾部的留白 —— 返回「不跨过最近谷底的那段连续音乐」的 {起点, 块数}。
     *
     * 这是「不认错歌」与「不送静音」两件事的交汇点：
     *  - 谷底之前属于上一首 / 上一乐句，取它就可能把歌词滚成别人的；
     *  - 谷底之后的留白送出去只是占掉 ACRCloud 那 12 秒的额度，一个指纹点都不贡献。
     * 整窗都是留白时返回 {n, 0}，由上层判成「本轮没有有效音乐」，不发也不算失败。
     *
     * 线上（{@link AudioBuffer#takeWindow}）与回归测试（test/L.java）都走这一个函数，
     * 免得测试里另写一份、测的却不是真跑的那套。
     */
    public static int[] trimValley(int[] rms, int n) {
        int from = lastValley(rms, n, floorOf(rms, n));
        from = from < 0 ? 0 : from + 1;
        return new int[] { from, n - from };
    }

    /**
     * 把 pcm[from, from+len) 按 blockBytes 分块算 RMS，写进 out（不足一块的尾巴丢掉）。
     *
     * @return 实际写出的块数
     */
    public static int blockRms(byte[] pcm, int from, int len, int blockBytes, int[] out) {
        if (pcm == null || out == null || blockBytes < 2 || len < blockBytes) return 0;
        int nb = len / blockBytes;
        if (nb > out.length) nb = out.length;

        for (int b = 0; b < nb; b++) {
            int s = from + b * blockBytes;
            int e = s + blockBytes;
            long sum = 0L;
            int cnt = 0;
            for (int i = s; i + 1 < e; i += 2) {
                int v = (pcm[i] & 0xFF) | (pcm[i + 1] << 8);
                sum += (long) v * (long) v;
                cnt++;
            }
            out[b] = cnt > 0 ? (int) Math.sqrt((double) sum / (double) cnt) : 0;
        }
        return nb;
    }

    /** 整段 RMS（用于静音判定）。必须按**实际取出的那段**算，不能拿全局平滑值顶替。 */
    public static double rmsOf(byte[] pcm, int from, int len) {
        if (pcm == null || len <= 0) return 0d;
        long sum = 0L;
        int cnt = 0;
        int e = from + len;
        for (int i = from; i + 1 < e && i + 1 < pcm.length; i += 2) {
            int v = (pcm[i] & 0xFF) | (pcm[i + 1] << 8);
            sum += (long) v * (long) v;
            cnt++;
        }
        return cnt > 0 ? Math.sqrt((double) sum / (double) cnt) : 0d;
    }

    /** 整段峰值（绝对值最大）。用于判断「信号小到有效位深不够」需不需要抬增益。 */
    public static int peakOf(byte[] pcm, int from, int len) {
        if (pcm == null || len <= 0) return 0;
        int peak = 0;
        int e = from + len;
        for (int i = from; i + 1 < e && i + 1 < pcm.length; i += 2) {
            int v = (pcm[i] & 0xFF) | (pcm[i + 1] << 8);
            int a = v < 0 ? -v : v;
            if (a > peak) peak = a;
        }
        return peak;
    }
}
