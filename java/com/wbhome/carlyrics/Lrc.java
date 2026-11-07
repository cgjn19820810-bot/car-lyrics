package com.wbhome.carlyrics;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Lrc {

    public static class Line {
        public final long t;
        public final String text;

        public Line(long t, String text) {
            this.t = t;
            this.text = text;
        }
    }

    public final List<Line> lines = new ArrayList<>();
    public final List<String> plainLines = new ArrayList<>();
    public boolean synced = false;
    public String plainText = "";
    public String source = "";

    private static final Pattern TAG =
            Pattern.compile("\\[(\\d{1,4}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]");

    public static Lrc parse(String raw, String source) {
        Lrc l = new Lrc();
        l.source = source == null ? "" : source;
        if (raw == null) return l;

        TreeMap<Long, String> map = new TreeMap<>();
        StringBuilder plain = new StringBuilder();

        String[] rows = raw.split("\\r?\\n");
        for (String row : rows) {
            Matcher m = TAG.matcher(row);
            boolean hit = false;
            while (m.find()) {
                hit = true;
                long mm;
                long ss;
                try {
                    mm = Long.parseLong(m.group(1));
                    ss = Long.parseLong(m.group(2));
                } catch (Exception e) {
                    continue;
                }
                String f = m.group(3);
                int frac = 0;
                if (f != null && !f.isEmpty()) {
                    if (f.length() == 1) frac = Integer.parseInt(f) * 100;
                    else if (f.length() == 2) frac = Integer.parseInt(f) * 10;
                    else frac = Integer.parseInt(f.substring(0, 3));
                }
                String txt = row.substring(m.end()).trim();
                long key = (mm * 60L + ss) * 1000L + frac;
                if (!map.containsKey(key)) map.put(key, txt);
            }
            if (!hit) {
                String t = row.trim();
                if (!t.isEmpty() && !t.startsWith("[")) plain.append(t).append('\n');
            }
        }

        for (Map.Entry<Long, String> e : map.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            l.lines.add(new Line(e.getKey(), e.getValue()));
        }

        l.synced = l.lines.size() >= 4;
        l.plainText = plain.toString().trim();

        if (!l.synced) {
            if (l.plainText.isEmpty() && !l.lines.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (Line ln : l.lines) sb.append(ln.text).append('\n');
                l.plainText = sb.toString().trim();
            }
            for (String s : l.plainText.split("\\r?\\n")) {
                String t = s.trim();
                if (!t.isEmpty()) l.plainLines.add(t);
            }
            l.lines.clear();
        }
        return l;
    }

    public boolean usable() {
        return (synced && !lines.isEmpty()) || !plainLines.isEmpty();
    }

    /** 返回 pos 时刻应显示的行下标，-1 表示还没开始 */
    public int indexAt(long ms) {
        if (lines.isEmpty()) return -1;
        int lo = 0, hi = lines.size() - 1, ans = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (lines.get(mid).t <= ms) {
                ans = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return ans;
    }
}
