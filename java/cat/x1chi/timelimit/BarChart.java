package cat.x1chi.timelimit;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.TypedValue;
import android.view.View;

/** Bars for the last 7 days with a dashed line at the daily limit. */
final class BarChart extends View {
    private final long[] v;
    private final String[] lab;
    private final long limit;
    private final int accent;
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    BarChart(Context c, long[] v, String[] lab, long limit, int fg, int accent) {
        super(c);
        this.v = v;
        this.lab = lab;
        this.limit = limit;
        this.accent = accent;
        txt.setColor(fg);
        txt.setTextAlign(Paint.Align.CENTER);
        txt.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11,
                c.getResources().getDisplayMetrics()));
        line.setColor(fg);
        line.setAlpha(140);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(TimeLimit.dp(c, 1));
        line.setPathEffect(new DashPathEffect(new float[] { TimeLimit.dp(c, 6), TimeLimit.dp(c, 4) }, 0f));
    }

    @Override
    protected void onDraw(Canvas cv) {
        float w = getWidth();
        float h = getHeight();
        float top = TimeLimit.dp(getContext(), 22);
        float bottom = TimeLimit.dp(getContext(), 26);
        float ch = h - top - bottom;

        long max = limit;
        for (long x : v) max = Math.max(max, x);
        if (max <= 0) max = 60000L;
        float m = max * 1.12f;

        float bw = w / v.length;
        float rad = TimeLimit.dp(getContext(), 6);
        for (int i = 0; i < v.length; i++) {
            float l = i * bw + bw * 0.2f;
            float r = (i + 1) * bw - bw * 0.2f;
            float bh = v[i] / m * ch;
            float t = top + ch - bh;
            bar.setColor(accent);
            bar.setAlpha(i == v.length - 1 ? 255 : 150);
            rect.set(l, t, r, top + ch);
            cv.drawRoundRect(rect, rad, rad, bar);
            cv.drawText(shortFmt(v[i]), (l + r) / 2f, t - TimeLimit.dp(getContext(), 4), txt);
            cv.drawText(lab[i], (l + r) / 2f, h - TimeLimit.dp(getContext(), 8), txt);
        }
        float ly = top + ch - limit / m * ch;
        cv.drawLine(0f, ly, w, ly, line);
    }

    private static String shortFmt(long ms) {
        long mins = ms / 60000L;
        if (mins < 60) return mins + TimeLimit.tr("м", "m");
        return (mins / 60) + TimeLimit.tr("ч", "h") + (mins % 60) + TimeLimit.tr("м", "m");
    }
}
