package com.wxalbum.nativeapp;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/** 主界面：底部四导航（下载 / 书架 / 历史 / 设置），界面全部由 Ui 构建。 */
public class MainActivity extends Activity {

    private static final int REQ_TREE = 41;
    private static final int REQ_WRITE = 42;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Stores.Prefs prefs;
    private int tab = 0;
    private String lastRefreshSig = "";   // 墨水屏空闲时跳过无变化的整屏重绘

    private TextView pageTitle, pageSub, phaseChip;
    private FrameLayout content;
    private final LinearLayout[] tabViews = new LinearLayout[4];
    private final ImageView[] tabIcons = new ImageView[4];
    private final TextView[] tabLabels = new TextView[4];

    private View dlScreen, shelfScreen, histScreen, setScreen;

    /* 下载页 */
    private EditText inUrl, inTitle, inAuthor, inDelay;
    private CheckBox cbExpand, cbImages, cbBookinfo, cbNumbering, cbCache;
    private TextView btnTitleMode;
    private int titleModePos = 0;
    private static final String[] TITLE_MODES = {"自动识别", "正文第一行", "文章标题"};
    private LinearLayout albumCard, listCard, listBox, actionCard;
    private ImageView coverView;
    private TextView albumHint, listCount, abMain, abSub, logView, progText;
    private ProgressBar prog;
    private TextView btnParse, btnBuild, btnCancel, btnPaste;
    private final LinkedHashSet<String> selected = new LinkedHashSet<>();
    private final HashMap<String, TextView> statusViews = new HashMap<>();
    private final HashMap<String, TextView> charViews = new HashMap<>();
    private String listSig = "";
    private String albumSig = "";
    private int lastDoneSig = -1;

    /* 书架 */
    private LinearLayout shelfBox;
    private TextView shelfHint, btnCheck;
    private String shelfSig = "";

    /* 历史 */
    private LinearLayout histBox;
    private TextView histInfo;
    private final LinkedHashSet<String> histSel = new LinkedHashSet<>();
    private String histSig = "";

    /* 设置 */
    private TextView setDir, setConc, setCache;
    private LinearLayout themeRow;

    private final HashMap<String, Bitmap> bmpCache = new HashMap<>();

    /* ==================== 生命周期 ==================== */

    @Override
    protected void onCreate(Bundle b) {
        prefs = Stores.prefs(this);
        setTheme(themeRes(prefs.theme));
        super.onCreate(b);
        setContentView(R.layout.activity_main);

        pageTitle = findViewById(R.id.pageTitle);
        phaseChip = findViewById(R.id.phaseChip);
        pageSub = findViewById(R.id.pageSub);
        phaseChip.setSingleLine(true);
        phaseChip.setEllipsize(android.text.TextUtils.TruncateAt.END);
        phaseChip.setMaxWidth(dp(Ui.eink6(this) ? 260 : 132));
        if (Ui.eink6(this)) phaseChip.setBackgroundResource(R.drawable.bg_chip_e6);
        content = findViewById(R.id.content);

        tabViews[0] = findViewById(R.id.tabDownload);
        tabViews[1] = findViewById(R.id.tabShelf);
        tabViews[2] = findViewById(R.id.tabHistory);
        tabViews[3] = findViewById(R.id.tabSettings);

        tabIcons[0] = findViewById(R.id.icoDownload);
        tabIcons[1] = findViewById(R.id.icoShelf);
        tabIcons[2] = findViewById(R.id.icoHistory);
        tabIcons[3] = findViewById(R.id.icoSettings);

        tabLabels[0] = findViewById(R.id.lblDownload);
        tabLabels[1] = findViewById(R.id.lblShelf);
        tabLabels[2] = findViewById(R.id.lblHistory);
        tabLabels[3] = findViewById(R.id.lblSettings);

        for (int i = 0; i < 4; i++) {
            final int idx = i;
            tabViews[i].setOnClickListener(v -> switchTab(idx));
        }

        dlScreen = buildDownload();
        shelfScreen = buildShelf();
        histScreen = buildHistory();
        setScreen = buildSettings();

        Engine.I.loadStores(this);
        new Thread(() -> {
            int fixed = Engine.recoverOrphans(this);
            if (fixed > 0) {
                ui.post(() -> toast("已还原上次中断留下的旧版本 " + fixed + " 本"));
            }
        }, "recover").start();
        // 从系统目录选择器返回时 Activity 可能被重建，恢复到原来的标签页，
        // 否则会跳回下载页，看起来像「设置界面不显示了」
        int savedTab = getSharedPreferences("wxalbum", MODE_PRIVATE).getInt("lastTab", 0);
        switchTab(savedTab >= 0 && savedTab <= 3 ? savedTab : 0);
        refresh();
        ui.postDelayed(ticker, tickMs());
    }

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refresh();
            ui.postDelayed(this, tickMs());
        }
    };

    /** 墨水屏刷新慢、怕残影：轮询从 600ms 放慢到 1500ms。 */
    private int tickMs() {
        return Ui.eink6(this) ? 1500 : 600;
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从系统目录选择器返回（或 Activity 被回收重建）后重新读盘，保证设置页显示最新位置
        prefs = Stores.prefs(this);
        updateSettings();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (tab != 0) {
            switchTab(0);
        } else {
            super.onBackPressed();
        }
    }

    private int themeRes(int mode) {
        if (mode == Stores.THEME_DARK) return R.style.AppTheme_Dark;
        if (mode == Stores.THEME_LIGHT) return R.style.AppTheme_Light;
        if (mode == Stores.THEME_EINK6) return R.style.AppTheme_Eink6;
        if (mode == Stores.THEME_MONO) {
            // 6 英寸 300dpi 设备上，普通「墨水屏」自动升级成专用样式
            return eink6Hardware() ? R.style.AppTheme_Eink6 : R.style.AppTheme_Mono;
        }
        boolean night = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        return night ? R.style.AppTheme_Dark : R.style.AppTheme_Light;
    }

    /** 按物理对角尺寸 + dpi 识别 6 英寸级墨水屏；400dpi 以上的普通手机不会误判。 */
    private boolean eink6Hardware() {
        DisplayMetrics m = getResources().getDisplayMetrics();
        return Ui.isEink6Screen(m.widthPixels, m.heightPixels, m.xdpi, m.ydpi, m.densityDpi);
    }

    /* ==================== 导航 ==================== */

    private void switchTab(int idx) {
        tab = idx;
        getSharedPreferences("wxalbum", MODE_PRIVATE).edit().putInt("lastTab", idx).apply();
        content.removeAllViews();
        View v = idx == 0 ? dlScreen : idx == 1 ? shelfScreen : idx == 2 ? histScreen : setScreen;
        content.addView(v, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        String[] titles = {"下载", "书架", "历史", "设置"};
        String[] subs = {
                getString(R.string.sub_download), getString(R.string.sub_shelf),
                getString(R.string.sub_history), getString(R.string.sub_settings)
        };
        pageTitle.setText(titles[idx]);
        pageSub.setText(subs[idx]);
        updateTabs();
        if (idx == 3) {
            updateSettings();
            updateCacheInfo();
        }
        refresh();
    }

    private void updateTabs() {
        // 墨水屏只有黑白：选中态用反白黑块，不能靠深浅灰区分；顺手去掉 ripple 的半透明灰
        boolean bw = Ui.mono(this);
        int on = Ui.color(this, R.attr.cPrimary);
        int off = Ui.color(this, R.attr.cText3);
        for (int i = 0; i < 4; i++) {
            boolean active = i == tab;
            if (bw) {
                tabViews[i].setBackgroundColor(active ? 0xFF000000 : 0xFFFFFFFF);
                tabIcons[i].setColorFilter(active ? 0xFFFFFFFF : 0xFF000000);
                tabLabels[i].setTextColor(active ? 0xFFFFFFFF : 0xFF000000);
            } else {
                tabIcons[i].setColorFilter(active ? on : off);
                tabLabels[i].setTextColor(active ? on : off);
            }
            tabLabels[i].setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
        }
    }

    /* ==================== 通用小组件 ==================== */

    private int dp(float v) {
        return Ui.dp(this, v);
    }

    /** 禁用态：普通主题用半透明；墨水屏只有黑白，改用纯黑删除线，绝不产生灰阶。 */
    private void dimButton(View v, boolean enabled) {
        if (Ui.mono(this)) {
            v.setAlpha(1f);
            if (v instanceof TextView) {
                TextView t = (TextView) v;
                int flags = t.getPaint().getFlags();
                t.getPaint().setFlags(enabled
                        ? (flags & ~Paint.STRIKE_THRU_TEXT_FLAG)
                        : (flags | Paint.STRIKE_THRU_TEXT_FLAG));
                t.invalidate();
            }
        } else {
            v.setAlpha(enabled ? 1f : 0.45f);
        }
    }

    private ScrollView scroll(LinearLayout box) {
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(false);
        sv.setClipToPadding(false);
        sv.setVerticalScrollBarEnabled(false);
        box.setPadding(dp(18), dp(2), dp(18), dp(28));
        sv.addView(box, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return sv;
    }

    private LinearLayout header(String title, String hint) {
        LinearLayout r = Ui.row(this);
        r.addView(Ui.section(this, title));
        TextView h = Ui.text(this, hint, Ui.dim(this, R.attr.dT1, 11.5f), R.attr.cText3, false);
        h.setGravity(Gravity.END);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        r.addView(h, p);
        r.setPadding(0, 0, 0, dp(10));
        return r;
    }

    private EditText edit(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ui.dim(this, R.attr.dT2, 14));
        e.setTextColor(Ui.color(this, R.attr.cText));
        e.setHintTextColor(Ui.color(this, R.attr.cText3));
        e.setBackgroundResource(Ui.eink6(this) ? R.drawable.bg_input_e6 : R.drawable.bg_input);
        e.setPadding(dp(12), dp(8), dp(12), dp(8));
        e.setMinHeight(dp(46));
        return e;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    /* ==================== 下载页 ==================== */

    private View buildDownload() {
        LinearLayout box = Ui.column(this);

        // 链接卡
        LinearLayout c1 = Ui.card(this);
        c1.addView(header("链接", "合集 / 单篇文章"));
        inUrl = edit(getString(R.string.hint_url));
        inUrl.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        c1.addView(inUrl);

        LinearLayout r1 = Ui.row(this);
        r1.setPadding(0, dp(10), 0, 0);
        btnPaste = Ui.button(this, "粘贴", false);
        btnParse = Ui.button(this, "解析", true);
        LinearLayout.LayoutParams pw = new LinearLayout.LayoutParams(dp(76), ViewGroup.LayoutParams.WRAP_CONTENT);
        r1.addView(btnPaste, pw);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        pp.leftMargin = dp(8);
        r1.addView(btnParse, pp);
        c1.addView(r1);

        LinearLayout r2 = Ui.row(this);
        r2.setPadding(0, dp(12), 0, 0);
        cbExpand = check("自动反查合集", true);
        cbCache = check("使用缓存", true);
        r2.addView(cbExpand);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.leftMargin = dp(14);
        r2.addView(cbCache, cp);
        c1.addView(r2);
        box.addView(c1, cardLp(0));

        // 书籍信息卡
        albumCard = Ui.card(this);
        albumCard.setVisibility(View.GONE);
        albumCard.addView(header("书籍信息", ""));
        LinearLayout ar = Ui.row(this);
        ar.setGravity(Gravity.TOP);
        coverView = new ImageView(this);
        coverView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable cbg = new GradientDrawable();
        cbg.setColor(Ui.color(this, R.attr.cSurface2));
        cbg.setCornerRadius(dp(10));
        coverView.setBackground(cbg);
        coverView.setClipToOutline(true);
        ar.addView(coverView, new LinearLayout.LayoutParams(dp(78), dp(106)));

        LinearLayout af = Ui.column(this);
        LinearLayout.LayoutParams afp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        afp.leftMargin = dp(12);
        ar.addView(af, afp);
        albumHint = Ui.text(this, "", Ui.dim(this, R.attr.dT1, 11.5f), R.attr.cText3, false);
        af.addView(albumHint);
        inTitle = edit("书名");
        inAuthor = edit("作者");
        af.addView(inTitle);
        af.addView(inAuthor, lpTop(dp(6)));
        albumCard.addView(ar, lpTop(dp(2)));
        box.addView(albumCard, cardLp(10));

        // 选项卡：拆成三行，避免窄屏把「标题序号」挤成竖排
        LinearLayout c3 = Ui.card(this);
        c3.addView(header("选项", "默认不带插图"));

        LinearLayout o1 = Ui.row(this);
        cbImages = check("下载插图", prefs.images);
        cbBookinfo = check("书籍信息页", prefs.bookinfo);
        o1.addView(cbImages);
        o1.addView(cbBookinfo, lpLeft(dp(16)));
        c3.addView(o1);

        LinearLayout o2 = Ui.row(this);
        o2.setPadding(0, dp(8), 0, 0);
        cbNumbering = check("标题序号", true);
        o2.addView(cbNumbering);
        LinearLayout.LayoutParams spring = new LinearLayout.LayoutParams(0, 1, 1f);
        o2.addView(new View(this), spring);
        o2.addView(Ui.text(this, "间隔", Ui.dim(this, R.attr.dT1, 11.5f), R.attr.cText2, false));
        inDelay = edit("0.5");
        inDelay.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        inDelay.setText("0.5");
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(56), ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.leftMargin = dp(6);
        o2.addView(inDelay, dlp);
        o2.addView(Ui.text(this, "秒", Ui.dim(this, R.attr.dT1, 11.5f), R.attr.cText3, false), lpLeft(dp(4)));
        c3.addView(o2);

        LinearLayout o3 = Ui.row(this);
        o3.setPadding(0, dp(10), 0, 0);
        o3.addView(Ui.text(this, "标题", Ui.dim(this, R.attr.dT1, 11.5f), R.attr.cText2, false));
        btnTitleMode = Ui.text(this, TITLE_MODES[titleModePos],
                Ui.dim(this, R.attr.dT1, 11.5f), R.attr.cText, false);
        btnTitleMode.setBackgroundResource(Ui.eink6(this) ? R.drawable.bg_input_e6 : R.drawable.bg_input);
        btnTitleMode.setGravity(Gravity.CENTER);
        btnTitleMode.setClickable(true);
        btnTitleMode.setFocusable(true);
        btnTitleMode.setPadding(dp(10), dp(8), dp(10), dp(8));
        btnTitleMode.setOnClickListener(v -> {
            titleModePos = (titleModePos + 1) % TITLE_MODES.length;
            btnTitleMode.setText(TITLE_MODES[titleModePos]);
        });
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(8);
        o3.addView(btnTitleMode, tlp);
        c3.addView(o3);
        box.addView(c3, cardLp(10));

        // 章节卡
        listCard = Ui.card(this);
        listCard.setVisibility(View.GONE);
        LinearLayout lh = Ui.row(this);
        lh.addView(Ui.section(this, "章节"));
        listCount = Ui.chip(this, "0", R.attr.cText2, Ui.color(this, R.attr.cSurface2));
        listCount.setPadding(dp(8), dp(1), dp(8), dp(1));
        LinearLayout.LayoutParams lcp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lcp.leftMargin = dp(8);
        lh.addView(listCount, lcp);
        LinearLayout.LayoutParams lhp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lh.addView(new View(this), lhp);
        listCard.addView(lh);

        LinearLayout tools = Ui.row(this);
        tools.setPadding(0, 0, 0, dp(6));
        String[] names = {"全选", "全不选", "反选", "仅本文", "仅失败"};
        for (int i = 0; i < names.length; i++) {
            final String mode = names[i];
            TextView b = Ui.smallButton(this, mode);
            b.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ui.dim(this, R.attr.dT0, 10));
            b.setOnClickListener(v -> selectMode(mode));
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) bp.leftMargin = dp(6);
            tools.addView(b, bp);
        }
        listCard.addView(tools);
        listBox = Ui.column(this);
        listCard.addView(listBox);
        box.addView(listCard, cardLp(10));

        // 进度卡
        actionCard = Ui.card(this);
        abMain = Ui.section(this, "等待解析");
        abSub = Ui.text(this, "解析后勾选章节即可生成", Ui.dim(this, R.attr.dT1, 11.5f), R.attr.cText3, false);
        actionCard.addView(abMain);
        actionCard.addView(abSub);

        prog = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        prog.setProgressDrawable(getDrawable(
                Ui.eink6(this) ? R.drawable.progress_e6 : R.drawable.progress_dl));
        prog.setMax(100);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(6));
        plp.topMargin = dp(10);
        actionCard.addView(prog, plp);
        progText = Ui.text(this, "0 / 0", Ui.dim(this, R.attr.dT1, 11.5f), R.attr.cText3, false);
        progText.setPadding(0, dp(4), 0, 0);
        actionCard.addView(progText);

        LinearLayout br = Ui.row(this);
        br.setPadding(0, dp(10), 0, 0);
        btnCancel = Ui.button(this, "取消", false);
        btnBuild = Ui.button(this, "开始生成", true);
        br.addView(btnCancel, new LinearLayout.LayoutParams(dp(88), ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        blp.leftMargin = dp(8);
        br.addView(btnBuild, blp);
        actionCard.addView(br);
        box.addView(actionCard, cardLp(10));

        // 日志卡
        LinearLayout c6 = Ui.card(this);
        c6.addView(header("日志", "最近 8 条"));
        logView = Ui.text(this, "—", Ui.dim(this, R.attr.dT0, 10), R.attr.cText3, false);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setLineSpacing(dp(2), 1.15f);
        c6.addView(logView);
        box.addView(c6, cardLp(10));

        btnParse.setOnClickListener(v -> doParse());
        btnPaste.setOnClickListener(v -> paste());
        btnBuild.setOnClickListener(v -> doBuild());
        btnCancel.setOnClickListener(v -> {
            Engine.I.cancel();
            toast("已请求取消");
        });
        return scroll(box);
    }

    private LinearLayout.LayoutParams cardLp(int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(top);
        return p;
    }

    private LinearLayout.LayoutParams lpTop(int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(top);
        return p;
    }

    private LinearLayout.LayoutParams lpLeft(int left) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.leftMargin = dp(left);
        return p;
    }

    private CheckBox check(String text, boolean on) {
        CheckBox c = new CheckBox(this);
        c.setText(text);
        c.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ui.dim(this, R.attr.dT1, 11.5f));
        c.setTextColor(Ui.color(this, R.attr.cText2));
        c.setChecked(on);
        c.setButtonTintList(ColorStateList.valueOf(Ui.color(this, R.attr.cPrimary)));
        c.setPadding(0, dp(2), 0, dp(2));
        return c;
    }

    private void paste() {
        android.content.ClipboardManager cm =
                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip() != null
                && cm.getPrimaryClip().getItemCount() > 0) {
            CharSequence s = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
            if (s != null && s.length() > 0) {
                inUrl.setText(s.toString().trim());
                toast("已粘贴");
                return;
            }
        }
        toast("剪贴板为空");
    }

    private void doParse() {
        String v = inUrl.getText().toString().trim();
        if (v.isEmpty()) {
            toast("请先粘贴链接");
            return;
        }
        hideKeyboard();
        listSig = "";
        albumSig = "";
        Engine.I.parse(this, v, cbExpand.isChecked(), (ok, msg) -> {
            toast(msg);
            if (ok) {
                albumSig = "";
                refresh();
            }
        });
        refresh();
    }

    private void hideKeyboard() {
        View v = getCurrentFocus();
        if (v != null) {
            android.view.inputmethod.InputMethodManager im =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (im != null) im.hideSoftInputFromWindow(v.getWindowToken(), 0);
        }
    }

    private Engine.Opts currentOpts() {
        Engine.Opts o = new Engine.Opts();
        o.images = cbImages.isChecked();
        o.bookinfo = cbBookinfo.isChecked();
        o.numbering = cbNumbering.isChecked();
        o.titlemode = titleModePos == 1 ? "first" : titleModePos == 2 ? "article" : "auto";
        o.title = inTitle.getText().toString().trim();
        o.author = inAuthor.getText().toString().trim();
        try {
            o.delayMs = (long) (Float.parseFloat(inDelay.getText().toString().trim()) * 1000);
        } catch (Exception e) {
            o.delayMs = 500;
        }
        o.concurrency = prefs.concurrency;
        return o;
    }

    private void doBuild() {
        if (selected.isEmpty()) {
            toast("请至少选择一篇");
            return;
        }
        Engine.I.build(this, new LinkedHashSet<>(selected), currentOpts(), (ok, msg) -> {
            toast(msg);
            if (ok) exportResult();
            else if (Engine.I.phase.equals("cancelled")) toast("已取消");
        });
        refresh();
    }

    private void selectMode(String mode) {
        List<Model.Article> arts = Engine.I.articles;
        if ("全选".equals(mode)) {
            for (Model.Article a : arts) selected.add(a.key());
        } else if ("全不选".equals(mode)) {
            selected.clear();
        } else if ("反选".equals(mode)) {
            for (Model.Article a : arts) {
                if (selected.contains(a.key())) selected.remove(a.key());
                else selected.add(a.key());
            }
        } else if ("仅本文".equals(mode)) {
            selected.clear();
            for (Model.Article a : arts) if (a.current) selected.add(a.key());
        } else if ("仅失败".equals(mode)) {
            selected.clear();
            for (Model.Article a : arts) if ("fail".equals(a.status)) selected.add(a.key());
        }
        syncChecks();
        updateBuildButton();
    }

    private void syncChecks() {
        for (int i = 0; i < listBox.getChildCount(); i++) {
            View row = listBox.getChildAt(i);
            if (!(row instanceof LinearLayout)) continue;
            View first = ((LinearLayout) row).getChildAt(0);
            if (first instanceof CheckBox) {
                CheckBox cb = (CheckBox) first;
                Object tag = cb.getTag();
                if (tag != null) cb.setChecked(selected.contains(tag.toString()));
            }
        }
    }

    /* ==================== 下载页刷新 ==================== */

    private void refreshAlbum() {
        Model.Album a = Engine.I.album;
        if (a == null) {
            albumCard.setVisibility(View.GONE);
            return;
        }
        albumCard.setVisibility(View.VISIBLE);
        String sig = a.albumId + "|" + a.title + "|" + a.author;
        if (!sig.equals(albumSig)) {
            albumSig = sig;
            inTitle.setText(a.title);
            inAuthor.setText(a.author);
            albumHint.setText(a.albumId.equals("single") || a.albumId.isEmpty()
                    ? "单篇文章" : "合集 ID " + a.albumId);
            loadCover(a.cover, coverView);
        }
    }

    private void refreshList(boolean force) {
        List<Model.Article> arts = Engine.I.articles;
        if (arts.isEmpty()) {
            listCard.setVisibility(View.GONE);
            return;
        }
        listCard.setVisibility(View.VISIBLE);
        String sig = (Engine.I.album == null ? "" : Engine.I.album.albumId) + "#" + arts.size()
                + "#" + (arts.isEmpty() ? "" : arts.get(0).msgid);
        if (force || !sig.equals(listSig)) {
            listSig = sig;
            selected.clear();
            for (Model.Article a : arts) selected.add(a.key());
            listBox.removeAllViews();
            statusViews.clear();
            charViews.clear();
            for (int i = 0; i < arts.size(); i++) addArticleRow(arts.get(i), i);
            listCount.setText(arts.size() + " 篇");
            lastDoneSig = -1;
        }
        int dsig = Engine.I.done * 131 + Engine.I.fail * 17 + Engine.I.total * 7;
        if (dsig != lastDoneSig) {
            lastDoneSig = dsig;
            for (Model.Article a : arts) {
                TextView st = statusViews.get(a.key());
                TextView ch = charViews.get(a.key());
                if (st != null) {
                    if ("done".equals(a.status)) {
                        st.setText("完成");
                        st.setTextColor(Ui.color(this, R.attr.cPrimary));
                    } else if ("fail".equals(a.status)) {
                        st.setText("失败");
                        st.setTextColor(Ui.color(this, R.attr.cDanger));
                    } else if ("fetching".equals(a.status)) {
                        st.setText("抓取中");
                        st.setTextColor(Ui.color(this, R.attr.cAccent));
                    } else {
                        st.setText("待抓取");
                        st.setTextColor(Ui.color(this, R.attr.cText3));
                    }
                }
                if (ch != null) ch.setText(a.chars > 0 ? Model.fmtChars(a.chars) : "");
            }
        }
        updateBuildButton();
    }

    private void addArticleRow(Model.Article a, int i) {
        LinearLayout row = Ui.row(this);
        row.setPadding(dp(2), dp(6), dp(2), dp(6));
        CheckBox cb = new CheckBox(this);
        cb.setButtonTintList(ColorStateList.valueOf(Ui.color(this, R.attr.cPrimary)));
        cb.setChecked(selected.contains(a.key()));
        cb.setTag(a.key());
        cb.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) selected.add(a.key());
            else selected.remove(a.key());
            updateBuildButton();
        });
        row.addView(cb, new LinearLayout.LayoutParams(dp(34), ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView no = Ui.text(this, String.valueOf(i + 1),
                Ui.dim(this, R.attr.dT0, 10), R.attr.cText3, false);
        no.setGravity(Gravity.END);
        row.addView(no, new LinearLayout.LayoutParams(dp(26), ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView tt = Ui.text(this, a.title, Ui.dim(this, R.attr.dT1, 11.5f), R.attr.cText, false);
        tt.setSingleLine(true);
        tt.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams ttp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        ttp.leftMargin = dp(6);
        row.addView(tt, ttp);

        if (a.current) {
            TextView tag = Ui.chip(this, "本文", R.attr.cPrimary, Ui.color(this, R.attr.cPrimarySoft));
            row.addView(tag, lpLeft(4));
        }

        TextView st = Ui.text(this, "待抓取", Ui.dim(this, R.attr.dT0, 10), R.attr.cText3, false);
        row.addView(st, lpLeft(6));
        TextView ch = Ui.text(this, "", Ui.dim(this, R.attr.dT0, 10), R.attr.cText3, false);
        ch.setGravity(Gravity.END);
        row.addView(ch, new LinearLayout.LayoutParams(dp(58), ViewGroup.LayoutParams.WRAP_CONTENT));

        statusViews.put(a.key(), st);
        charViews.put(a.key(), ch);
        listBox.addView(row);
    }

    private void updateBuildButton() {
        boolean busy = Engine.I.busy();
        btnBuild.setEnabled(!busy && !selected.isEmpty());
        dimButton(btnBuild, !busy && !selected.isEmpty());
        btnCancel.setEnabled(busy);
        dimButton(btnCancel, busy);
        btnParse.setEnabled(!busy);
        dimButton(btnParse, !busy);
        btnBuild.setText(busy ? "处理中…" : ("开始生成" + (selected.isEmpty() ? "" : "（" + selected.size() + "）")));
        if (Engine.I.album != null) {
            abMain.setText(selected.size() + " / " + Engine.I.articles.size() + " 篇已选");
            String t = inTitle.getText().toString().trim();
            abSub.setText("书名：" + (t.isEmpty() ? Engine.I.album.title : t));
        } else {
            abMain.setText("等待解析");
            abSub.setText("解析后勾选章节即可生成");
        }
    }

    private void refreshProgress() {
        boolean busy = Engine.I.busy();
        int total = Math.max(1, Engine.I.total);
        int done = Engine.I.done;
        prog.setProgress(busy ? Math.min(100, done * 100 / total) : (Engine.I.phase.equals("done") ? 100 : 0));
        String cur = Engine.I.current;
        if (busy) {
            progText.setText((cur.isEmpty() ? Engine.I.message : ("正在抓取：" + cur))
                    + "    " + done + " / " + Engine.I.total);
        } else if (Engine.I.phase.equals("done")) {
            progText.setText("完成 · " + Engine.I.resultName + " · " + Model.fmtSize(Engine.I.resultSize));
        } else {
            progText.setText(Engine.I.message);
        }
        List<String> logs = Engine.I.logs;
        StringBuilder sb = new StringBuilder();
        int from = Math.max(0, logs.size() - 8);
        synchronized (logs) {
            for (int i = from; i < logs.size(); i++) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(logs.get(i));
            }
        }
        logView.setText(sb.length() == 0 ? "—" : sb.toString());
    }

    private void loadCover(String url, ImageView iv) {
        if (url == null || url.isEmpty()) {
            iv.setImageDrawable(null);
            return;
        }
        Bitmap b = bmpCache.get(url);
        if (b != null) {
            iv.setImageBitmap(b);
            return;
        }
        if (url.equals(iv.getTag())) return;
        iv.setTag(url);
        new Thread(() -> {
            try {
                WxApi.Raw raw = WxApi.raw(url, "https://mp.weixin.qq.com/");
                final Bitmap bm = BitmapFactory.decodeByteArray(raw.data, 0, raw.data.length);
                if (bm != null) {
                    bmpCache.put(url, bm);
                    ui.post(() -> {
                        if (url.equals(iv.getTag())) iv.setImageBitmap(bm);
                    });
                }
            } catch (Exception ignored) {
            }
        }).start();
    }

    /* ==================== 书架 ==================== */

    private View buildShelf() {
        LinearLayout root = Ui.column(this);
        root.setPadding(dp(18), dp(2), dp(18), 0);

        LinearLayout c = Ui.card(this);
        LinearLayout h = Ui.row(this);
        h.addView(Ui.section(this, "书籍追踪"));
        h.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        btnCheck = Ui.smallButton(this, "检查更新");
        h.addView(btnCheck);
        c.addView(h);
        shelfHint = Ui.text(this, "生成过的合集会自动加入这里",
                Ui.dim(this, R.attr.dT1, 11.5f), R.attr.cText3, false);
        shelfHint.setPadding(0, dp(4), 0, 0);
        c.addView(shelfHint);
        root.addView(c, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView sv = new ScrollView(this);
        sv.setClipToPadding(false);
        sv.setVerticalScrollBarEnabled(false);
        sv.setPadding(0, dp(10), 0, 0);
        shelfBox = Ui.column(this);
        shelfBox.setPadding(0, 0, 0, dp(24));
        sv.addView(shelfBox, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        btnCheck.setOnClickListener(v -> {
            toast("正在检查更新…");
            Engine.I.checkWatch(this, (ok, msg) -> {
                toast(msg);
                shelfSig = "";
                refresh();
            });
            refresh();
        });
        return root;
    }

    private void renderShelf() {
        List<Stores.WatchItem> items = new java.util.ArrayList<>(Engine.I.watch);
        StringBuilder sig = new StringBuilder(Engine.I.checking ? "1" : "0");
        for (Stores.WatchItem w : items) {
            sig.append('|').append(w.albumId).append(',').append(w.count).append(',')
                    .append(w.newCount).append(',').append(w.error);
        }
        if (sig.toString().equals(shelfSig)) return;
        shelfSig = sig.toString();

        int fresh = 0;
        for (Stores.WatchItem w : items) if (w.newCount > 0) fresh += w.newCount;
        shelfHint.setText(Engine.I.checking ? "正在检查…"
                : (fresh > 0 ? ("发现 " + fresh + " 章更新") : "生成过的合集会自动加入这里"));
        btnCheck.setEnabled(!Engine.I.checking);
        dimButton(btnCheck, !Engine.I.checking);

        shelfBox.removeAllViews();
        if (items.isEmpty()) {
            TextView e = Ui.text(this, "书架还是空的\n生成一本合集后会自动出现在这里",
                    Ui.dim(this, R.attr.dT1, 11.5f), R.attr.cText3, false);
            e.setGravity(Gravity.CENTER);
            e.setPadding(dp(10), dp(48), dp(10), dp(48));
            shelfBox.addView(e);
            return;
        }
        for (Stores.WatchItem w : items) shelfBox.addView(bookCard(w));
    }

    private View bookCard(Stores.WatchItem w) {
        LinearLayout card = Ui.column(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Ui.color(this, R.attr.cSurface2));
        bg.setCornerRadius(dp(14));
        bg.setStroke(1, Ui.color(this, R.attr.cLine));
        card.setBackground(bg);
        card.setPadding(dp(12), dp(12), dp(12), dp(12));

        LinearLayout r = Ui.row(this);
        r.setGravity(Gravity.TOP);

        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable cb = new GradientDrawable();
        cb.setColor(Ui.color(this, R.attr.cSurface));
        cb.setCornerRadius(dp(8));
        iv.setBackground(cb);
        iv.setClipToOutline(true);
        r.addView(iv, new LinearLayout.LayoutParams(dp(54), dp(74)));
        loadCover(w.cover, iv);

        LinearLayout main = Ui.column(this);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        mp.leftMargin = dp(11);
        r.addView(main, mp);

        LinearLayout tr = Ui.row(this);
        TextView name = Ui.text(this, w.title, Ui.dim(this, R.attr.dT2, 14), R.attr.cText, true);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tr.addView(name, np);

        String badge;
        int badgeColor = R.attr.cText3;
        if (!w.error.isEmpty()) {
            badge = "检查失败";
            badgeColor = R.attr.cDanger;
        } else if (w.newCount > 0) {
            badge = "+" + w.newCount + " 章";
            badgeColor = R.attr.cPrimary;
        } else if (w.newCount == 0) {
            badge = "已最新";
            badgeColor = R.attr.cPrimary;
        } else {
            badge = "—";
        }
        tr.addView(Ui.chip(this, badge, badgeColor, Ui.color(this, R.attr.cSurface)), lpLeft(6));
        main.addView(tr);

        StringBuilder sub = new StringBuilder();
        sub.append(w.count).append(" 篇");
        if (!w.lastBuild.isEmpty()) sub.append(" · 生成于 ").append(w.lastBuild);
        if (!w.lastCheck.isEmpty()) sub.append("\n检查于 ").append(w.lastCheck);
        if (!w.error.isEmpty()) sub.append("\n").append(w.error);
        TextView st = Ui.text(this, sub.toString(), Ui.dim(this, R.attr.dT0, 10), R.attr.cText3, false);
        st.setPadding(0, dp(3), 0, 0);
        main.addView(st);

        LinearLayout acts = Ui.row(this);
        acts.setPadding(0, dp(8), 0, 0);
        TextView up = Ui.smallButton(this, "更新");
        TextView rm = Ui.smallButton(this, "移除");
        acts.addView(up);
        acts.addView(rm, lpLeft(6));
        main.addView(acts);

        up.setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle("更新《" + w.title + "》")
                    .setMessage("重新抓取新增章节（旧章节走缓存），校验通过后原位替换原文件；\n"
                            + "生成失败会自动保留旧版本，成功则不保留旧版。继续？")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("更新", (d, x) -> {
                        switchTab(0);
                        Engine.I.updateWatch(this, w.albumId, (ok, msg) -> {
                            toast(msg);
                            if (ok) exportResult();
                            else toast(msg);
                        });
                    }).show();
        });
        rm.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("从书架移除？")
                .setMessage("只移除追踪条目，不会删除已生成的 EPUB。")
                .setNegativeButton("取消", null)
                .setPositiveButton("移除", (d, x) -> {
                    Stores.removeWatch(this, w.albumId);
                    Engine.I.refreshWatch(this);
                    shelfSig = "";
                    refresh();
                }).show());

        card.addView(r);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.topMargin = dp(8);
        card.setLayoutParams(cardLp);
        return card;
    }

    /* ==================== 历史 ==================== */

    private View buildHistory() {
        LinearLayout root = Ui.column(this);
        root.setPadding(dp(18), dp(2), dp(18), 0);

        LinearLayout c = Ui.card(this);
        LinearLayout h = Ui.row(this);
        h.addView(Ui.section(this, "生成历史"));
        h.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        TextView refreshBtn = Ui.smallButton(this, "刷新");
        refreshBtn.setOnClickListener(v -> {
            Engine.I.loadStores(this);
            histSig = "";
            refresh();
        });
        h.addView(refreshBtn);
        c.addView(h);

        histInfo = Ui.text(this, "共 0 条记录", Ui.dim(this, R.attr.dT1, 11.5f), R.attr.cText3, false);
        histInfo.setPadding(0, dp(4), 0, dp(8));
        c.addView(histInfo);

        // 按钮横排，放不下就横向滚动，不会被挤压换行
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout tools = Ui.row(this);
        String[] names = {"全选", "清空", "删记录", "删文件", "文件+记录"};
        for (int i = 0; i < names.length; i++) {
            final String mode = names[i];
            TextView b;
            if (mode.startsWith("删") || mode.startsWith("文件")) b = Ui.dangerButton(this, mode);
            else b = Ui.smallButton(this, mode);
            b.setOnClickListener(v -> historyAction(mode));
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) bp.leftMargin = dp(6);
            tools.addView(b, bp);
        }
        hs.addView(tools, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        c.addView(hs, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(c, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView sv = new ScrollView(this);
        sv.setClipToPadding(false);
        sv.setVerticalScrollBarEnabled(false);
        sv.setPadding(0, dp(10), 0, 0);
        histBox = Ui.column(this);
        histBox.setPadding(0, 0, 0, dp(24));
        sv.addView(histBox, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    private void historyAction(String mode) {
        List<Stores.HistItem> items = new java.util.ArrayList<>(Engine.I.history);
        if ("全选".equals(mode)) {
            histSel.clear();
            for (Stores.HistItem h : items) histSel.add(h.id);
            histSig = "";
            renderHistory();
            return;
        }
        if ("清空".equals(mode)) {
            histSel.clear();
            histSig = "";
            renderHistory();
            return;
        }
        if (histSel.isEmpty()) {
            toast("请先勾选记录");
            return;
        }
        String tip;
        String kind;
        if ("删记录".equals(mode)) {
            tip = "只删除记录，EPUB 文件保留。";
            kind = "record";
        } else if ("删文件".equals(mode)) {
            tip = "只删除 EPUB 文件，记录保留并标记为已删除。";
            kind = "file";
        } else {
            tip = "删除文件同时删除记录，不可恢复。";
            kind = "both";
        }
        new AlertDialog.Builder(this)
                .setTitle("确认删除 " + histSel.size() + " 条？")
                .setMessage(tip)
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (d, x) -> {
                    Stores.historyDelete(this, new ArrayList<>(histSel), kind);
                    histSel.clear();
                    Engine.I.loadStores(this);
                    histSig = "";
                    refresh();
                    toast("已删除");
                }).show();
    }

    private void renderHistory() {
        List<Stores.HistItem> items = new java.util.ArrayList<>(Engine.I.history);
        StringBuilder sig = new StringBuilder();
        sig.append(items.size()).append('/').append(histSel.size());
        for (Stores.HistItem h : items) {
            sig.append('|').append(h.id).append(',').append(h.exists ? 1 : 0).append(',').append(h.uri.isEmpty() ? 0 : 1);
        }
        if (sig.toString().equals(histSig)) return;
        histSig = sig.toString();

        histInfo.setText(histSel.isEmpty()
                ? ("共 " + items.size() + " 条记录")
                : ("已选 " + histSel.size() + " / " + items.size() + " 条"));
        histBox.removeAllViews();
        if (items.isEmpty()) {
            TextView e = Ui.text(this, "还没有生成记录\n生成一本书后会在这里留一条",
                    Ui.dim(this, R.attr.dT1, 11.5f), R.attr.cText3, false);
            e.setGravity(Gravity.CENTER);
            e.setPadding(dp(10), dp(48), dp(10), dp(48));
            histBox.addView(e);
            return;
        }
        for (Stores.HistItem h : items) histBox.addView(historyRow(h));
    }

    private View historyRow(Stores.HistItem h) {
        LinearLayout row = Ui.row(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Ui.color(this, R.attr.cSurface2));
        bg.setCornerRadius(dp(13));
        bg.setStroke(1, histSel.contains(h.id)
                ? Ui.color(this, R.attr.cPrimaryLine) : Ui.color(this, R.attr.cLine));
        row.setBackground(bg);
        row.setPadding(dp(10), dp(10), dp(10), dp(10));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.topMargin = dp(8);
        row.setLayoutParams(rp);

        CheckBox cb = new CheckBox(this);
        cb.setChecked(histSel.contains(h.id));
        cb.setButtonTintList(ColorStateList.valueOf(Ui.color(this, R.attr.cPrimary)));
        cb.setOnCheckedChangeListener((v, on) -> {
            if (on) histSel.add(h.id);
            else histSel.remove(h.id);
            histInfo.setText(histSel.isEmpty()
                    ? ("共 " + Engine.I.history.size() + " 条记录")
                    : ("已选 " + histSel.size() + " / " + Engine.I.history.size() + " 条"));
        });
        row.addView(cb, new LinearLayout.LayoutParams(dp(32), ViewGroup.LayoutParams.WRAP_CONTENT));

        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable cbg = new GradientDrawable();
        cbg.setColor(Ui.color(this, R.attr.cSurface));
        cbg.setCornerRadius(dp(7));
        iv.setBackground(cbg);
        iv.setClipToOutline(true);
        row.addView(iv, new LinearLayout.LayoutParams(dp(40), dp(54)));
        loadCover(h.cover, iv);

        LinearLayout main = Ui.column(this);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        mp.leftMargin = dp(10);
        row.addView(main, mp);

        LinearLayout tr = Ui.row(this);
        TextView name = Ui.text(this, h.title, Ui.dim(this, R.attr.dT1, 11.5f), R.attr.cText, true);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tr.addView(name, np);
        tr.addView(Ui.chip(this, h.mode, R.attr.cText2, Ui.color(this, R.attr.cSurface)), lpLeft(4));
        main.addView(tr);

        String sub = h.time + " · " + h.chapters + " 章 · " + Model.fmtChars(h.chars)
                + " · " + Model.fmtSize(h.size) + (h.exists ? "" : " · 文件已删除");
        TextView st = Ui.text(this, sub, Ui.dim(this, R.attr.dT0, 10), R.attr.cText3, false);
        main.addView(st);

        LinearLayout acts = Ui.row(this);
        acts.setPadding(0, dp(6), 0, 0);
        TextView open = Ui.smallButton(this, "打开");
        TextView save = Ui.smallButton(this, "保存");
        open.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ui.dim(this, R.attr.dT0, 10));
        save.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ui.dim(this, R.attr.dT0, 10));
        open.setEnabled(h.exists);
        dimButton(open, h.exists);
        open.setOnClickListener(v -> openEpub(h));
        save.setOnClickListener(v -> saveToPhone(h));
        acts.addView(open);
        acts.addView(save, lpLeft(6));
        main.addView(acts);

        row.setOnClickListener(v -> {
            cb.setChecked(!cb.isChecked());
            histSig = "";
            renderHistory();
        });
        return row;
    }

    private void openEpub(Stores.HistItem h) {
        try {
            if (!h.uri.isEmpty()) {
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(Uri.parse(h.uri), "application/epub+zip");
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(i);
                return;
            }
            File f = new File(h.path);
            if (!f.exists()) {
                toast("文件不存在，可能已被删除");
                return;
            }
            Exporter.Result r = Exporter.export(this, f, prefs.treeUri);
            if (r.uri != null) {
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(r.uri, "application/epub+zip");
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(i);
            } else {
                toast("已保存到 " + r.label);
            }
        } catch (Exception e) {
            toast("打开失败：" + e.getMessage());
        }
    }

    private void saveToPhone(Stores.HistItem h) {
        File f = new File(h.path);
        if (!f.exists()) {
            toast("文件不存在，可能已被删除");
            return;
        }
        try {
            Exporter.Result r = Exporter.export(this, f, prefs.treeUri);
            toast("已保存到 " + r.label);
            if (r.uri != null) {
                List<Stores.HistItem> hs = Stores.loadHistory(this);
                for (Stores.HistItem x : hs) if (x.id.equals(h.id)) x.uri = r.uri.toString();
                Stores.saveHistory(this, hs);
                Engine.I.loadStores(this);
                histSig = "";
            }
        } catch (Exception e) {
            toast("保存失败：" + e.getMessage());
        }
    }

    /* ==================== 设置 ==================== */

    private View buildSettings() {
        LinearLayout box = Ui.column(this);

        LinearLayout c1 = Ui.card(this);
        c1.addView(header("下载", "改动即时生效"));
        c1.addView(settingRow("保存位置", "生成后点「导出」写入这里", dirActions()));
        // 当前路径单独占一整行：以前挤在按钮旁只有 120dp，改完只显示「自定义目录」四个字
        setDir = Ui.text(this, "", Ui.dim(this, R.attr.dT1, 11.5f), R.attr.cText2, false);
        setDir.setMaxLines(3);
        setDir.setEllipsize(android.text.TextUtils.TruncateAt.END);
        setDir.setPadding(0, 0, 0, dp(2));
        setDir.setOnClickListener(v -> showDirDetail());
        c1.addView(setDir, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Ui.divider(this, c1);
        c1.addView(settingRow("下载线程", "1 最稳，10 最快；过高容易被限流", concActions()));
        Ui.divider(this, c1);
        CheckBox ci = check("默认下载插图", prefs.images);
        ci.setOnCheckedChangeListener((v, on) -> {
            prefs.images = on;
            Stores.savePrefs(this, prefs);
            if (cbImages != null) cbImages.setChecked(on);
        });
        c1.addView(settingRow("下载插图", "正文图片默认不抓，省流量", ci));
        Ui.divider(this, c1);
        CheckBox cb = check("默认书籍信息页", prefs.bookinfo);
        cb.setOnCheckedChangeListener((v, on) -> {
            prefs.bookinfo = on;
            Stores.savePrefs(this, prefs);
            if (cbBookinfo != null) cbBookinfo.setChecked(on);
        });
        c1.addView(settingRow("书籍信息页", "扉页：书名/作者/字数/ID/链接/时间", cb));
        box.addView(c1);

        LinearLayout c2 = Ui.card(this);
        c2.addView(header("界面", "只影响本机"));
        themeRow = Ui.row(this);
        themeRow.setPadding(0, dp(6), 0, dp(4));
        HorizontalScrollView themeScroll = new HorizontalScrollView(this);
        themeScroll.setHorizontalScrollBarEnabled(false);
        themeScroll.addView(themeRow);   // 五个主题在窄屏可横滑，不再挤成竖排
        c2.addView(themeScroll);
        if (eink6Hardware()) {
            DisplayMetrics m = getResources().getDisplayMetrics();
            String info = String.format(Locale.getDefault(),
                    "已识别 %d×%d · %ddpi · %.1f 英寸墨水屏",
                    m.widthPixels, m.heightPixels, m.densityDpi,
                    Ui.diagInches(m.widthPixels, m.heightPixels, m.xdpi, m.ydpi));
            if (prefs.theme == Stores.THEME_MONO) info += "\n当前「墨水屏」已自动套用 6″ 专用布局";
            TextView hw = Ui.text(this, info, Ui.dim(this, R.attr.dT0, 10), R.attr.cText2, false);
            LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            hlp.topMargin = dp(10);
            c2.addView(hw, hlp);
            if (prefs.theme != Stores.THEME_EINK6 && prefs.theme != Stores.THEME_MONO) {
                TextView apply = Ui.smallButton(this, "切换到「墨水屏 6″」");
                LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                alp.topMargin = dp(8);
                apply.setOnClickListener(v -> {
                    prefs.theme = Stores.THEME_EINK6;
                    Stores.savePrefs(this, prefs);
                    recreate();
                });
                c2.addView(apply, alp);
            }
        }
        box.addView(c2, cardLp(10));

        LinearLayout c3 = Ui.card(this);
        c3.addView(header("缓存", ""));
        setCache = Ui.text(this, "统计中…", Ui.dim(this, R.attr.dT1, 11.5f), R.attr.cText3, false);
        c3.addView(setCache);
        TextView clear = Ui.smallButton(this, "清空缓存");
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(10);
        c3.addView(clear, clp);
        clear.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("清空正文与图片缓存？")
                .setMessage("只删缓存，不影响已经生成的 EPUB。下次抓取会重新下载。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清空", (d, x) -> {
                    deleteTree(new File(getFilesDir(), "cache"));
                    updateCacheInfo();
                    toast("已清空");
                }).show());
        box.addView(c3, cardLp(10));

        LinearLayout c4 = Ui.card(this);
        c4.addView(header("关于", ""));
        String ver = "1.0";
        try {
            ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        TextView about = Ui.text(this,
                "公众号转 EPUB · 原生版 v" + ver + "\n纯 Java 实现，不打包 Python 运行时；"
                        + "抓取、清洗、EPUB 打包全部本地完成，不联网上传任何内容。",
                Ui.dim(this, R.attr.dT1, 11.5f), R.attr.cText3, false);
        c4.addView(about);
        TextView legacy = Ui.smallButton(this, "旧版（Chaquopy）可同时安装");
        legacy.setEnabled(false);
        dimButton(legacy, false);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.topMargin = dp(10);
        c4.addView(legacy, llp);
        box.addView(c4, cardLp(10));

        buildThemeChips();
        updateSettings();
        return scroll(box);
    }

    private LinearLayout settingRow(String title, String sub, View right) {
        LinearLayout r = Ui.row(this);
        r.setPadding(0, dp(12), 0, dp(12));
        LinearLayout t = Ui.column(this);
        t.addView(Ui.text(this, title, Ui.dim(this, R.attr.dT2, 14), R.attr.cText, false));
        t.addView(Ui.text(this, sub, Ui.dim(this, R.attr.dT0, 10), R.attr.cText3, false));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tp.rightMargin = dp(10);
        r.addView(t, tp);
        if (right != null) r.addView(right);
        return r;
    }

    private View dirActions() {
        LinearLayout r = Ui.row(this);
        TextView pick = Ui.smallButton(this, "更改");
        TextView reset = Ui.smallButton(this, "默认");
        pick.setOnClickListener(v -> pickDir());
        reset.setOnClickListener(v -> {
            prefs.treeUri = "";
            Stores.savePrefs(this, prefs);
            updateSettings();
            toast("已改为默认下载目录");
        });
        LinearLayout.LayoutParams p1 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p1.leftMargin = dp(6);
        r.addView(pick, p1);
        LinearLayout.LayoutParams p2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p2.leftMargin = dp(6);
        r.addView(reset, p2);
        return r;
    }

    private View concActions() {
        LinearLayout r = Ui.row(this);
        TextView minus = Ui.smallButton(this, "−");
        setConc = Ui.text(this, String.valueOf(prefs.concurrency),
                Ui.dim(this, R.attr.dT2, 14), R.attr.cText, true);
        setConc.setGravity(Gravity.CENTER);
        setConc.setMinWidth(dp(30));
        TextView plus = Ui.smallButton(this, "+");
        minus.setOnClickListener(v -> changeConc(-1));
        plus.setOnClickListener(v -> changeConc(1));
        r.addView(minus);
        r.addView(setConc);
        r.addView(plus);
        return r;
    }

    private void changeConc(int delta) {
        int v = Math.max(1, Math.min(10, prefs.concurrency + delta));
        prefs.concurrency = v;
        Stores.savePrefs(this, prefs);
        updateSettings();
    }

    private void updateSettings() {
        if (setDir != null) {
            String s = dirDisplay();
            // 墨水屏上每轮刷新都 setText 会反复重绘；内容没变就不动
            if (!s.contentEquals(setDir.getText())) setDir.setText(s);
        }
        if (setConc != null) {
            String s = String.valueOf(prefs.concurrency);
            if (!s.contentEquals(setConc.getText())) setConc.setText(s);
        }
    }

    /** 显示用户能看懂的目录名，而不是原来写死的「自定义目录」。 */
    private String dirDisplay() {
        if (prefs.treeUri == null || prefs.treeUri.isEmpty()) {
            return "当前：系统「下载 / 公众号EPUB」（默认）";
        }
        String name;
        try {
            name = Exporter.dirLabel(this, Uri.parse(prefs.treeUri));
        } catch (Exception e) {
            name = "所选目录";
        }
        return "当前：" + name + "\n点此查看完整路径";
    }

    private void showDirDetail() {
        boolean custom = prefs.treeUri != null && !prefs.treeUri.isEmpty();
        String full = custom ? prefs.treeUri
                : "未自定义。生成的 EPUB 先存在应用内目录，点「导出」再写入系统「下载/公众号EPUB」。";
        new AlertDialog.Builder(this)
                .setTitle("保存位置")
                .setMessage(dirDisplay().replace("点此查看完整路径", "").trim()
                        + "\n\n" + (custom ? "完整路径：\n" : "") + full)
                .setPositiveButton("知道了", null)
                .show();
    }

    private void updateCacheInfo() {
        if (setCache == null) return;
        new Thread(() -> {
            long size = dirSize(new File(getFilesDir(), "cache"));
            int files = countFiles(new File(getFilesDir(), "cache"));
            ui.post(() -> setCache.setText("正文与图片缓存：" + Model.fmtSize(size)
                    + " · " + files + " 个文件"));
        }).start();
    }

    private long dirSize(File f) {
        if (f == null || !f.exists()) return 0;
        if (f.isFile()) return f.length();
        long n = 0;
        File[] fs = f.listFiles();
        if (fs != null) for (File c : fs) n += dirSize(c);
        return n;
    }

    private int countFiles(File f) {
        if (f == null || !f.exists()) return 0;
        if (f.isFile()) return 1;
        int n = 0;
        File[] fs = f.listFiles();
        if (fs != null) for (File c : fs) n += countFiles(c);
        return n;
    }

    private void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] fs = f.listFiles();
        if (fs != null) for (File c : fs) deleteTree(c);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private void buildThemeChips() {
        themeRow.removeAllViews();
        String[] names = {"深色", "浅色", "墨水屏", "墨水屏 6″", "自动"};
        int[] modes = {Stores.THEME_DARK, Stores.THEME_LIGHT, Stores.THEME_MONO,
                Stores.THEME_EINK6, Stores.THEME_AUTO};
        boolean bw = Ui.mono(this);
        for (int i = 0; i < names.length; i++) {
            final int mode = modes[i];
            boolean on = prefs.theme == mode;
            TextView chip;
            if (bw) {
                chip = Ui.chipBw(this, names[i], on);   // 1bit：选中反白，不用灰度
            } else {
                chip = Ui.chip(this, names[i],
                        on ? R.attr.cPrimary : R.attr.cText2,
                        on ? Ui.color(this, R.attr.cPrimarySoft) : Ui.color(this, R.attr.cSurface2));
                chip.setPadding(dp(14), dp(8), dp(14), dp(8));
            }
            chip.setOnClickListener(v -> {
                prefs.theme = mode;
                Stores.savePrefs(this, prefs);
                recreate();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.leftMargin = dp(8);
            themeRow.addView(chip, lp);
        }
    }

    private void pickDir() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ_TREE);
        } catch (Exception e) {
            toast("打开目录选择器失败：" + e.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_TREE && res == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            boolean persisted = true;
            try {
                getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Exception e) {
                persisted = false;
            }
            prefs.treeUri = uri.toString();
            Stores.savePrefs(this, prefs);
            updateSettings();
            String name;
            try {
                name = Exporter.dirLabel(this, uri);
            } catch (Exception e) {
                name = "所选目录";
            }
            toast(persisted ? "保存位置已更新：" + name
                    : "目录已记录，但系统未授予长期权限，重启后可能需要重选");
        }
    }

    /* ==================== 导出 ==================== */

    private void exportResult() {
        if (Engine.I.resultPath.isEmpty()) return;
        if (Build.VERSION.SDK_INT < 29
                && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_WRITE);
            return;
        }
        File f = new File(Engine.I.resultPath);
        try {
            Exporter.Result r = Exporter.export(this, f, prefs.treeUri);
            toast("已保存到 " + r.label);
            if (r.uri != null) {
                List<Stores.HistItem> hs = Stores.loadHistory(this);
                for (Stores.HistItem h : hs) {
                    if (h.path.equals(f.getAbsolutePath())) h.uri = r.uri.toString();
                }
                Stores.saveHistory(this, hs);
                Engine.I.loadStores(this);
                histSig = "";
            }
        } catch (Exception e) {
            toast("保存失败：" + e.getMessage());
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req == REQ_WRITE) {
            if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) exportResult();
            else toast("没有存储权限，文件只保存在应用目录");
        }
    }

    /* ==================== 主刷新 ==================== */

    private void refresh() {
        if (Engine.I == null) return;
        // 墨水屏空闲时状态没变就整屏跳过，避免每轮 setText 触发无意义的刷新与残影
        if (Ui.eink6(this)) {
            String sig = tab + "|" + Engine.I.phase + "|" + Engine.I.message + "|"
                    + Engine.I.done + "/" + Engine.I.total + "|" + Engine.I.logs.size()
                    + "|" + Engine.I.checking + "|" + prefs.treeUri + "|" + prefs.concurrency;
            if (!Engine.I.busy() && sig.equals(lastRefreshSig)) return;
            lastRefreshSig = sig;
        }
        phaseChip.setText(Engine.I.message);
        int colorAttr = Engine.I.phase.equals("error") ? R.attr.cDanger
                : Engine.I.phase.equals("done") ? R.attr.cPrimary
                : Engine.I.busy() ? R.attr.cAccent : R.attr.cText2;
        phaseChip.setTextColor(Ui.color(this, colorAttr));

        if (tab == 0) {
            refreshAlbum();
            refreshList(false);
            refreshProgress();
            updateBuildButton();
        } else if (tab == 1) {
            renderShelf();
        } else if (tab == 2) {
            renderHistory();
        } else {
            updateSettings();
        }
    }
}
