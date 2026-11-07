import java.util.Arrays;

/**
 * 把 AudioBuffer 的环形缓冲取数逻辑单独拎出来验证 —— 这段位运算最容易写错。
 *
 * 注意数据生成方式：如果只是「每字节 = 序号 & 0xFF」，周期是 256，
 * 那么偏移错 256 的整数倍会得到完全相同的数组，测不出来。
 * 所以这里用 pos*7 + (pos>>8) 生成，破坏这种周期性。
 */
public class R {

    static final int BYTES_PER_SEC = 32000;      // 16000Hz * 2B
    static final int KEEP_SEC = 30;
    static final byte[] RING = new byte[BYTES_PER_SEC * KEEP_SEC];

    static final Object LOCK = new Object();
    static int sWritePos = 0;
    static long sTotalWritten = 0L;

    static byte val(long pos) {
        return (byte) ((pos * 7 + (pos >> 8)) & 0xFF);
    }

    static byte[] gen(long from, int len) {
        byte[] b = new byte[len];
        for (int i = 0; i < len; i++) b[i] = val(from + i);
        return b;
    }

    /** 模拟录音线程写入（按 1000 字节这种不规则块喂，避免总长总是 256 的倍数） */
    static void feed(byte[] data) {
        int off = 0;
        while (off < data.length) {
            int n = Math.min(1000, data.length - off);
            synchronized (LOCK) {
                for (int i = 0; i < n; i++) {
                    RING[sWritePos] = data[off + i];
                    sWritePos++;
                    if (sWritePos >= RING.length) sWritePos = 0;
                }
                sTotalWritten += n;
            }
            off += n;
        }
    }

    /** 与 AudioBuffer.take() 完全一致的取数逻辑 */
    static byte[] take(int seconds) {
        int want = seconds * BYTES_PER_SEC;
        if (want > RING.length) want = RING.length;
        want &= ~1;

        byte[] out = new byte[want];
        int got;
        synchronized (LOCK) {
            got = (int) Math.min(sTotalWritten, (long) want);
            got &= ~1;
            if (got <= 0) return new byte[0];
            int start = sWritePos - got;
            while (start < 0) start += RING.length;
            int first = Math.min(got, RING.length - start);
            System.arraycopy(RING, start, out, 0, first);
            if (got > first) System.arraycopy(RING, 0, out, first, got - first);
        }
        return (got == want) ? out : Arrays.copyOf(out, got);
    }

    static int fails = 0;

    static void check(String name, byte[] got, long expectStart) {
        if (got.length == 0) { fails++; System.out.println("FAIL " + name + ": 拿到空数据"); return; }
        int bad = -1;
        for (int i = 0; i < got.length; i++) {
            if (got[i] != val(expectStart + i)) { bad = i; break; }
        }
        if (bad >= 0) fails++;
        System.out.println((bad < 0 ? "OK   " : "FAIL ") + name
                + "  长度=" + got.length
                + (bad < 0 ? "" : "  第 " + bad + " 字节对不上（说明起点偏了）"));
    }

    public static void main(String[] a) {
        long seq = 0;

        // 1) 缓冲刚启动，只喂 3 秒，却要 6 秒 → 只拿得到 3 秒，且是最新的
        feed(gen(seq, 3 * BYTES_PER_SEC)); seq += 3L * BYTES_PER_SEC;
        byte[] r1 = take(6);
        check("缓冲不足时返回已有的", r1, seq - r1.length);

        // 2) 喂到 10 秒，要 6 秒 → 精确返回最近 6 秒
        feed(gen(seq, 7 * BYTES_PER_SEC)); seq += 7L * BYTES_PER_SEC;
        byte[] r2 = take(6);
        if (r2.length != 6 * BYTES_PER_SEC) {
            fails++; System.out.println("FAIL 长度不对 " + r2.length);
        } else {
            check("够长时精确返回最近 6 秒", r2, seq - 6L * BYTES_PER_SEC);
        }

        // 3) 喂到超过容量（触发回绕）→ 仍须取到正确的最近 6 秒
        feed(gen(seq, 30 * BYTES_PER_SEC)); seq += 30L * BYTES_PER_SEC;
        byte[] r3 = take(6);
        check("回绕后仍取到正确数据", r3, seq - 6L * BYTES_PER_SEC);

        // 4) 取满整个容量
        byte[] r4 = take(30);
        check("取满整个容量", r4, seq - 30L * BYTES_PER_SEC);

        // 5) 请求超过容量 → 夹到容量，不越界
        byte[] r5 = take(999);
        if (r5.length != RING.length) {
            fails++; System.out.println("FAIL 超容量未夹紧: " + r5.length);
        } else {
            check("请求超容量时夹到 KEEP_SEC", r5, seq - RING.length);
        }

        // 6) 奇数秒数 → 长度必须偶数对齐（16bit 采样不能劈开半个样本）
        feed(gen(seq, BYTES_PER_SEC)); seq += BYTES_PER_SEC;
        byte[] r6 = take(3);
        if (r6.length % 2 != 0) { fails++; System.out.println("FAIL 奇数长度 " + r6.length); }
        else System.out.println("OK   长度偶数对齐  " + r6.length);

        // 7) 连续两次取同一段结果一致
        byte[] x = take(5), y = take(5);
        boolean same = Arrays.equals(x, y);
        if (!same) fails++;
        System.out.println((same ? "OK   " : "FAIL ") + "连续两次取同一段结果一致");

        // 8) 边界：一个字节都没写时取数
        sWritePos = 0; sTotalWritten = 0L;
        byte[] r8 = take(6);
        boolean emptyOk = r8.length == 0;
        if (!emptyOk) fails++;
        System.out.println((emptyOk ? "OK   " : "FAIL ") + "缓冲为空时返回空数组，不抛异常");

        System.out.println(fails == 0 ? "\n全部通过" : "\n有 " + fails + " 个失败");
    }
}
