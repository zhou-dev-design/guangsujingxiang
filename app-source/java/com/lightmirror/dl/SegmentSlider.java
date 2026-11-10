package com.lightmirror.dl;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * 两段式滑块：左右各一项，点哪边就滑到哪边，也可以按住拖过去。
 * 选中那一侧用主色块兜住（看起来是滑块加深），另一侧只有文字。
 * <p>
 * 只负责「选第几个」，具体含义交给调用方。
 */
public class SegmentSlider extends View {

    public interface OnPickListener {
        void onPick(int index);
    }

    private static final int COUNT = 2;

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private String[] labels = {"", ""};
    private int index = 0;
    /** 0~1，动画过程中的滑块位置；静止时等于 index */
    private float pos = 0f;

    private ValueAnimator animator;
    private OnPickListener listener;

    private float downX, downY, downPos;
    private boolean dragging, horizontal;

    private int cTrack, cTrackLine, cThumb, cOnThumb, cIdleText;

    public SegmentSlider(Context c) {
        this(c, null);
    }

    public SegmentSlider(Context c, AttributeSet a) {
        super(c, a);
        setClickable(true);
        setFocusable(true);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(dp(1));
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(Typeface.DEFAULT_BOLD);
        textPaint.setTextSize(dim(R.attr.dT2));
        resolveColors();
    }

    private void resolveColors() {
        cTrack = color(R.attr.cPrimarySoft);
        cTrackLine = color(R.attr.cPrimaryLine);
        cThumb = color(R.attr.cPrimary);
        cOnThumb = color(R.attr.cOnPrimary);
        cIdleText = color(R.attr.cText2);
        bgPaint.setColor(cTrack);
        linePaint.setColor(cTrackLine);
        thumbPaint.setColor(cThumb);
    }

    public void setLabels(String left, String right) {
        labels = new String[]{left, right};
        invalidate();
    }

    /** 直接落位，不回调（初始化用） */
    public void setSelected(int i) {
        index = i < 0 || i >= COUNT ? 0 : i;
        pos = index;
        invalidate();
    }

    public int getSelected() {
        return index;
    }

    public void setOnPickListener(OnPickListener l) {
        listener = l;
    }

    // ------------------------------------------------------------ 绘制

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = resolveSize(dp(220), widthSpec);
        int h = resolveSize(dp(42), heightSpec);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(Canvas cv) {
        final int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        final float pad = dp(3);
        final float radius = h / 2f;
        final float segW = (w - pad * 2) / COUNT;

        rect.set(0, 0, w, h);
        cv.drawRoundRect(rect, radius, radius, bgPaint);
        rect.set(0.5f, 0.5f, w - 0.5f, h - 0.5f);
        cv.drawRoundRect(rect, radius, radius, linePaint);

        // 滑块本体：跟着 pos 平滑移动
        final float tx = pad + segW * pos;
        rect.set(tx, pad, tx + segW, h - pad);
        cv.drawRoundRect(rect, (h - pad * 2) / 2f, (h - pad * 2) / 2f, thumbPaint);

        final float baseY = h / 2f - (textPaint.ascent() + textPaint.descent()) / 2f;
        for (int i = 0; i < COUNT; i++) {
            // 这一侧被滑块盖住多少，文字就从「灰」过渡到「反白」
            float cover = 1f - Math.abs(pos - i);
            if (cover < 0f) cover = 0f;
            if (cover > 1f) cover = 1f;
            textPaint.setColor(blend(cIdleText, cOnThumb, cover));
            cv.drawText(labels[i], pad + segW * (i + 0.5f), baseY, textPaint);
        }
    }

    private int blend(int from, int to, float t) {
        int a = (int) (android.graphics.Color.alpha(from) * (1 - t)
                + android.graphics.Color.alpha(to) * t);
        int r = (int) (android.graphics.Color.red(from) * (1 - t)
                + android.graphics.Color.red(to) * t);
        int g = (int) (android.graphics.Color.green(from) * (1 - t)
                + android.graphics.Color.green(to) * t);
        int b = (int) (android.graphics.Color.blue(from) * (1 - t)
                + android.graphics.Color.blue(to) * t);
        return android.graphics.Color.argb(a, r, g, b);
    }

    // ------------------------------------------------------------ 交互

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        final float segW = (getWidth() - dp(3) * 2) / (float) COUNT;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                downPos = pos;
                dragging = false;
                horizontal = false;
                if (animator != null) animator.cancel();
                return true;

            case MotionEvent.ACTION_MOVE: {
                final float dx = e.getX() - downX;
                final float dy = e.getY() - downY;
                if (!horizontal) {
                    if (Math.abs(dx) > dp(6) && Math.abs(dx) > Math.abs(dy)) {
                        horizontal = true;
                        dragging = true;
                        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    } else if (Math.abs(dy) > dp(6)) {
                        // 竖着划是页面滚动，不抢事件，交给外面的 ScrollView
                    }
                }
                if (dragging && segW > 0) {
                    float p = downPos + dx / segW;
                    pos = Math.max(0f, Math.min(COUNT - 1, p));
                    invalidate();
                }
                return true;
            }

            case MotionEvent.ACTION_UP:
                if (dragging) {
                    settle(pos > 0.5f ? 1 : 0);
                } else {
                    settle(e.getX() < getWidth() / 2f ? 0 : 1);
                }
                dragging = false;
                horizontal = false;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                performClick();
                return true;

            case MotionEvent.ACTION_CANCEL:
                settle(pos > 0.5f ? 1 : 0);
                dragging = false;
                horizontal = false;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                return true;
        }
        return super.onTouchEvent(e);
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    /** 吸附到某一侧，位置变了才回调 */
    private void settle(final int target) {
        final boolean changed = target != index;
        index = target;
        if (animator != null) animator.cancel();
        animator = ValueAnimator.ofFloat(pos, target);
        animator.setDuration(160);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(a -> {
            pos = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
        if (changed && listener != null) listener.onPick(target);
    }

    // ------------------------------------------------------------ 小工具

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private int dim(int attrRes) {
        TypedValue tv = new TypedValue();
        getContext().getTheme().resolveAttribute(attrRes, tv, true);
        return (int) tv.getDimension(getResources().getDisplayMetrics());
    }

    private int color(int attrRes) {
        TypedValue tv = new TypedValue();
        getContext().getTheme().resolveAttribute(attrRes, tv, true);
        if (tv.resourceId != 0) return getContext().getColor(tv.resourceId);
        return tv.data;
    }
}
