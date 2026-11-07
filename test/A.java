import java.util.Locale;

/**
 * 歌手名匹配回归测试（LyricsFetcher.artistMatch 的纯逻辑副本）。
 *
 * 为什么这个必须测：它决定了「要不要采纳一份歌词」。
 * 放宽了 → 同名不同曲会串词（《上班族狂想曲》在国内平台有 28 个版本，歌词各不相同，
 *          抓错版本用户会看到一首完全无关的词在滚，比没歌词更糟）；
 * 太严了 → 明明有歌词却拿不到（各平台歌手写法差得远：「周杰伦」/「周杰伦/Jay Chou」）。
 *
 * 跑法：  java test/A.java
 */
public class A {

    // ---- 被测逻辑（与 LyricsFetcher 一致） ----

    static String norm(String s) {
        if (s == null) return "";
        s = s.toLowerCase(Locale.ROOT);
        s = s.replaceAll("[\\(（\\[【][^\\)）\\]】]*[\\)）\\]】]", "");
        s = s.replaceAll("[\\s\u3000]+", "");
        s = s.replace("'", "").replace("\u2019", "");
        return s.trim();
    }

    static String[] splitArtists(String s) {
        if (s == null) return new String[0];
        return s.split("[/、,，&;；]|\\bfeat\\.?|\\bft\\.?", -1);
    }

    static boolean artistMatch(String want, String got) {
        String a = norm(want);
        String b = norm(got);
        if (a.isEmpty() || b.isEmpty()) return false;
        if (a.equals(b) || a.contains(b) || b.contains(a)) return true;
        for (String x : splitArtists(b)) {
            String n = norm(x);
            if (!n.isEmpty() && (a.contains(n) || n.contains(a))) return true;
        }
        for (String x : splitArtists(a)) {
            String n = norm(x);
            if (!n.isEmpty() && b.contains(n)) return true;
        }
        return false;
    }

    // ---- 脚手架 ----
    static int pass = 0, fail = 0;

    static void yes(String name, String want, String got) {
        check(name, true, artistMatch(want, got));
    }

    static void no(String name, String want, String got) {
        check(name, false, artistMatch(want, got));
    }

    static void check(String name, boolean expect, boolean actual) {
        if (expect == actual) {
            pass++;
        } else {
            fail++;
            System.out.println("  FAIL " + name + "  期望 " + expect + " 实际 " + actual);
        }
    }

    public static void main(String[] a) {
        System.out.println("=== 必须匹配上（写法差异）===");
        yes("完全相同", "周杰伦", "周杰伦");
        yes("合唱写法", "王野", "追风筝的诺米/王野");
        yes("英文后缀", "周杰伦", "周杰伦/Jay Chou");
        yes("带 DJ 括注", "媛小小", "媛小小(DJ版)");
        yes("带 Live 括注", "陈奕迅", "陈奕迅（Live）");
        yes("括注在目标侧", "牛油果(专属版)", "牛油果");
        yes("空格差异", "Tears For Fears", "TearsForFears");
        yes("feat 拆分", "张三", "李四 feat. 张三");
        yes("顺序颠倒", "Jay Chou 周杰伦", "周杰伦");
        yes("大小写", "TAYLOR SWIFT", "taylor swift");

        System.out.println("\n=== 必须拒绝（同名不同曲 / 不同人）===");
        // ↓ 这就是老大哥那首歌的实际情况
        no("阮天大 vs coco这个李文（QQ第一条）", "阮天大", "coco-木子文/说唱者联盟");
        no("阮天大 vs 媛小小（网易云第一条）", "阮天大", "媛小小");
        no("阮天大 vs 牛油果", "阮天大", "牛油果");
        no("阮天大 vs 六仔哥", "阮天大", "六仔哥");
        no("完全不同的人", "周杰伦", "林俊杰");
        no("歌手为空", "周杰伦", "");
        no("目标为空", "", "周杰伦");

        System.out.println("\n=== 归一化 ===");
        check("括号剥离", true, norm("牛油果(专属版)").equals("牛油果"));
        check("中文括号剥离", true, norm("陈奕迅（Live）").equals("陈奕迅"));
        check("方括号剥离", true, norm("A[B]C").equals("ac"));
        check("去空白", true, norm("张 三 丰").equals("张三丰"));
        // 注意顺序：先去掉所有空白，再去撇号 —— 所以是 gunsnroses 不是 gunsn roses
        check("撇号", true, norm("Guns N' Roses").equals("gunsnroses"));
        check("撇号无关写法一致", true,
                norm("Guns N' Roses").equals(norm("Guns N Roses")));

        System.out.println("\n=== 拆分 ===");
        check("斜杠", true, splitArtists("A/B").length == 2);
        check("顿号", true, splitArtists("A、B").length == 2);
        check("feat", true, splitArtists("A feat. B").length == 2);
        check("ft", true, splitArtists("A ft B").length == 2);

        System.out.println("\n===== 通过 " + pass + " 项，失败 " + fail + " 项 =====");
        if (fail > 0) System.exit(1);
    }
}
