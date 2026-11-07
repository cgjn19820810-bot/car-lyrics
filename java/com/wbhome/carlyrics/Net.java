package com.wbhome.carlyrics;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

public final class Net {

    public static final String UA = "CarLyrics/1.0 (Android)";

    /**
     * 浏览器 UA。歌词站点（QQ音乐 / 网易云）会挑 UA，
     * 非浏览器 UA 容易被拒或返回空 body —— 实测 python 用浏览器 UA 才拿到数据。
     */
    public static final String UA_BROWSER =
            "Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    public static String get(String url, String referer, int timeoutMs) {
        return get(url, referer, UA, timeoutMs);
    }

    public static String get(String url, String referer, String ua, int timeoutMs) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setRequestProperty("User-Agent", ua == null ? UA : ua);
            c.setRequestProperty("Accept", "*/*");
            if (referer != null) c.setRequestProperty("Referer", referer);
            int code = c.getResponseCode();
            InputStream in;
            if (code >= 200 && code < 300) {
                in = c.getInputStream();
            } else {
                in = c.getErrorStream();
            }
            if (in == null) return null;
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return new String(bo.toByteArray(), "UTF-8");
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) try { c.disconnect(); } catch (Exception ignore) { }
        }
    }

    public static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }
}
