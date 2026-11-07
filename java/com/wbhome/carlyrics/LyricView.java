package com.wbhome.carlyrics;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * 全屏歌词画布。
 *
 * 竖屏和横屏是**两套排布**（2026-09-15 老大哥反馈：竖屏字小、显示行数少、下半屏一大片空）：
 *
 *              横屏                          竖屏
 *  当前行字号  min(宽×5.2%, 高×15%)          min(宽×8.2%, 高×5.2%)
 *  可见行数    前1 + 当前 + 后2（4 行）       前4 + 当前 + 后4（9 行）
 *  当前行位置  高 × 40%（偏上）               高 × 46%（居中）
 *  超长行      缩字号                         自动折行（最多 2 段）
 *
 * 老问题的根因：竖屏也拿「宽 × 5.2%」定字号 —— 竖屏宽度本来就窄，字号被宽度死死卡住，
 * 于是字小；行数固定 4 行、当前行又钉在 40% 高度，下面 60% 全空。
 * 现在竖屏系数放大、改用高度封顶，行数铺开、当前行居中。
 *
 * 横屏那套数值是刻意保持原样的 —— 老大哥横屏用着没问题，别动。
 */
public class LyricView extends View {

    private static final int C_CUR = 0xFFFFC83D;
    private static final int C_NEXT = 0xFF8E9AAB;
    private static final int C_PREV = 0xFF5C6775;
    private static final int C_MID = 0xFF4A5462;
    private static final int C_FAR = 0xFF3A424E;
    private static final int C_TITLE = 0xFFAEB8C6;
    private static final int C_HINT = 0xFF6E7A8A;
    private static final int C_LEVEL_TITLE = 0xFFB9C3D1;

    /**
     * 竖屏每行最多折成几段。横屏折 1 段（不折）——
     * 横屏本来就扁，一折就把下面几行挤没了，那边还是按老办法缩字号。
     */
    private static final int WRAP_PORTRAIT = 2;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Config cfg;

    /** 一个「视觉行」= 折行后的一段 */
    private static final class Vis {
        final String text;
        final int owner;

        Vis(String t, int o) {
            text = t;
            owner = o;
        }
    }

    private final List<Vis> vis = new ArrayList<Vis>();
    private final List<String> rowSrc = new ArrayList<String>();
    private final List<String> rowBuf = new ArrayList<String>();

    public LyricView(Context c, AttributeSet a) {
        super(c, a);
        init();
    }

    private void init() {
        cfg = Config.get(getContext());
        p.setTextAlign(Paint.Align.CENTER);
        p.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        p.setSubpixelText(true);
    }

    @Override
    protected void onDraw(Canvas cv) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        boolean pt = h > w;                 // 竖屏
        TrackInfo t = PlayerState.track;
        Lrc lrc = PlayerState.lrc;

        if (t == null) {
            float y1 = h * (pt ? 0.38f : 0.42f);
            float y2 = h * (pt ? 0.48f : 0.55f);
            float y3 = h * (pt ? 0.58f : 0.66f);
            line(cv, w, y1, "正在听车里的音乐…", sz(w, h, pt, 0.062f, 0.042f, 0.11f), C_LEVEL_TITLE);
            line(cv, w, y2, PlayerState.detail, sz(w, h, pt, 0.032f, 0.022f, 0.055f), C_HINT);
            line(cv, w, y3, PlayerState.status, sz(w, h, pt, 0.030f, 0.020f, 0.05f), C_HINT);
            return;
        }

        if (lrc == null || !lrc.usable()) {
            float y1 = h * (pt ? 0.38f : 0.34f);
            float y2 = h * (pt ? 0.48f : 0.50f);
            float y3 = h * (pt ? 0.58f : 0.60f);
            line(cv, w, y1, t.display(), sz(w, h, pt, 0.048f, 0.034f, 0.09f), C_TITLE);
            line(cv, w, y2, "这首歌暂时没有找到歌词", sz(w, h, pt, 0.040f, 0.028f, 0.075f), C_HINT);
            line(cv, w, y3, "歌里第 " + (t.playOffsetMs / 1000) + " 秒 · 匹配度 " + (int) t.score,
                    sz(w, h, pt, 0.026f, 0.020f, 0.05f), C_FAR);
            return;
        }

        long pos = t.positionMs() + cfg.shiftMs();

        if (!lrc.synced) {
            drawPlain(cv, w, h, t, lrc, pos, pt);
            return;
        }

        int idx = lrc.indexAt(pos);
        if (idx < 0) {
            float y1 = h * (pt ? 0.38f : 0.34f);
            float y2 = h * (pt ? 0.48f : 0.50f);
            line(cv, w, y1, t.display(), sz(w, h, pt, 0.040f, 0.028f, 0.075f), C_TITLE);
            line(cv, w, y2, "♪ 前奏…", sz(w, h, pt, 0.050f, 0.032f, 0.085f), C_HINT);
            return;
        }

        drawSynced(cv, w, h, lrc, idx, pos, pt);
    }

    // ------------------------------------------------------------ 带时间轴

    private void drawSynced(Canvas cv, int w, int h, Lrc lrc, int idx, long pos, boolean pt) {
        rowSrc.clear();
        for (Lrc.Line ln : lrc.lines) rowSrc.add(ln.text);

        float curSize = pt ? Math.min(w * 0.082f, h * 0.052f)
                           : Math.min(w * 0.052f, h * 0.150f);

        // 行间平滑推进
        float prog = 0f;
        if (idx + 1 < lrc.lines.size()) {
            long a = lrc.lines.get(idx).t;
            long b = lrc.lines.get(idx + 1).t;
            if (b > a) {
                prog = (pos - a) / (float) (b - a);
                if (prog < 0f) prog = 0f;
                if (prog > 1f) prog = 1f;
            }
        }

        paint(cv, w, h, rowSrc, idx, prog, pt, curSize,
                pt ? 1.42f : 1.62f,
                pt ? 0.46f : 0.40f,
                pt ? 4 : 1,
                pt ? 4 : 2,
                pt ? WRAP_PORTRAIT : 1);
    }

    /** 没有时间轴的纯文本歌词：按播放进度比例估算位置 */
    private void drawPlain(Canvas cv, int w, int h, TrackInfo t, Lrc lrc, long pos, boolean pt) {
        int n = lrc.plainLines.size();
        if (n == 0) return;
        int idx = 0;
        if (t.durationMs > 10000) {
            double f = (double) pos / (double) t.durationMs;
            idx = (int) (f * n);
        }
        if (idx < 0) idx = 0;
        if (idx > n - 1) idx = n - 1;

        float curSize = pt ? Math.min(w * 0.072f, h * 0.046f)
                           : Math.min(w * 0.046f, h * 0.130f);

        paint(cv, w, h, lrc.plainLines, idx, 0f, pt, curSize,
                pt ? 1.42f : 1.55f,
                pt ? 0.46f : 0.40f,
                pt ? 4 : 1,
                pt ? 4 : 1,
                pt ? WRAP_PORTRAIT : 1);
    }

    // ------------------------------------------------------------ 主体绘制

    /**
     * 画歌词主体。src = 逻辑行文本，idx = 当前行。
     * 先把可见逻辑行按宽度折成视觉行，再整块排布，让「当前行的视觉块」落在基准线上。
     */
    private void paint(Canvas cv, int w, int h, List<String> src, int idx, float prog, boolean pt,
                       float curSize, float gapMul, float baseYRatio, int nUp, int nDown,
                       int maxWrap) {
        if (src.isEmpty()) return;

        float gap = curSize * gapMul;
        float baseY = h * baseYRatio;
        float maxW = w * 0.92f;

        int from = Math.max(0, idx - nUp);
        int to = Math.min(src.size() - 1, idx + nDown);

        vis.clear();
        for (int i = from; i <= to; i++) {
            List<String> parts = wrapRow(src.get(i), curSize, maxW, maxWrap);
            for (int k = 0; k < parts.size(); k++) vis.add(new Vis(parts.get(k), i));
        }
        if (vis.isEmpty()) return;

        int curFirst = 0;
        int curLast = 0;
        boolean found = false;
        for (int i = 0; i < vis.size(); i++) {
            if (vis.get(i).owner == idx) {
                if (!found) {
                    curFirst = i;
                    found = true;
                }
                curLast = i;
            }
        }

        float shift = -gap * prog;

        for (int i = 0; i < vis.size(); i++) {
            Vis v = vis.get(i);
            float y = baseY + (i - curFirst) * gap + shift;
            if (y < -gap || y > h + gap) continue;

            float scale;
            int color;
            if (i >= curFirst && i <= curLast) {
                scale = 1f;
                color = C_CUR;
            } else if (i < curFirst) {
                int k = curFirst - i;
                if (k == 1) { scale = 0.82f; color = C_PREV; }
                else if (k == 2) { scale = 0.78f; color = C_MID; }
                else { scale = 0.72f; color = C_FAR; }
            } else {
                int k = i - curLast;
                if (k == 1) { scale = 0.82f; color = C_NEXT; }
                else if (k == 2) { scale = 0.78f; color = C_MID; }
                else { scale = 0.72f; color = C_FAR; }
            }
            line(cv, w, y, v.text, curSize * scale, color);
        }
    }

    /**
     * 按宽度把一行拆成最多 maxWrap 段。
     * 中文歌词词间没有空格可断，直接按「放得下多少字」硬切 —— 视觉上跟字幕一样。
     * 最后一段不做测量：如果它仍然超宽，交给 line() 去缩/压，避免这里再切一刀把行数搞乱。
     */
    private List<String> wrapRow(String txt, float size, float maxW, int maxWrap) {
        rowBuf.clear();
        if (txt == null) return rowBuf;
        String s = txt.trim();
        if (s.isEmpty()) return rowBuf;

        p.setTextSize(size);
        p.setTextScaleX(1f);
        if (maxWrap <= 1 || p.measureText(s) <= maxW) {
            rowBuf.add(s);
            return rowBuf;
        }

        int start = 0;
        while (start < s.length() && rowBuf.size() < maxWrap) {
            if (rowBuf.size() == maxWrap - 1) {
                rowBuf.add(s.substring(start));
                break;
            }
            int end = start + 1;
            while (end <= s.length() && p.measureText(s.substring(start, end)) <= maxW) end++;
            int cut = end - 1;
            if (cut <= start) cut = start + 1;
            rowBuf.add(s.substring(start, cut));
            start = cut;
        }
        return rowBuf;
    }

    // ------------------------------------------------------------ 单行绘制

    private void line(Canvas cv, int w, float y, String txt, float size, int color) {
        if (txt == null) return;
        txt = txt.trim();
        if (txt.isEmpty()) return;

        p.setColor(color);
        p.setTextScaleX(1f);
        float s = size;
        if (s < 12f) s = 12f;
        p.setTextSize(s);
        float max = w * 0.92f;

        // 缩字号的底线上调到 62%（原来 40%，长行会被缩成蚂蚁）
        float floor = Math.max(14f, size * 0.62f);
        while (p.measureText(txt) > max && s > floor) {
            s -= 2f;
            p.setTextSize(s);
        }

        // 缩到下限仍放不下：横向压扁，保住字高。压不超过 32%，再长就让它轻微出界
        if (p.measureText(txt) > max) {
            float sx = max / p.measureText(txt);
            if (sx < 0.68f) sx = 0.68f;
            p.setTextScaleX(sx);
        }

        cv.drawText(txt, w / 2f, y, p);
        p.setTextScaleX(1f);
    }

    /** 竖屏 / 横屏两套字号系数，各自再按高度封顶 */
    private static float sz(int w, int h, boolean pt, float ptW, float landW, float hCap) {
        return Math.min(w * (pt ? ptW : landW), h * hCap);
    }
}
