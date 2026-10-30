package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * The next 24 hours under the clock, in the spirit of the Apple Weather card: a smooth temperature
 * line with a fade below, the current hour marked with a filled dot, the day's low and high with
 * hollow ones, faint columns for the chance of precipitation, and the city's hours along the
 * bottom. Pure drawing: nothing animates, nothing runs while Home sits idle.
 */
public class WeatherCurveView extends View {
    private long[] at;      // the window's points, the city's local hours encoded as UTC
    private float[] temps;
    private int[] pop;      // precipitation probability 0–100, -1 unknown
    private int n, minI = -1, maxI = -1;
    private int[] tickI;
    private String[] tickText;

    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);

    public WeatherCurveView(Context c) { this(c, null); }

    public WeatherCurveView(Context c, AttributeSet a) {
        super(c, a);
        float d = c.getResources().getDisplayMetrics().density;
        float sd = c.getResources().getDisplayMetrics().scaledDensity;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        label.setTextAlign(Paint.Align.CENTER);
        tick.setTextAlign(Paint.Align.CENTER);
        bar.setStyle(Paint.Style.FILL);
        // Sizes fixed once: the view is drawn at one density and never reconfigured.
        line.setStrokeWidth(2 * d);
        line.setColor(0xFFFFFFFF);
        label.setTextSize(10.5f * sd);
        tick.setTextSize(10 * sd);
        dot.setStrokeWidth(1.6f * d);
        label.setColor(0xF2FFFFFF);
        tick.setColor(0x80FFFFFF);
        dot.setColor(0xFFFFFFFF);
        bar.setColor(0x59FFFFFF);
    }

    /** Picks the window (this hour up to 24 hours ahead) and precomputes everything onDraw needs. */
    public void setData(Weather.Hourly h, long nowMillis) {
        if (h == null || h.atLocal.length == 0) { n = 0; invalidate(); return; }
        long hourNow = Math.floorDiv(nowMillis / 1000L + h.offsetSec, 3600L) * 3600L;
        int start = 0;
        while (start < h.atLocal.length && h.atLocal[start] < hourNow) start++;
        int count = Math.min(24, h.atLocal.length - start);
        if (count < 3) { n = 0; invalidate(); return; }
        at = new long[count]; temps = new float[count]; pop = new int[count];
        System.arraycopy(h.atLocal, start, at, 0, count);
        System.arraycopy(h.temps, start, temps, 0, count);
        System.arraycopy(h.pop, start, pop, 0, count);
        n = count;
        minI = maxI = -1;
        for (int i = 1; i < n; i++) {
            if (minI < 0 || temps[i] < temps[minI]) minI = i;
            if (maxI < 0 || temps[i] > temps[maxI]) maxI = i;
        }
        int[] ticks = {0, 6, 12, 18};
        tickI = new int[ticks.length]; tickText = new String[ticks.length];
        int k = 0;
        Context c = getContext();
        for (int t : ticks) {
            if (t >= n) break;
            tickI[k] = t;
            tickText[k] = t == 0 ? c.getString(R.string.wx_now)
                    : String.format(c.getString(R.string.wx_hour), (int) ((at[t] % 86400) / 3600));
            k++;
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (n < 3) return;
        float w = getWidth(), h = getHeight();
        float d = getResources().getDisplayMetrics().density;
        float pad = 7 * d, top = 20 * d, bottom = h - 15 * d;
        float step = (w - 2 * pad) / (n - 1);
        float lo = temps[0], hi = temps[0];
        for (int i = 0; i < n; i++) { lo = Math.min(lo, temps[i]); hi = Math.max(hi, temps[i]); }
        float span = Math.max(hi - lo, 1f) * 1.15f;
        float mid = (lo + hi) / 2f;
        float[] xs = new float[n], ys = new float[n];
        for (int i = 0; i < n; i++) {
            xs[i] = pad + step * i;
            ys[i] = (bottom + top) / 2f - (temps[i] - mid) / span * (bottom - top);
        }
        // Line and its fade.
        Path curve = smooth(xs, ys);
        fill.setShader(new LinearGradient(0, top, 0, bottom, 0x2EFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        Path area = new Path(curve);
        area.lineTo(xs[n - 1], bottom); area.lineTo(xs[0], bottom); area.close();
        canvas.drawPath(area, fill);
        canvas.drawPath(curve, line);
        // Precipitation columns along the curve's floor, above the hours line.
        for (int i = 0; i < n; i++) {
            if (pop[i] < 5) continue;
            float bh = pop[i] / 100f * 11 * d;
            canvas.drawRect(xs[i] - d, bottom - bh, xs[i] + d, bottom, bar);
        }
        // The current hour: a filled dot and its temperature; the low and the high: hollow rings.
        // Marks that would crowd the current hour (it is often also the day's low before dawn) are
        // left out: their number is already on screen.
        canvas.drawCircle(xs[0], ys[0], 3.2f * d, dot);
        drawTemp(canvas, xs[0], ys[0], temps[0], d, true);
        if (minI > 1) { dot.setStyle(Paint.Style.STROKE); canvas.drawCircle(xs[minI], ys[minI], 2.6f * d, dot); dot.setStyle(Paint.Style.FILL); drawTemp(canvas, xs[minI], ys[minI], temps[minI], d, false); }
        if (maxI > 1) { dot.setStyle(Paint.Style.STROKE); canvas.drawCircle(xs[maxI], ys[maxI], 2.6f * d, dot); dot.setStyle(Paint.Style.FILL); drawTemp(canvas, xs[maxI], ys[maxI], temps[maxI], d, false); }
        // Hours along the bottom.
        for (int k = 0; k < tickI.length && tickText[k] != null; k++) {
            canvas.drawText(tickText[k], clamp(xs[tickI[k]], tick.measureText(tickText[k]) / 2f, w - pad), h - 3.5f * d, tick);
        }
    }

    /** The temperature above a point, kept inside the view. */
    private void drawTemp(Canvas canvas, float x, float y, float t, float d, boolean now) {
        String s = String.valueOf(Math.round(t)) + "°";
        float tw = label.measureText(s);
        canvas.drawText(s, clamp(x, tw / 2f, getWidth() - tw / 2f - 2 * d),
                y - (now ? 7f : 6.5f) * d, label);
    }

    private static float clamp(float x, float min, float max) {
        return Math.max(min, Math.min(max, x));
    }

    /** Catmull-Rom through the points as cubic Béziers: smooth like Apple's, no overshoot at the ends. */
    private Path smooth(float[] xs, float[] ys) {
        Path p = new Path();
        p.moveTo(xs[0], ys[0]);
        for (int i = 1; i < n; i++) {
            float x1 = xs[i - 1], y1 = ys[i - 1], x2 = xs[i], y2 = ys[i];
            float cx1 = x1 + (x2 - xs[Math.max(0, i - 2)]) / 6f;
            float cy1 = y1 + (y2 - ys[Math.max(0, i - 2)]) / 6f;
            float cx2 = x2 - (xs[Math.min(n - 1, i + 1)] - x1) / 6f;
            float cy2 = y2 - (ys[Math.min(n - 1, i + 1)] - y1) / 6f;
            p.cubicTo(cx1, cy1, cx2, cy2, x2, y2);
        }
        return p;
    }
}
