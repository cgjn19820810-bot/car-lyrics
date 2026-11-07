package com.wbhome.carlyrics;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Locale;

/**
 * 歌词获取：三个源串行找，先找到带时间轴的就不往下走。
 *
 *   1. LRCLIB  —— 接口正好吃 ACRCloud 给的 歌手/歌名/专辑/时长，时间轴精度到 10ms
 *   2. 网易云   —— 中文歌兜底
 *   3. QQ音乐   —— 中文歌/网络热歌覆盖最好（2026-09-15 实测：`上班族狂想曲` 三家里只有 QQ 有词）
 *
 * **每个源都必须做「歌手名匹配」，这是 v1.4 最重要的修复。**
 * 起因：老大哥说《上班族狂想曲》没歌词。查下去发现这首歌在国内平台有 **28 个不同版本**
 * （coco这个李文 / 媛小小 / 牛油果 / 六仔哥 / 追风筝的诺米 …），歌词**每版都不一样**，
 * 而 ACRCloud 认出来的是「阮天大」那版。旧代码「搜索结果取第一条」，
 * 网易云给的是媛小小版、QQ 给的是 coco这个李文版 —— **歌词张冠李戴，比没歌词更糟**：
 * 用户会看到一首完全不相关的歌词在滚，以为 App 坏了。
 *
 * 所以现在的规矩是：**歌手名对不上，宁可不给歌词，也不给错的**。
 */
public final class LyricsFetcher {

    private static final int TIMEOUT = 12000;
    private static final String QQ_REF = "https://y.qq.com/portal/player.html";
    private static final String NE_REF = "https://music.163.com/";

    /** 每个源最多看几条候选 */
    private static final int MAX_CAND = 10;

    public static Lrc fetch(Context ctx, TrackInfo t) {
        if (t == null) return null;

        String key = cacheKey(t);
        Lrc cached = readCache(ctx, key);
        if (cached != null && cached.usable()) return cached;

        Lrc best = fromLrclib(t);
        if (!good(best)) best = pick(best, fromNetEase(t));
        if (!good(best)) best = pick(best, fromQQ(t));

        if (best != null && best.usable()) writeCache(ctx, key, best);
        return best;
    }

    /** 已经拿到带时间轴的歌词，后面几个源就不用问了 */
    private static boolean good(Lrc l) {
        return l != null && l.synced && l.usable();
    }

    /** a 优先；a 没有时间轴而 b 有，则换 b */
    private static Lrc pick(Lrc a, Lrc b) {
        if (a == null) return b;
        if (b == null) return a;
        if (b.synced && !a.synced) return b;
        return a;
    }

    /**
     * 缓存键用「歌手 + 歌名」而不是 acrid —— 同一首歌被 ACRCloud 或 AudD 认出来，
     * 命中的是同一份缓存，换引擎不会白拉一次。
     */
    private static String cacheKey(TrackInfo t) {
        String a = t.artist == null ? "" : t.artist.trim().toLowerCase(Locale.ROOT);
        String n = t.title == null ? "" : t.title.trim().toLowerCase(Locale.ROOT);
        if (!a.isEmpty() || !n.isEmpty()) return "s|" + a + "|" + n;
        return t.acrid;
    }

    // ------------------------------------------------------------ 歌手名匹配

    /**
     * 归一化：小写、去所有空白、去掉 (Live) (DJ版) [伴奏] 这类括注。
     * 这些括注恰恰是同名版本之间最大的差异来源，带着它比对反而容易误伤。
     */
    private static String norm(String s) {
        if (s == null) return "";
        s = s.toLowerCase(Locale.ROOT);
        s = s.replaceAll("[\\(（\\[【][^\\)）\\]】]*[\\)）\\]】]", "");
        s = s.replaceAll("[\\s\u3000]+", "");
        s = s.replace("'", "").replace("\u2019", "");
        return s.trim();
    }

    /** 多人合唱：「A/B」「A、B」「A feat. B」都要能拆开单独比 */
    private static String[] splitArtists(String s) {
        if (s == null) return new String[0];
        return s.split("[/、,，&;；]|\\bfeat\\.?|\\bft\\.?", -1);
    }

    /**
     * 歌手名是否算同一人。
     * 宽松到「互相包含」就算 —— 因为各平台的写法差得很远
     * （「周杰伦」vs「周杰伦/Jay Chou」vs「Jay Chou 周杰伦」），
     * 但绝不放宽到「都不匹配也认」——那正是串词的来源。
     */
    private static boolean artistMatch(String want, String got) {
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

    /** ACRCloud 偶尔不给歌手名（纯音乐等）—— 那种情况只能只看歌名 */
    private static boolean artistUnknown(String s) {
        return norm(s).isEmpty();
    }

    /** 结果里的歌手字段拼成一个串，方便统一匹配 */
    private static String artistsOf(JSONArray arr) {
        if (arr == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String n = o.optString("name", "");
            if (n.isEmpty()) n = o.optString("singer_name", "");
            if (n.isEmpty()) continue;
            if (sb.length() > 0) sb.append('/');
            sb.append(n);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------ 源 1：LRCLIB

    private static Lrc fromLrclib(TrackInfo t) {
        // 精确接口：直接把 artist / title / album / duration 一起喂进去，命中即为精确匹配
        String body = Net.get("https://lrclib.net/api/get?artist_name=" + Net.enc(t.artist)
                + "&track_name=" + Net.enc(t.title)
                + (t.album != null && !t.album.isEmpty() ? "&album_name=" + Net.enc(t.album) : "")
                + (t.durationMs > 5000 ? "&duration=" + (t.durationMs / 1000) : ""),
                null, Net.UA_BROWSER, TIMEOUT);
        Lrc exact = pickLrclib(body);
        if (exact != null && exact.synced) return exact;

        // 模糊搜索：LRCLIB 里同样堆着一大票同名翻唱，必须按歌手名筛
        String body2 = Net.get("https://lrclib.net/api/search?q="
                + Net.enc(t.artist + " " + t.title), null, Net.UA_BROWSER, TIMEOUT);
        Lrc slim = null;
        if (body2 != null && body2.trim().startsWith("[")) {
            try {
                JSONArray arr = new JSONArray(body2);
                for (int i = 0; i < Math.min(arr.length(), MAX_CAND); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    if (!artistUnknown(t.artist)
                            && !artistMatch(t.artist, o.optString("artistName", ""))) continue;
                    Lrc one = lrclibOne(o);
                    if (one == null) continue;
                    if (one.synced) return one;
                    if (slim == null) slim = one;
                }
            } catch (Exception ignore) { }
        }
        return slim != null ? slim : exact;
    }

    private static Lrc pickLrclib(String body) {
        if (body == null) return null;
        try {
            if (!body.contains("syncedLyrics")) return null;
            return lrclibOne(new JSONObject(body));
        } catch (Exception ignore) { }
        return null;
    }

    private static Lrc lrclibOne(JSONObject o) {
        try {
            String s = o.isNull("syncedLyrics") ? "" : o.optString("syncedLyrics", "");
            if (!s.isEmpty()) return Lrc.parse(s, "LRCLIB");
            String p = o.optString("plainLyrics", "");
            if (!p.isEmpty()) return plain(p, "LRCLIB");
        } catch (Exception ignore) { }
        return null;
    }

    // ------------------------------------------------------------ 源 2：网易云

    private static Lrc fromNetEase(TrackInfo t) {
        try {
            String kw = t.title + (artistUnknown(t.artist) ? "" : " " + t.artist);
            String s = Net.get("https://music.163.com/api/search/get/web?csrf_token=&s=" + Net.enc(kw)
                    + "&type=1&offset=0&total=true&limit=" + MAX_CAND, NE_REF, Net.UA_BROWSER, 10000);
            if (s == null) return null;
            JSONObject j = new JSONObject(s);
            JSONObject res = j.optJSONObject("result");
            JSONArray songs = res == null ? null : res.optJSONArray("songs");
            if (songs == null) return null;

            Lrc slim = null;
            for (int i = 0; i < Math.min(songs.length(), MAX_CAND); i++) {
                JSONObject so = songs.getJSONObject(i);
                if (!artistUnknown(t.artist)
                        && !artistMatch(t.artist, artistsOf(so.optJSONArray("artists")))) continue;
                long id = so.optLong("id", 0L);
                if (id == 0L) continue;

                String lyric = Net.get("https://music.163.com/api/song/lyric?id=" + id
                        + "&lv=1&kv=1&tv=-1", NE_REF, Net.UA_BROWSER, 10000);
                if (lyric == null) continue;
                JSONObject lj = new JSONObject(lyric);
                JSONObject lrc = lj.optJSONObject("lrc");
                if (lrc == null) continue;
                String text = lrc.optString("lyric", "");
                if (text.isEmpty()) continue;

                Lrc one = Lrc.parse(text, "网易云");
                if (!one.usable()) continue;
                if (one.synced) return one;
                if (slim == null) slim = one;
            }
            return slim;
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------ 源 3：QQ音乐

    /**
     * QQ 音乐的歌词接口要带 Referer，且 `retcode != 0` 表示这首拿不到词
     * （实测有的歌返回 -1901，是付费/无授权，歌词字段直接是空的）。
     */
    private static Lrc fromQQ(TrackInfo t) {
        try {
            String kw = t.title + (artistUnknown(t.artist) ? "" : " " + t.artist);
            String s = Net.get("https://c.y.qq.com/soso/fcgi-bin/client_search_cp?p=1&n=" + MAX_CAND
                    + "&w=" + Net.enc(kw) + "&format=json", QQ_REF, Net.UA_BROWSER, 10000);
            if (s == null) return null;
            JSONObject j = new JSONObject(s);
            JSONObject data = j.optJSONObject("data");
            JSONObject song = data == null ? null : data.optJSONObject("song");
            JSONArray list = song == null ? null : song.optJSONArray("list");
            if (list == null) return null;

            Lrc slim = null;
            for (int i = 0; i < Math.min(list.length(), MAX_CAND); i++) {
                JSONObject so = list.getJSONObject(i);
                if (!artistUnknown(t.artist)
                        && !artistMatch(t.artist, artistsOf(so.optJSONArray("singer")))) continue;
                String mid = so.optString("songmid", "");
                if (mid.isEmpty()) continue;

                String body = Net.get("https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid="
                        + Net.enc(mid) + "&format=json&nobase64=1", QQ_REF, Net.UA_BROWSER, 10000);
                if (body == null) continue;
                JSONObject lj = new JSONObject(body);
                if (lj.optInt("retcode", 0) != 0) continue;
                String text = lj.optString("lyric", "");
                if (text.isEmpty()) continue;

                Lrc one = Lrc.parse(text, "QQ音乐");
                if (!one.usable()) continue;
                if (one.synced) return one;
                if (slim == null) slim = one;
            }
            return slim;
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------ 工具

    /** 造一个纯文本歌词（无时间轴，靠播放进度比例估算） */
    private static Lrc plain(String text, String source) {
        Lrc l = new Lrc();
        l.source = source;
        l.plainText = text.trim();
        for (String ln : text.split("\\r?\\n")) {
            String x = ln.trim();
            if (!x.isEmpty()) l.plainLines.add(x);
        }
        return l;
    }

    // ------------------------------------------------------------ 落盘缓存

    /**
     * 文件名用 key 的 MD5。
     * 原来是把非字母数字的字符全删掉当文件名 —— 中文歌名会被整条清空，
     * 所有中文歌挤到同一个缓存文件里互相覆盖。换 MD5 后彻底没这个问题。
     */
    private static File cacheFile(Context ctx, String key) {
        File dir = new File(ctx.getFilesDir(), "lyr");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, md5(key) + ".lrc");
    }

    private static String md5(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return "k" + Integer.toHexString(s.hashCode());
        }
    }

    private static Lrc readCache(Context ctx, String key) {
        if (key == null || key.isEmpty()) return null;
        File f = cacheFile(ctx, key);
        if (!f.exists()) return null;
        try {
            FileInputStream in = new FileInputStream(f);
            byte[] b = new byte[(int) f.length()];
            int off = 0;
            while (off < b.length) {
                int n = in.read(b, off, b.length - off);
                if (n <= 0) break;
                off += n;
            }
            in.close();
            String all = new String(b, 0, off, "UTF-8");
            int nl = all.indexOf('\n');
            if (nl < 0) return null;
            String src = all.substring(0, nl);
            String raw = all.substring(nl + 1);
            Lrc l = Lrc.parse(raw, src);
            return l.usable() ? l : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeCache(Context ctx, String key, Lrc l) {
        if (key == null || key.isEmpty() || l == null) return;
        try {
            StringBuilder sb = new StringBuilder();
            sb.append(l.source).append('\n');
            for (Lrc.Line ln : l.lines) sb.append('[').append(fmt(ln.t)).append(']').append(ln.text).append('\n');
            for (String p : l.plainLines) sb.append(p).append('\n');
            FileOutputStream out = new FileOutputStream(cacheFile(ctx, key));
            out.write(sb.toString().getBytes("UTF-8"));
            out.close();
        } catch (Exception ignore) { }
    }

    private static String fmt(long ms) {
        long mm = ms / 60000;
        long ss = (ms % 60000) / 1000;
        long cs = ms % 1000;
        return String.format("%02d:%02d.%03d", mm, ss, cs);
    }
}
