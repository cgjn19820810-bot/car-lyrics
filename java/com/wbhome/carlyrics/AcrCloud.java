package com.wbhome.carlyrics;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * ACRCloud 音频识别客户端（识别协议 v1）。
 *
 * 签名串（官方 identification-api 文档）：
 *   string_to_sign = POST \n /v1/identify \n access_key \n audio \n signature_version \n timestamp
 *   signature      = base64(hmac-sha1(access_secret, string_to_sign))
 *
 * 注意 signature_version：现行官方文档与官方 SDK 用 "1"，早期示例用 "1.0"。
 * 这里先按 "1" 发；如果服务端说签名/参数不对，自动换 "1.0" 再试一次，
 * 成功即记住，避免因为这一个字符导致整台机器用不了。
 */
public final class AcrCloud {

    private static final String SIG_V1 = "1";
    private static final String SIG_V10 = "1.0";

    /** 试出哪种 signature_version 可用后记住它 */
    private static volatile String sGoodSig = SIG_V1;

    public static class Result {
        public boolean ok;
        public String message = "";
        public int code = -1;
        public TrackInfo track;
    }

    // 这些返回码说明「请求本身是对的，只是没结果/没额度」，不用换签名重试
    private static boolean isConclusive(int code) {
        return code == 0 || code == 1001 || code == 2004 || code == 3003 || code == 3014;
    }

    public static Result identify(byte[] wav, String ak, String as, String host) {
        Result r = attempt(wav, ak, as, host, sGoodSig);
        if (r.ok || isConclusive(r.code)) return r;

        // 换成另一种 signature_version 再试
        String other = SIG_V1.equals(sGoodSig) ? SIG_V10 : SIG_V1;
        Result r2 = attempt(wav, ak, as, host, other);
        if (r2.ok || isConclusive(r2.code)) {
            sGoodSig = other;
            return r2;
        }
        return r;
    }

    private static Result attempt(byte[] wav, String ak, String as, String host, String sigVer) {
        Result r = new Result();
        if (ak == null || ak.isEmpty() || as == null || as.isEmpty()) {
            r.message = "还没填 ACRCloud 密钥";
            return r;
        }
        HttpURLConnection c = null;
        try {
            long ts = System.currentTimeMillis() / 1000L;
            String sts = "POST\n/v1/identify\n" + ak + "\naudio\n" + sigVer + "\n" + ts;
            String sig = hmacSha1Base64(as, sts);

            String B = "----CarLyrics" + Long.toHexString(System.nanoTime());
            ByteArrayOutputStream body = new ByteArrayOutputStream(wav.length + 2048);
            field(body, B, "access_key", ak);
            field(body, B, "data_type", "audio");
            field(body, B, "signature_version", sigVer);
            field(body, B, "signature", sig);
            field(body, B, "sample_bytes", String.valueOf(wav.length));
            field(body, B, "timestamp", String.valueOf(ts));

            body.write(("--" + B + "\r\n").getBytes("UTF-8"));
            body.write(("Content-Disposition: form-data; name=\"sample\"; filename=\"sample.wav\"\r\n")
                    .getBytes("UTF-8"));
            body.write("Content-Type: audio/wav\r\n\r\n".getBytes("UTF-8"));
            body.write(wav);
            body.write("\r\n".getBytes("UTF-8"));
            body.write(("--" + B + "--\r\n").getBytes("UTF-8"));

            c = (HttpURLConnection) new URL("https://" + host + "/v1/identify").openConnection();
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
                r.message = "网络错误 HTTP " + http;
                return r;
            }
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            String resp = new String(bo.toByteArray(), "UTF-8");

            JSONObject j = new JSONObject(resp);
            JSONObject st = j.optJSONObject("status");
            int sc = st == null ? -1 : st.optInt("code", -1);
            String msg = st == null ? "" : st.optString("msg", "");
            r.code = sc;
            if (sc != 0) {
                r.message = describe(sc, msg);
                return r;
            }

            JSONObject md = j.optJSONObject("metadata");
            JSONArray music = md == null ? null : md.optJSONArray("music");
            if (music == null || music.length() == 0) {
                r.code = 1001;
                r.message = "没听出来";
                return r;
            }

            JSONObject m = music.getJSONObject(0);
            TrackInfo t = new TrackInfo();
            t.acrid = m.optString("acrid", "");
            t.title = m.optString("title", "");
            JSONObject al = m.optJSONObject("album");
            t.album = al == null ? "" : al.optString("name", "");
            JSONArray arts = m.optJSONArray("artists");
            if (arts != null && arts.length() > 0) {
                t.artist = arts.getJSONObject(0).optString("name", "");
            }
            t.durationMs = m.optLong("duration_ms", 0L);
            t.playOffsetMs = m.optLong("play_offset_ms", 0L);
            t.score = m.optDouble("score", 0d);
            if (t.acrid == null || t.acrid.isEmpty()) t.acrid = t.artist + "|" + t.title;

            r.ok = true;
            r.track = t;
            r.message = "识别成功";
            return r;
        } catch (Exception e) {
            r.message = e.getClass().getSimpleName() + ": " + e.getMessage();
            return r;
        } finally {
            if (c != null) try { c.disconnect(); } catch (Exception ignore) { }
        }
    }

    private static void field(ByteArrayOutputStream b, String boundary, String k, String v)
            throws Exception {
        b.write(("--" + boundary + "\r\n").getBytes("UTF-8"));
        b.write(("Content-Disposition: form-data; name=\"" + k + "\"\r\n\r\n").getBytes("UTF-8"));
        b.write(v.getBytes("UTF-8"));
        b.write("\r\n".getBytes("UTF-8"));
    }

    private static String hmacSha1Base64(String key, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(key.getBytes("UTF-8"), "HmacSHA1"));
        byte[] out = mac.doFinal(data.getBytes("UTF-8"));
        return android.util.Base64.encodeToString(out, android.util.Base64.NO_WRAP);
    }

    private static String describe(int code, String msg) {
        String hint;
        switch (code) {
            // 1001 是最常见的一条。别再说「音量放大点」这种没用的建议 ——
            // 真到这一步说明指纹没匹配上，原因基本只有三种，直接摆出来：
            case 1001: hint = "没听出来（这首歌不在曲库里 / 是翻唱或现场版 / 车里太吵）"; break;
            case 1002: hint = "请求缺参数"; break;
            case 1003: hint = "参数值不对"; break;
            case 2004: hint = "音频解码失败"; break;
            case 3001: hint = "Access Key 不对，或者 Host 填了别的区域 —— 两个都要和控制台里显示的一模一样"; break;
            case 3002: hint = "Access Secret 不对，或者 Host 填了别的区域 —— 检查设置页"; break;
            case 3003: hint = "调用频率超限"; break;
            case 3014: hint = "本月额度已用完"; break;
            default:   hint = "识别服务返回错误"; break;
        }
        return hint + "（code=" + code + (msg.isEmpty() ? "" : " " + msg) + "）";
    }
}
