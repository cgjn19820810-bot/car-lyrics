public class T {
    static long parseTimecode(String s) {
        if (s == null) return 0L;
        s = s.trim();
        if (s.isEmpty() || "null".equalsIgnoreCase(s)) return 0L;
        String[] parts = s.split(":");
        long total = 0L;
        try {
            for (int i = 0; i < parts.length; i++) {
                double v = Double.parseDouble(parts[i].trim());
                long whole = (long) Math.floor(v);
                total = total * 60L + whole;
                if (i == parts.length - 1) {
                    long ms = Math.round((v - whole) * 1000d);
                    return total * 1000L + ms;
                }
            }
        } catch (Exception e) {
            return 0L;
        }
        return total * 1000L;
    }

    static int fails = 0;

    static void t(String in, long want) {
        long got = parseTimecode(in);
        boolean ok = got == want;
        if (!ok) fails++;
        System.out.println((ok ? "OK   " : "FAIL ") + (in == null ? "null" : "\"" + in + "\"")
                + " -> " + got + "  期望 " + want);
    }

    public static void main(String[] a) {
        t("00:56", 56000);        // AudD 实测返回的格式
        t("01:02:03", 3723000);   // 超过一小时的写法
        t("56", 56000);           // 只有秒
        t("03:05", 185000);
        t("00:00", 0);
        t("00:56.5", 56500);      // 带小数秒也要能扛
        t(" 01:30 ", 90000);
        t("", 0);
        t("null", 0);
        t(null, 0);
        t("abc", 0);              // 脏数据不能炸
        t("1:2:3:4", 223384000L); // 非常规的4段，只要求按 60 进制继续累加、不抛异常
        System.out.println(fails == 0 ? "\n全部通过" : "\n有 " + fails + " 个失败");
    }
}
