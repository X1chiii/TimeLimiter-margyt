package cat.x1chi.timelimit;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.view.MotionEvent;
import android.view.View;

/** Hue/saturation disc. Brightness is applied by darkening the whole disc. */
final class ColorWheel extends View {
    interface Changed { void on(int colour); }

    private final Paint huePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint satPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint darkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float hue = 0f, sat = 1f, val = 1f;
    private float cx, cy, r;
    private Changed changed;

    ColorWheel(Context c) {
        super(c);
        ring.setStyle(Paint.Style.STROKE);
    }

    void setChanged(Changed c) { changed = c; }

    float value() { return val; }

    int colour() { return Color.HSVToColor(new float[] { hue, sat, val }); }

    void setColour(int colour) {
        float[] hsv = new float[3];
        Color.colorToHSV(colour, hsv);
        hue = hsv[0];
        sat = hsv[1];
        val = hsv[2];
        invalidate();
        fire();
    }

    void setValue(float v) {
        val = Math.max(0f, Math.min(1f, v));
        invalidate();
        fire();
    }

    private void fire() {
        if (changed != null) changed.on(colour());
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        cx = w / 2f;
        cy = h / 2f;
        r = Math.min(w, h) / 2f - 20f;
        int[] hues = { 0xFFFF0000, 0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF,
                0xFF0000FF, 0xFFFF00FF, 0xFFFF0000 };
        huePaint.setShader(new SweepGradient(cx, cy, hues, null));
        satPaint.setShader(new RadialGradient(cx, cy, r, 0xFFFFFFFF, 0x00FFFFFF,
                Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas cv) {
        cv.drawCircle(cx, cy, r, huePaint);
        cv.drawCircle(cx, cy, r, satPaint);
        darkPaint.setColor(Color.argb(Math.round((1f - val) * 255f), 0, 0, 0));
        cv.drawCircle(cx, cy, r, darkPaint);

        double ang = Math.toRadians(hue);
        float mx = cx + (float) Math.cos(ang) * sat * r;
        float my = cy + (float) Math.sin(ang) * sat * r;
        ring.setColor(Color.BLACK);
        ring.setStrokeWidth(8f);
        cv.drawCircle(mx, my, 16f, ring);
        ring.setColor(Color.WHITE);
        ring.setStrokeWidth(4f);
        cv.drawCircle(mx, my, 16f, ring);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        float dx = e.getX() - cx;
        float dy = e.getY() - cy;
        double deg = Math.toDegrees(Math.atan2(dy, dx));
        if (deg < 0) deg += 360;
        hue = (float) deg;
        sat = Math.min(1f, (float) Math.hypot(dx, dy) / r);
        invalidate();
        fire();
        return true;
    }
}
