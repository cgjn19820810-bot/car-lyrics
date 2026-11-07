import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 端到端验证：用 App 完全一致的方式组装 WAV + multipart，真发一次 AudD 请求。
 *
 * 严格对齐 AudioRecorder.wav() 的包头写法（ffmpeg 产的 WAV 会在 fmt 后插 LIST 块，
 * 与实际 App 输出不同，所以这里拿原始 PCM 自己封头，测的才是 App 真正发出去的字节）。
 * 顺带验证 RMS 静音判断在真实音乐上的表现。
 */
public class B {

    static final int RATE = 16000;

    public static void main(String[] args) throws Exception {
        byte[] pcm = readAll("ex.pcm");
        System.out.println("原始 PCM：" + pcm.length + " 字节 = " + (pcm.length / (RATE * 2)) + " 秒");

        // ---- 1. 与 AudioRecorder.wav() 一致的封头 ----
        byte[] wav = wav(pcm, RATE, 1, 16);
        System.out.println("封成 WAV：" + wav.length + " 字节  （RIFF+fmt+data，无 ffmpeg 那种 LIST 块）");

        // ---- 2. RMS + 静音判断（真实音乐应该远高于 110）----
        double sumSq = 0d; long cnt = 0;
        for (int i = 0; i + 1 < pcm.length; i += 2) {
            short v = (short) ((pcm[i] & 0xFF) | (pcm[i + 1] << 8));
            sumSq += (double) v * v; cnt++;
        }
        double rms = cnt > 0 ? Math.sqrt(sumSq / cnt) : 0d;
        System.out.println("RMS = " + (int) rms + "  → 静音判断(阈值110): " + (rms < 110 ? "判定为静音(会跳过)" : "有声音，正常送识别"));

        // ---- 3. 与 AudD.identify() 一致的 multipart ----
        String bd = "----CarLyricsAudd" + Long.toHexString(System.nanoTime());
        ByteArrayOutputStream body = new ByteArrayOutputStream(wav.length + 1024);
        field(body, bd, "api_token", "test");
        body.write(("--" + bd + "\r\n").getBytes("UTF-8"));
        body.write(("Content-Disposition: form-data; name=\"file\"; filename=\"sample.wav\"\r\n")
                .getBytes("UTF-8"));
        body.write("Content-Type: audio/wav\r\n\r\n".getBytes("UTF-8"));
        body.write(wav);
        body.write("\r\n".getBytes("UTF-8"));
        body.write(("--" + bd + "--\r\n").getBytes("UTF-8"));

        long t0 = System.currentTimeMillis();
        HttpURLConnection c = (HttpURLConnection) new URL("https://api.audd.io/").openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setConnectTimeout(8000);
        c.setReadTimeout(20000);
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + bd);
        c.setRequestProperty("User-Agent", "CarLyrics/1.0 (Android)");
        c.setFixedLengthStreamingMode(body.size());
        OutputStream os = c.getOutputStream();
        body.writeTo(os);
        os.flush(); os.close();

        int http = c.getResponseCode();
        InputStream in = (http >= 200 && http < 300) ? c.getInputStream() : c.getErrorStream();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192]; int n;
        while (in != null && (n = in.read(buf)) > 0) bo.write(buf, 0, n);
        String resp = new String(bo.toByteArray(), "UTF-8");
        c.disconnect();
        long ms = System.currentTimeMillis() - t0;

        System.out.println("\nHTTP " + http + "  耗时 " + ms + " ms");
        System.out.println("响应：" + (resp.length() > 300 ? resp.substring(0, 300) + "..." : resp));

        // ---- 4. 按 AudD.java 的逻辑解析 ----
        String title = pick(resp, "\"title\":\"");
        String artist = pick(resp, "\"artist\":\"");
        String timecode = pick(resp, "\"timecode\":\"");
        System.out.println("\n--- 按 App 的解析逻辑提取 ---");
        System.out.println("artist   = " + artist);
        System.out.println("title    = " + title);
        System.out.println("timecode = " + timecode + "   → " + parseTimecode(timecode) + " ms");

        boolean ok = resp.contains("\"status\":\"success\"");
        System.out.println();
        System.out.println(ok ? "✅ 端到端通过：App 的 WAV 封头 + multipart 构造，AudD 正常识别"
                             : "❌ 失败");
    }

    // 与 AudD.parseTimecode 一致
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
                if (i == parts.length - 1) return total * 1000L + Math.round((v - whole) * 1000d);
            }
        } catch (Exception e) { return 0L; }
        return total * 1000L;
    }

    static String pick(String json, String key) {
        int i = json.indexOf(key);
        if (i < 0) return "(无)";
        int s = i + key.length();
        int e = json.indexOf('"', s);
        if (e < 0) return "(无)";
        return json.substring(s, e);
    }

    static byte[] readAll(String path) throws Exception {
        File f = new File(path);
        FileInputStream in = new FileInputStream(f);
        byte[] b = new byte[(int) f.length()];
        int off = 0;
        while (off < b.length) { int n = in.read(b, off, b.length - off); if (n <= 0) break; off += n; }
        in.close();
        return b;
    }

    static void field(ByteArrayOutputStream b, String boundary, String k, String v) throws Exception {
        b.write(("--" + boundary + "\r\n").getBytes("UTF-8"));
        b.write(("Content-Disposition: form-data; name=\"" + k + "\"\r\n\r\n").getBytes("UTF-8"));
        b.write(v.getBytes("UTF-8"));
        b.write("\r\n".getBytes("UTF-8"));
    }

    static byte[] wav(byte[] pcm, int rate, int ch, int bits) {
        int byteRate = rate * ch * bits / 8;
        int dataLen = pcm.length;
        byte[] h = new byte[44];
        put(h, 0, "RIFF"); putInt(h, 4, 36 + dataLen); put(h, 8, "WAVE");
        put(h, 12, "fmt "); putInt(h, 16, 16); putShort(h, 20, 1); putShort(h, 22, ch);
        putInt(h, 24, rate); putInt(h, 28, byteRate);
        putShort(h, 32, ch * bits / 8); putShort(h, 34, bits);
        put(h, 36, "data"); putInt(h, 40, dataLen);
        byte[] out = new byte[44 + dataLen];
        System.arraycopy(h, 0, out, 0, 44);
        System.arraycopy(pcm, 0, out, 44, dataLen);
        return out;
    }

    static void put(byte[] b, int o, String s) { for (int i = 0; i < s.length(); i++) b[o + i] = (byte) s.charAt(i); }
    static void putInt(byte[] b, int o, int v) {
        b[o] = (byte)(v&0xFF); b[o+1] = (byte)((v>>8)&0xFF); b[o+2] = (byte)((v>>16)&0xFF); b[o+3] = (byte)((v>>24)&0xFF);
    }
    static void putShort(byte[] b, int o, int v) { b[o] = (byte)(v&0xFF); b[o+1] = (byte)((v>>8)&0xFF); }
}
