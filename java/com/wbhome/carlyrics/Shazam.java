package com.wbhome.carlyrics;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.UUID;

/**
 * Shazam 识别客户端 —— 识曲主引擎（免费、不限次、同步快、曲库大）。
 *
 * 与 ACRCloud/AudD 的关键差异：
 *  - **指纹在本地算**：用 {@link ShazamFingerprint} 对 16kHz mono s16 生成指纹（Wang 2003 算法），
 *    只把指纹（几十 KB 的 data URI）POST 到 Apple 的 Shazam 服务，不传音频本身；
 *  - **免费不限次**：无 API key、无计费。识曲协议是 shazamio / SongRec 同款公开逆向接口
 *    （固定 header + JSON body，无签名），国内直连 amp.shazam.com 可用；
 *  - **offset = 片段起点**：响应的 matches[0].offset 是「这段音频在原曲中的起始秒数」
 *    （Apple ShazamKit 文档：matchOffset = "matches the start of the query"），
 *    与 ACRCloud 的 play_offset_ms（片段起点）同语义，直接 *1000 交给上层校准歌词。
 *
 * 为什么它能当主力：ACRCloud 按次计费、AudD 免费额度极低，而 Shazam 全免费 ——
 * 用它先探，绝大多数歌直接命中，只有它认不出的才消耗 ACRCloud / AudD 的额度。
 */
public final class Shazam {

    private static final String ENDPOINT =
            "https://amp.shazam.com/discovery/v5/en-US/GB/iphone/-/tag/%s/%s"
            + "?sync=true&webv3=true&sampling=true&connected=&shazamapiversion=v3"
            + "&sharehub=true&hubv5minorversion=v5.1&hidelb=true&video=v3";

    /** shazamio 同款 Dalvik UA（App UA 才到得了 API，浏览器 UA 会 405） */
    private static final String[] UA = {
            "Dalvik/2.1.0 (Linux; U; Android 5.0.2; VS980 4G Build/LRX22G)",
            "Dalvik/2.1.0 (Linux; U; Android 6.0.1; SM-G920F Build/MMB29K)",
            "Dalvik/2.1.0 (Linux; U; Android 5.1.1; SM-N910G Build/LMY47X)",
    };

    private Shazam() { }

    /**
     * 用原始 16kHz 单声道 s16 音频识别。
     *
     * @param pcm16kMono 16kHz / 单声道 / 16bit 小端原始 PCM（short 数组）
     * @return 结果；ok=false 时 message 说明原因（网络错 / 没听出来 / 音频太短）
     */
    public static AcrCloud.Result identify(short[] pcm16kMono) {
        AcrCloud.Result r = new AcrCloud.Result();

        // 至少 ~0.5 秒才可能有指纹峰值（do_peak_recognition 要 46 帧起）；更短直接判失败，别白联网。
        if (pcm16kMono == null || pcm16kMono.length < 46 * 128) {
            r.message = "Shazam 音频太短（" + (pcm16kMono == null ? 0 : pcm16kMono.length / 16000) + " 秒）";
            return r;
        }

        String uri;
        try {
            uri = ShazamFingerprint.makeSignatureUri(pcm16kMono);
        } catch (Throwable e) {
            r.message = "Shazam 指纹失败: " + e.getClass().getSimpleName();
            return r;
        }

        HttpURLConnection c = null;
        try {
            JSONObject body = new JSONObject();
            body.put("timezone", "Europe/Moscow");
            JSONObject sig = new JSONObject();
            sig.put("uri", uri);
            sig.put("samplems", pcm16kMono.length);
            body.put("signature", sig);
            body.put("timestamp", System.currentTimeMillis() / 1000L);
            body.put("context", new JSONObject());
            body.put("geolocation", new JSONObject());

            String url = String.format(ENDPOINT,
                    UUID.randomUUID().toString().toUpperCase(),
                    UUID.randomUUID().toString().toUpperCase());

            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setConnectTimeout(8000);
            c.setReadTimeout(20000);
            c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            c.setRequestProperty("X-Shazam-Platform", "IPHONE");
            c.setRequestProperty("X-Shazam-AppVersion", "14.1.0");
            c.setRequestProperty("Accept", "*/*");
            c.setRequestProperty("Accept-Language", "en-US");
            // 千万别手动设 Accept-Encoding：Android 的 HttpURLConnection 一旦手动设了它，
            // 就不会再自动解压 gzip 响应，Shazam 返回的压缩 JSON 会读成一坨乱码，
            // new JSONObject() 直接抛 JSONException（PC 端 aiohttp 自动解压，所以电脑能成、手机不行）。
            // 不设 = 系统自动加 gzip 并透明解压，这才是对的。
            c.setRequestProperty("User-Agent", UA[(int) (System.currentTimeMillis() % UA.length)]);

            byte[] payload = body.toString().getBytes("UTF-8");
            c.setFixedLengthStreamingMode(payload.length);
            OutputStream os = c.getOutputStream();
            os.write(payload);
            os.flush();
            os.close();

            int http = c.getResponseCode();
            if (http < 200 || http >= 300) {
                r.message = "Shazam 服务器拒绝 HTTP " + http + "（网络可达但接口没放行）";
                return r;
            }
            InputStream in = c.getInputStream();
            if (in == null) {
                r.message = "Shazam 无响应体";
                return r;
            }
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            String resp = new String(bo.toByteArray(), "UTF-8");

            JSONObject j = new JSONObject(resp);
            JSONArray matches = j.optJSONArray("matches");
            if (matches == null || matches.length() == 0) {
                r.code = 1001;
                r.message = "Shazam 没听出来（送 " + (pcm16kMono.length / 16000)
                        + " 秒，不在曲库 / 翻唱现场版 / 环境太吵）";
                return r;
            }

            JSONObject track = j.optJSONObject("track");
            if (track == null) {
                r.code = 1001;
                r.message = "Shazam 返回了匹配但没有曲目信息";
                return r;
            }

            double offset = matches.getJSONObject(0).optDouble("offset", 0d);

            TrackInfo t = new TrackInfo();
            t.engine = "Shazam";
            t.title = track.optString("title", "");
            t.artist = track.optString("subtitle", "");   // Shazam 的 subtitle 是歌手
            t.album = "";
            t.playOffsetMs = (long) (offset * 1000d);
            t.durationMs = 0L;                            // 识曲接口不给总时长，交给歌词估算
            t.score = 0d;
            t.acrid = "shazam:" + track.optString("key", "") + "|" + t.artist + "|" + t.title;

            if (t.title.isEmpty() && t.artist.isEmpty()) {
                r.code = 1001;
                r.message = "Shazam 返回了空结果";
                return r;
            }

            r.ok = true;
            r.track = t;
            r.message = "识别成功（Shazam）";
            return r;
        } catch (Exception e) {
            r.message = "Shazam 网络/解析失败 " + e.getClass().getSimpleName() + ": " + e.getMessage();
            return r;
        } finally {
            if (c != null) try { c.disconnect(); } catch (Exception ignore) { }
        }
    }
}
