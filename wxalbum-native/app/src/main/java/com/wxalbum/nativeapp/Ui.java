package com.wxalbum.nativeapp;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 轻量 UI 工具：统一从主题取色、取字号，少写 XML。 */
public final class Ui {

    private Ui() {
    }

    public static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    public static int color(Context c, int attr) {
        TypedValue tv = new TypedValue();
        if (c.getTheme().resolveAttribute(attr, tv, true)) {
            if (tv.resourceId != 0) return c.getColor(tv.resourceId);
            return tv.data;
        }
        return 0xFF888888;
    }

    public static float dim(Context c, int attr, float fallbackSp) {
        TypedValue tv = new TypedValue();
        if (c.getTheme().resolveAttribute(attr, tv, true)) {
            return tv.getDimension(c.getResources().getDisplayMetrics());
        }
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, fallbackSp,
                c.getResources().getDisplayMetrics());
    }

    public static boolean mono(Context c) {
        TypedValue tv = new TypedValue();
        return c.getTheme().resolveAttribute(R.attr.monoMode, tv, true) && tv.data != 0;
    }

    public static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    public static TextView text(Context c, String s, float sizePx, int colorAttr, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx);
        t.setTextColor(color(c, colorAttr));
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLineSpacing(0, 1.15f);
        return t;
    }

    public static TextView label(Context c, String s) {
        return text(c, s, dim(c, R.attr.dT2, 14), R.attr.cText2, false);
    }

    public static TextView section(Context c, String s) {
        return text(c, s, dim(c, R.attr.dT3, 15), R.attr.cText, true);
    }

    public static TextView title(Context c, String s) {
        return text(c, s, dim(c, R.attr.dT4, 21), R.attr.cText, true);
    }

    /** 卡片：圆角 20dp + 1px 描边。 */
    public static LinearLayout card(Context c) {
        LinearLayout l = column(c);
        l.setBackgroundResource(R.drawable.bg_card);
        int p = dp(c, 16);
        l.setPadding(p, p, p, p);
        return l;
    }

    public static TextView button(Context c, String s, boolean primary) {
        TextView b = text(c, s, dim(c, R.attr.dT2, 14), primary ? R.attr.cOnPrimary : R.attr.cText, true);
        b.setBackgroundResource(primary ? R.drawable.bg_primary : R.drawable.bg_ghost);
        b.setGravity(Gravity.CENTER);
        b.setClickable(true);
        b.setFocusable(true);
        b.setPadding(dp(c, 16), dp(c, 10), dp(c, 16), dp(c, 10));
        b.setMinHeight(dp(c, 44));
        return b;
    }

    public static TextView smallButton(Context c, String s) {
        TextView b = text(c, s, dim(c, R.attr.dT1, 11.5f), R.attr.cText2, false);
        b.setBackgroundResource(R.drawable.bg_ghost);
        b.setGravity(Gravity.CENTER);
        b.setClickable(true);
        b.setFocusable(true);
        b.setPadding(dp(c, 10), dp(c, 6), dp(c, 10), dp(c, 6));
        return b;
    }

    public static TextView dangerButton(Context c, String s) {
        TextView b = text(c, s, dim(c, R.attr.dT1, 11.5f), R.attr.cDanger, false);
        b.setBackgroundResource(R.drawable.bg_danger);
        b.setGravity(Gravity.CENTER);
        b.setClickable(true);
        b.setFocusable(true);
        b.setPadding(dp(c, 10), dp(c, 6), dp(c, 10), dp(c, 6));
        return b;
    }

    public static TextView chip(Context c, String s, int colorAttr, int bgColor) {
        TextView t = text(c, s, dim(c, R.attr.dT1, 11.5f), colorAttr, false);
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(c, 100));
        d.setColor(bgColor);
        t.setBackground(d);
        t.setPadding(dp(c, 9), dp(c, 3), dp(c, 9), dp(c, 3));
        return t;
    }

    public static View gap(Context c, int h) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(c, h)));
        return v;
    }

    public static View hgap(Context c, int w) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(c, w), 1));
        return v;
    }

    public static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    public static LinearLayout.LayoutParams lpWeight(float weight) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.weight = weight;
        return p;
    }

    public static void margin(View v, int l, int t, int r, int b) {
        ViewGroup.LayoutParams raw = v.getLayoutParams();
        if (raw instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) raw;
            p.setMargins(dp(v.getContext(), l), dp(v.getContext(), t),
                    dp(v.getContext(), r), dp(v.getContext(), b));
            v.setLayoutParams(p);
        }
    }

    public static void pad(View v, int l, int t, int r, int b) {
        v.setPadding(dp(v.getContext(), l), dp(v.getContext(), t),
                dp(v.getContext(), r), dp(v.getContext(), b));
    }

    public static void divider(Context c, LinearLayout parent) {
        View v = new View(c);
        v.setBackgroundColor(color(c, R.attr.cLine));
        parent.addView(v, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 0.7f))));
    }
}
