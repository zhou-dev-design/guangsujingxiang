package com.lightmirror.dl;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.view.View;

/**
 * 单行自适应标签：地方够大就用原始字号，不够就一路缩，缩到放得下为止。
 * 不折行、不加省略号、没有字号下限——宁可字小，也要整串显示。
 *
 * 为什么不用 TextView：TextView 的 autoSize 在 singleLine 下不生效；
 * 而在 onLayout 之后再 setTextSize 又会被 ViewRootImpl 当作「布局中请求重新布局」
 * 吞掉（2.0.2 / 2.0.3 两次都栽在这上面，界面毫无变化）。
 * 这里干脆自己量、自己画：onMeasure 里拿到真实可用宽度后选字号，onDraw 直接
 * drawText，量多少画多少，不依赖 TextView 的内部布局时机。
 */
public class FitLabel extends View {

    private final TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private String text = "";
    private float baseSize;     // 原始字号（px）
    private float fitSize;      // 这次实际用的字号（px）

    public FitLabel(Context context) {
        super(context);
        paint.setTypeface(Typeface.DEFAULT);
        paint.setTextAlign(Paint.Align.LEFT);
    }

    public void setText(String s) {
        text = s == null ? "" : s;
        requestLayout();
        invalidate();
    }

    /** 设定「地方够大」时的字号（px） */
    public void setBaseSize(float px) {
        baseSize = px;
        requestLayout();
        invalidate();
    }

    public void setLabelColor(int color) {
        paint.setColor(color);
        invalidate();
    }

    public void setBold(boolean bold) {
        paint.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        requestLayout();
        invalidate();
    }

    public float getFitSize() {
        return fitSize;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int mode = MeasureSpec.getMode(widthMeasureSpec);
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int avail = width - getPaddingLeft() - getPaddingRight();

        fitSize = baseSize > 0f ? baseSize : paint.getTextSize();
        paint.setTextSize(fitSize);
        if (mode != MeasureSpec.UNSPECIFIED && avail > 0) {
            // 每 0.5px 降一档，没有下限，1px 也要塞进去；文字短就直接用原字号
            while (fitSize > 1f && paint.measureText(text) > avail) {
                fitSize = Math.max(1f, fitSize - 0.5f);
                paint.setTextSize(fitSize);
            }
        }

        Paint.FontMetrics fm = paint.getFontMetrics();
        int textHeight = (int) Math.ceil(fm.descent - fm.ascent);
        int wantW = (int) Math.ceil(paint.measureText(text)) + getPaddingLeft() + getPaddingRight();
        int measuredW;
        if (mode == MeasureSpec.EXACTLY) {
            measuredW = width;
        } else if (mode == MeasureSpec.AT_MOST) {
            measuredW = Math.min(wantW, width);
        } else {
            measuredW = wantW;
        }
        int measuredH = textHeight + getPaddingTop() + getPaddingBottom();
        setMeasuredDimension(resolveSize(measuredW, widthMeasureSpec),
                resolveSize(measuredH, heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (text.length() == 0 || fitSize <= 0f) return;
        Paint.FontMetrics fm = paint.getFontMetrics();
        float textHeight = fm.descent - fm.ascent;
        float top = getPaddingTop() + (getHeight() - getPaddingTop() - getPaddingBottom() - textHeight) / 2f;
        canvas.drawText(text, getPaddingLeft(), top - fm.ascent, paint);
    }
}
