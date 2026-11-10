package com.lightmirror.dl;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/** 实时速度波形：最近 46 个采样点的折线 + 渐变填充。 */
public class WaveView extends View {

    private static final int CAP = 46;
    private final float[] data = new float[CAP];
    private int count;
    private float maxRef = 10f;

    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint base = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    public WaveView(Context c) {
        super(c);
        init();
    }

    public WaveView(Context c, AttributeSet a) {
        super(c, a);
        init();
    }

    public WaveView(Context c, AttributeSet a, int def) {
        super(c, a, def);
        init();
    }

    private void init() {
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(dp(1.8f));
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setColor(0xFF2BE3A7);
        fill.setStyle(Paint.Style.FILL);
        base.setStyle(Paint.Style.STROKE);
        base.setStrokeWidth(1f);
        base.setColor(0x14FFFFFF);
    }

    public void push(float mbps) {
        if (count == CAP) {
            System.arraycopy(data, 1, data, 0, CAP - 1);
            count = CAP - 1;
        }
        data[count++] = Math.max(0f, mbps);
        if (mbps > maxRef) maxRef = mbps * 1.15f;
        invalidate();
    }

    public void reset() {
        count = 0;
        maxRef = 10f;
        invalidate();
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        fill.setShader(new LinearGradient(0, 0, 0, h,
                0x662BE3A7, 0x002BE3A7, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        canvas.drawLine(0, h - 1, w, h - 1, base);
        if (count < 2) return;

        float step = (float) w / (CAP - 1);
        path.reset();
        path.moveTo(0, h);
        for (int i = 0; i < count; i++) {
            float x = i * step;
            float y = h - Math.min(data[i] / maxRef, 1f) * (h - dp(2));
            if (i == 0) path.lineTo(x, y);
            else path.lineTo(x, y);
        }
        path.lineTo((count - 1) * step, h);
        path.close();
        canvas.drawPath(path, fill);

        path.reset();
        for (int i = 0; i < count; i++) {
            float x = i * step;
            float y = h - Math.min(data[i] / maxRef, 1f) * (h - dp(2));
            if (i == 0) path.moveTo(x, y);
            else path.lineTo(x, y);
        }
        canvas.drawPath(path, line);
    }
}
