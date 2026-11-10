package com.lightmirror.dl;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

/**
 * 自动换行的 chip 容器。
 * 设置页里格式可能很多，横着排会被挤出屏幕，这里按行折下去。
 */
public class ChipFlow extends ViewGroup {

    private int hGap = 8;
    private int vGap = 8;

    public ChipFlow(Context c) {
        super(c);
    }

    public ChipFlow(Context c, AttributeSet a) {
        super(c, a);
    }

    public void setGaps(int h, int v) {
        hGap = h;
        vGap = v;
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int padL = getPaddingLeft(), padR = getPaddingRight();
        int avail = Math.max(0, MeasureSpec.getSize(widthSpec) - padL - padR);
        int childSpec = MeasureSpec.makeMeasureSpec(avail, MeasureSpec.AT_MOST);

        int x = 0, y = 0, rowH = 0, widest = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            if (c.getVisibility() == GONE) continue;
            measureChild(c, childSpec, heightSpec);
            int cw = c.getMeasuredWidth(), ch = c.getMeasuredHeight();
            if (x > 0 && x + cw > avail) {
                x = 0;
                y += rowH + vGap;
                rowH = 0;
            }
            x += cw + hGap;
            rowH = Math.max(rowH, ch);
            widest = Math.max(widest, x - hGap);
        }

        int height = y + rowH + getPaddingTop() + getPaddingBottom();
        setMeasuredDimension(
                resolveSize(Math.max(widest + padL + padR, getSuggestedMinimumWidth()), widthSpec),
                resolveSize(Math.max(height, getSuggestedMinimumHeight()), heightSpec));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int padL = getPaddingLeft(), padT = getPaddingTop();
        int avail = Math.max(0, r - l - padL - getPaddingRight());
        int x = 0, y = padT, rowH = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            if (c.getVisibility() == GONE) continue;
            int cw = c.getMeasuredWidth(), ch = c.getMeasuredHeight();
            if (x > 0 && x + cw > avail) {
                x = 0;
                y += rowH + vGap;
                rowH = 0;
            }
            c.layout(padL + x, y, padL + x + cw, y + ch);
            x += cw + hGap;
            rowH = Math.max(rowH, ch);
        }
    }
}
