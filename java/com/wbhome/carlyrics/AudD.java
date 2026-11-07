package com.wbhome.carlyrics;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * AudD 识别客户端 —— ACRCloud 没听出来时的兜底引擎。
 *
 * 为什么值得做兜底：两家曲库覆盖不同，车里放的歌 ACRCloud 认不出、AudD 常常认得出，
 * 反过来也一样。串起来能明显抬高整体识别率。
 *
 * 与 ACRCloud 的关键差异（都实测确认过）：
 *  - AudD 的 timecode 形如 "00:56" 或 "01:02:03"，含义是「你发的片段在歌里的位置」，
 *    作用等同 ACRCloud 的 play_offset_ms → 所以它也能精确校准歌词，不只是报个歌名。
 *  - AudD **不返回歌曲总时长** → 纯 AudD 命中时排期要另外估算
 *    （见 LyricService，用歌词最后一行时间兜底）。
 *  - 请求更简单：POST https://api.audd.io/ ，multipart 带 api_token + file 两个字段。
 *
 * 文档：https://docs.audd.io/
 */
public final class AudD {

    private static final String ENDPOINT = "https://api.audd.io/";

    /**
     * 没填 token 时用官方文档里的示例 token。
     * 能出结果，但额度极低（官方只保证很少的次数），仅作「聊胜于无」的保险。
     * 想要稳定兜底，去 dashboard.audd.io 注册拿自己的 token 填进设置页。
     */
    private static final String FALLBACK_TOKEN = "test";

    private AudD() { }

    public static String effectiveToken(String token) {
        if (token == null || token.trim().isEmpty()) return FALLBACK_TOKEN;
        return token.trim();
    }

    public static AcrCloud.Result identify(byte[] wav, String token) {
        AcrCloud.Result r = new AcrCloud.Result();
        String tk = effectiveToken(token);
        HttpURLConnection c = null;
        try {
            String B = "----CarLyricsAudd" + Long.toHexString(System.nanoTime());
            ByteArrayOutputStream body = new ByteArrayOutputStream(wav.length + 1024);

            field(body, B, "api_token", tk);

            body.write(("--" + B + "\r\n").getBytes("UTF-8"));
            body.write(("Content-Disposition: form-data; name=\"file\"; filename=\"sample.wav\"\r\n")
                    .getBytes("UTF-8"));
            body.write("Content-Type: audio/wav\r\n\r\n".getBytes("UTF-8"));
            body.write(wav);
            body.write("\r\n".getBytes("UTF-8"));
            body.write(("--" + B + "--\r\n").getBytes("UTF-8"));

            c = (HttpURLConnection) new URL(ENDPOINT).openConnection();
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setConnectTimeout(8000);
            c.setReadTimeout(20000);
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + B);
            c.setRequestProperty("User-Agent", Net.UA);
            c.setFixedLengthStreamingMode(body.size());
            OutputStream os = c.getOutputStream();
            body.writeTo(os);
            os.flush();
            os.close();

            int http = c.getResponseCode();
            InputStream in = (http >= 200 && http < 300) ? c.getInputStream() : c.getErrorStream();
            if (in == null) {
                r.message = "AudD 网络错误 HTTP " + http;
                return r;
            }
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            String resp = new String(bo.toByteArray(), "UTF-8");

            JSONObject j = new JSONObject(resp);

            if (!"success".equals(j.optString("status", ""))) {
                JSONObject err = j.optJSONObject("error");
                int ec = err == null ? -1 : err.optInt("error_code", -1);
                String em = err == null ? "" : err.optString("error_message", "");
                r.code = ec;
                r.message = describe(ec, em);
                return r;
            }

            JSONObject res = j.optJSONObject("result");
            if (res == null) {
                r.code = 1001;
                r.message = "AudD 也没听出来";
                return r;
            }

            TrackInfo t = new TrackInfo();
            t.engine = "AudD";
            t.title = res.optString("title", "");
            t.artist = res.optString("artist", "");
            t.album = res.optString("album", "");
            t.playOffsetMs = parseTimecode(res.optString("timecode", ""));
            t.durationMs = 0L;                      // AudD 不给总时长
            t.score = 0d;                           // 也不给匹配度
            t.acrid = "audd:" + t.artist + "|" + t.title;
            if (t.title.isEmpty() && t.artist.isEmpty()) {
                r.code = 1001;
                r.message = "AudD 返回了空结果";
                return r;
            }

            r.ok = true;
            r.track = t;
            r.message = "识别成功（AudD）";
            return r;
        } catch (Exception e) {
            r.message = "AudD " + e.getClass().getSimpleName() + ": " + e.getMessage();
            return r;
        } finally {
            if (c != null) try { c.disconnect(); } catch (Exception ignore) { }
        }
    }

    /**
     * "00:56" / "01:02:03" / "56" / "00:56.5" → 毫秒。
     * 从右往左按 60 进制累加。末段可能带小数秒（"56.5" = 56 秒 500 毫秒），
     * 小数部分要保留成毫秒 —— 直接 Math.floor 会把半个秒吞掉。
     * 解析不了就返回 0（当作「不知道位置」），绝不抛异常。
     */
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

    private static void field(ByteArrayOutputStream b, String boundary, String k, String v)
            throws Exception {
        b.write(("--" + boundary + "\r\n").getBytes("UTF-8"));
        b.write(("Content-Disposition: form-data; name=\"" + k + "\"\r\n\r\n").getBytes("UTF-8"));
        b.write(v.getBytes("UTF-8"));
        b.write("\r\n".getBytes("UTF-8"));
    }

    private static String describe(int code, String msg) {
        String hint;
        switch (code) {
            case 900: hint = "AudD token 无效，检查设置页"; break;
            case 901: hint = "AudD 免费额度已用完（填个自己的 token 可提高额度）"; break;
            case 600: hint = "AudD 音频地址不对"; break;
            case 700: hint = "AudD 没收到音频文件"; break;
            case 500: hint = "AudD 无法解析这个音频"; break;
            case 400: hint = "AudD 音频文件过大"; break;
            case 300: hint = "AudD 说音频太短/指纹提取失败"; break;
            default:  hint = "AudD 返回错误"; break;
        }
        return hint + "（code=" + code + (msg.isEmpty() ? "" : " " + msg) + "）";
    }
}
