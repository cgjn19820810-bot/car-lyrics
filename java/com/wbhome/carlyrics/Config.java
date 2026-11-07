package com.wbhome.carlyrics;

import android.content.Context;
import android.content.SharedPreferences;

public final class Config {
    private static final String NAME = "car_lyrics";
    private static volatile Config sInst;
    private final SharedPreferences sp;

    private Config(Context c) {
        sp = c.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
        // 1.3 起默认送 10 秒音频（原来 6 秒太短，车里一吵就听不出来）。
        // 用一次性标记做迁移 —— 否则老装机里已经存着 6，改默认值对他根本不生效；
        // 迁移过一次就不再插手，他之后自己在设置页调的值不会被覆盖。
        if (!sp.getBoolean("mig_rec10", false)) {
            sp.edit().putInt("recsec", 10).putBoolean("mig_rec10", true).apply();
        }
    }

    public static Config get(Context c) {
        if (sInst == null) {
            synchronized (Config.class) {
                if (sInst == null) sInst = new Config(c);
            }
        }
        return sInst;
    }

    public String accessKey() {
        return sp.getString("ak", "").trim();
    }

    public String accessSecret() {
        return sp.getString("as", "").trim();
    }

    /** AudD 兜底引擎的 token，可留空（留空用官方示例 token，额度低） */
    public String auddToken() {
        return sp.getString("audd", "").trim();
    }

    /**
     * ACRCloud 的 Host。
     *
     * **故意不给默认值**。不同区域是不同的域名
     * （identify-cn-north-1.acrcloud.cn / identify-us-west-2.acrcloud.com / identify-eu-west-1.acrcloud.com …），
     * 预填一个国内节点会让「项目建在别的区域」的人一直报「Access Key 不对」，
     * 白白怀疑自己 key 抄错了。必须让他去控制台照着抄。
     * 只做一件事：把用户粘进来的 https:// 前缀和路径尾巴去掉，容错。
     */
    public String host() {
        String h = sp.getString("host", "").trim();
        h = h.replace("https://", "").replace("http://", "");
        int slash = h.indexOf('/');
        if (slash > 0) h = h.substring(0, slash);
        int q = h.indexOf('?');
        if (q > 0) h = h.substring(0, q);
        return h.trim();
    }

    /**
     * 单次识别送出去的音频长度，秒，4~12。
     *
     * 默认从 6 提到 **10**（2026-09-15）：ACRCloud 官方 SDK 的 rec_length 默认就是 10，
     * 片段越长，车里的噪音对指纹匹配的干扰越小。「有的歌识别不出」的第一嫌疑就是送得太短。
     *
     * 上限从 10 放到 **12**（2026-09-15）：官方识别 API 文档明写
     * 「我们建议识别的内容小于 12 秒，且接口只会识别前 12 秒内容，多余的部分将会被丢弃」——
     * 再往上加就是纯浪费上行，所以 12 就是天花板，不做更长。
     *
     * 定位精度不受影响：送出去的片段越长，我们记的「录音中点」也越靠后，
     * 两者是同步往后挪的（见 LyricService 里 baseOffsetMs 的算法）。
     */
    public int recordSec() {
        int v = sp.getInt("recsec", 10);
        if (v < 4) v = 4;
        if (v > 12) v = 12;
        return v;
    }

    /**
     * 最长静默间隔，秒，15~300。
     * 知道歌名和时长后，App 会按「这首歌还剩多久」来安排下一次识别；
     * 这个值封顶，用来在用户手动切歌时也能察觉。调大省额度，调小反应快。
     */
    public int maxSleepSec() {
        int v = sp.getInt("maxsleep", 60);
        if (v < 15) v = 15;
        if (v > 300) v = 300;
        return v;
    }

    /**
     * 还不知道在放什么歌时的试探间隔，秒。
     * 积极识别模式下 12 秒（默认），省电模式 20 秒。
     * 这是「用额度换识别率」最直接的一颗旋钮 —— 认不出时探得越勤，撞上好窗口的机会越多。
     */
    public int searchSec() {
        return aggressive() ? 12 : 20;
    }

    /**
     * 连续多少次「听不出来」之后开始退避。
     * 积极模式 4 次（默认），省电模式 3 次 —— 早一点退避省额度，但也会晚一点才再试，
     * 而车里很多「听不出来」只是那 10 秒正好落在前奏/间奏，多试一次就中了。
     */
    public int missStreakLimit() {
        return aggressive() ? 4 : 3;
    }

    /** 退避后至少隔多久再试，毫秒 */
    public int missBackoffMs() {
        return aggressive() ? 20000 : 30000;
    }

    /**
     * 积极识别：认不出时探得更勤、退避更短。
     * 默认开 —— 这台 App 在车里是「开着不管」的，识别率就是它的可用性；
     * 觉得额度吃紧再关成省电档。
     */
    public boolean aggressive() {
        return sp.getBoolean("aggr", true);
    }

    public void setAggressive(boolean b) {
        sp.edit().putBoolean("aggr", b).apply();
    }

    /** 歌词偏移微调，毫秒，-3000~3000 */
    public int shiftMs() {
        return sp.getInt("shift", 0);
    }

    public boolean landscape() {
        return sp.getBoolean("land", true);
    }

    public void setLandscape(boolean b) {
        sp.edit().putBoolean("land", b).apply();
    }

    /**
     * 预录音缓冲：App 运行期间常驻录音，识别时直接倒带取最近几秒。
     * 切歌后反应快得多，代价是麦克风一直开着（开车插着电，可接受）。
     */
    public boolean preBuffer() {
        return sp.getBoolean("prebuf", true);
    }

    public void setPreBuffer(boolean b) {
        sp.edit().putBoolean("prebuf", b).apply();
    }

    /** ACRCloud 三项（Host / Access Key / Access Secret）都填全了才算「能跑」 */
    public boolean hasKey() {
        return !accessKey().isEmpty() && !accessSecret().isEmpty() && !host().isEmpty();
    }

    /** 还差哪一项 —— 直接告诉用户缺什么，别只丢一句「没填密钥」 */
    public String missingHint() {
        if (host().isEmpty()) return "还差 Host：去 ACRCloud 控制台复制项目页上那一行";
        if (accessKey().isEmpty()) return "还差 Access Key：去 ACRCloud 控制台复制";
        if (accessSecret().isEmpty()) return "还差 Access Secret：去 ACRCloud 控制台复制";
        return "";
    }

    /** AudD 是否填了自己的 token（没填也会用官方示例 token 兜底，只是额度低） */
    public boolean hasAudDToken() {
        return !auddToken().isEmpty();
    }

    public void save(String ak, String as, String host, String audd,
                     int recSec, int maxSleep, int shift) {
        sp.edit()
                .putString("ak", ak == null ? "" : ak.trim())
                .putString("as", as == null ? "" : as.trim())
                .putString("host", host == null ? "" : host.trim())
                .putString("audd", audd == null ? "" : audd.trim())
                .putInt("recsec", recSec)
                .putInt("maxsleep", maxSleep)
                .putInt("shift", shift)
                .apply();
    }
}
