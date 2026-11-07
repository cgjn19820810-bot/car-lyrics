import java.util.ArrayList;
import java.util.List;

/**
 * 折行算法回归测试（LyricView.wrapRow 的纯逻辑副本）。
 *
 * 为什么单独测这个：竖屏大字号的**全部风险都堆在折行上** ——
 * 折错了会无限循环、会丢字、会把整块撑爆，而这三种在手机上都是「闪退/花屏」级别的事故，
 * 没有真机只能靠这儿挡住。
 *
 * android.graphics.Paint 用不了，用假测量代替：
 *   汉字 1.0×size，ASCII 0.55×size（和真实渲染基本吻合）。
 *
 * 跑法：  java test/W.java
 */
public class W {

    // ---- 假测量 ----
    static float measure(String s, float size) {
        if (s == null) return 0f;
        float w = 0f;
        for (int i = 0; i < s.length(); i++) {
            w += (s.charAt(i) < 128 ? 0.55f : 1.0f) * size;
        }
        return w;
    }

    // ---- 被测逻辑（与 LyricView.wrapRow 一致） ----
    static List<String> wrapRow(String txt, float size, float maxW, int maxWrap, List<String> buf) {
        buf.clear();
        if (txt == null) return buf;
        String s = txt.trim();
        if (s.isEmpty()) return buf;

        if (maxWrap <= 1 || measure(s, size) <= maxW) {
            buf.add(s);
            return buf;
        }

        int start = 0;
        while (start < s.length() && buf.size() < maxWrap) {
            if (buf.size() == maxWrap - 1) {
                buf.add(s.substring(start));
                break;
            }
            int end = start + 1;
            while (end <= s.length() && measure(s.substring(start, end), size) <= maxW) end++;
            int cut = end - 1;
            if (cut <= start) cut = start + 1;
            buf.add(s.substring(start, cut));
            start = cut;
        }
        return buf;
    }

    // ---- 测试脚手架 ----
    static int pass = 0;
    static int fail = 0;

    static void eq(String name, String want, String got) {
        if (want.equals(got)) {
            pass++;
        } else {
            fail++;
            System.out.println("  FAIL " + name + "\n     want=[" + want + "]\n     got =[" + got + "]");
        }
    }

    static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.size(); i++) {
            if (i > 0) sb.append('|');
            sb.append(l.get(i));
        }
        return sb.toString();
    }

    static String rep(String s, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }

    /** 合起来必须和原文一样（折行只允许切分，不许丢字/多字） */
    static void noLoss(String name, String src, List<String> out) {
        StringBuilder sb = new StringBuilder();
        for (String s : out) sb.append(s);
        eq(name + " 不丢字", src.trim(), sb.toString());
    }

    public static void main(String[] a) {
        List<String> buf = new ArrayList<String>();

        // 竖屏实测参数：Magic V5 外屏 1060 宽，字号 min(1060*0.082, 2376*0.052)=86.9，可用宽 1060*0.92=975
        float size = 86.9f;
        float maxW = 975f;
        int wrap = 2;

        System.out.println("竖屏参数: size=" + size + " maxW=" + maxW + " 一行约 " + (int) (maxW / size) + " 个字");

        System.out.println("\n[1] 短行不折");
        eq("短行", "我爱你", join(wrapRow("我爱你", size, maxW, wrap, buf)));

        System.out.println("\n[2] 刚好放得下");
        String fit = rep("字", 11);          // 11*86.9 = 955.9 <= 975
        eq("11字不折", fit, join(wrapRow(fit, size, maxW, wrap, buf)));

        System.out.println("\n[3] 超长行折成两段（第一段必须 <= maxW）");
        String long20 = rep("字", 20);
        List<String> r3 = wrapRow(long20, size, maxW, wrap, buf);
        System.out.println("  20字 -> " + r3.size() + " 段，各段字数: " + r3.get(0).length() + " / " + r3.get(1).length());
        eq("段数", "2", String.valueOf(r3.size()));
        eq("第一段 11 字", "11", String.valueOf(r3.get(0).length()));
        eq("第二段 9 字", "9", String.valueOf(r3.get(1).length()));
        if (measure(r3.get(0), size) > maxW) { fail++; System.out.println("  FAIL 第一段超宽"); } else pass++;
        noLoss("20字", long20, r3);

        System.out.println("\n[4] 横屏 maxWrap=1：不折行（横屏保持缩字号的老行为）");
        List<String> r4 = wrapRow(long20, size, maxW, 1, buf);
        eq("横屏 1 段", "1", String.valueOf(r4.size()));
        eq("横屏整行保留", long20, join(r4));

        System.out.println("\n[5] 极长行（3 段才放得下）：只给 2 段，第二段吃剩下的");
        String long30 = rep("字", 30);
        List<String> r5 = wrapRow(long30, size, maxW, wrap, buf);
        eq("段数", "2", String.valueOf(r5.size()));
        eq("第二段 19 字", "19", String.valueOf(r5.get(1).length()));
        noLoss("30字", long30, r5);

        System.out.println("\n[6] 单个字符都放不下：不能死循环，硬切一个字");
        List<String> r6 = wrapRow("abc", 1000f, 100f, wrap, buf);
        eq("段数", "2", String.valueOf(r6.size()));
        eq("第一段 1 字", "1", String.valueOf(r6.get(0).length()));
        noLoss("abc", "abc", r6);

        System.out.println("\n[7] 空白与空串");
        eq("空串", "", join(wrapRow("", size, maxW, wrap, buf)));
        eq("null", "", join(wrapRow(null, size, maxW, wrap, buf)));
        eq("纯空格", "", join(wrapRow("   ", size, maxW, wrap, buf)));
        eq("首尾空格被 trim", "abc", join(wrapRow("  abc  ", 12f, maxW, wrap, buf)));

        System.out.println("\n[8] 中英混排：英文按 0.55 字宽算，切的字数应当多于纯中文");
        String mixed = "This is a very long lyric line with 中文 and more words here";
        List<String> r8 = wrapRow(mixed, size, maxW, wrap, buf);
        System.out.println("  混排 -> " + r8.size() + " 段: [" + join(r8) + "]");
        eq("段数 2", "2", String.valueOf(r8.size()));
        noLoss("混排", mixed, r8);

        System.out.println("\n[9] 横屏实测参数：2186*0.92=2011 可用宽，字号 123.6 —— 17 字以内不该折");
        float lsize = 123.6f;
        float lmax = 2011f;
        eq("横屏 17 字不折", rep("字", 17), join(wrapRow(rep("字", 17), lsize, lmax, 1, buf)));

        System.out.println("\n===== 通过 " + pass + " 项，失败 " + fail + " 项 =====");
        if (fail > 0) System.exit(1);
    }
}
