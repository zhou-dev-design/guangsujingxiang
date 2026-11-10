package com.lightmirror.dl;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity {

    private static final int TAB_HOME = 0, TAB_WATCH = 1, TAB_HISTORY = 2, TAB_MIRROR = 3, TAB_SETTINGS = 4;
    private static final int MAX_CONTENT_DP = 620;
    private static final int MAX_TASKS = 12;                 // 队列上限，防止无限堆积（并发最多 10）

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<SpeedTester.Result> results = new ArrayList<>();
    private final List<RowHolder> rows = new ArrayList<>();

    private SharedPreferences prefs;
    private List<Mirror> mirrors;
    private SpeedTester tester;
    private DownloadEngine engine;
    private List<Mirror> ranked;
    private long rankedAt;
    private boolean rankDirty, testing;
    private String lastProbeUrl = "";

    private boolean mono;
    private int themeMode;          // 0 自动 / 1 深色 / 2 浅色 / 3 黑白
    private WatchController watch;
    private int runId;
    private boolean ignoreMonoToggle;
    private String pendingTag, pendingRepo;
    private int activeTab = TAB_HOME;

    private int cText, cText2, cText3, cPrimary, cDanger, cOnPrimary, cLine, cAccent;

    private View screenHome, screenWatch, screenHistory, screenMirrors, screenSettings;
    private EditText urlInput, customInput;
    private TextView chipType, fileName, fileSize, mirrorHint, mirrorCount, customHint, mirrorNote;
    private SegmentSlider segWatchMode;
    private TextView watchModeSub;
    private TextView savePath, savePathSub;
    private Button btnStart;
    private LinearLayout mirrorList, mirrorPrefs, historyList, dlBlock, taskList;
    private ScrollView mirrorScroll;
    private TextView taskCount;

    // 历史页：选择模式 + 勾选集合 + 每行的刷新闭包（勾选时只重画行，不重建列表）
    private boolean historyPicking;
    private final java.util.LinkedHashSet<String> historySel = new java.util.LinkedHashSet<>();
    private final List<Runnable> historyPainters = new ArrayList<>();

    // 拖拽排序：按住 ≡ 手柄上下拖，实时换位
    private View dragRow;
    private int dragIndex = -1, dragTarget = -1, dragHeight;
    private float dragGrabOffset;
    private boolean dragMoved;

    private final List<GithubUrlParser.Info> pendingTasks = new ArrayList<>();
    private final java.util.LinkedHashMap<Integer, TaskCard> cards = new java.util.LinkedHashMap<>();
    private String addMode = Mirror.MODE_PREFIX;

    static class RowHolder {
        View wrap;
        TextView rank, name, badge, meta, speed, lat;
        ProgressBar bar;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 主题必须在 super.onCreate 之前决定，否则窗口已经按默认主题创建
        prefs = MirrorCatalog.prefs(this);
        resolveTheme();
        setTheme(themeRes());

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        resolveColors();
        mirrors = MirrorCatalog.load(this);
        engine = DownloadEngine.get();
        applyEnginePrefs();

        bind();
        applyMonoChrome();
        monoType();
        setupTabs();
        setupHome();
        setupHistory();
        setupMirrors();
        setupSettings();
        applyContentWidth();

        safe("版本页", () -> {
            watch = new WatchController(this, new WatchController.Host() {
                @Override
                public void pickUrl(String url, String repo, String tag) {
                    MainActivity.this.pickUrl(url, repo, tag);
                }

                @Override
                public void pickBatch(List<WatchController.Pick> items) {
                    MainActivity.this.pickBatch(items);
                }

                @Override
                public int queueRoom() {
                    return Math.max(0, MAX_TASKS - cards.size() - pendingTasks.size());
                }

                @Override
                public boolean isQueued(String url) {
                    return MainActivity.this.isQueued(url);
                }

                @Override
                public void toast(String s) {
                    MainActivity.this.toast(s);
                }

                @Override
                public boolean isDownloading() {
                    return queueRoom() <= 0;
                }

                @Override
                public void openSettings() {
                    switchTab(TAB_SETTINGS);
                }
            });
            watch.bind();
        });

        switchTab(TAB_HOME);
        renderMirrorList(enabledMirrors(), -1);
        safe("记录页", this::renderHistory);
        safe("镜像偏好", this::renderMirrorPrefs);
        safe("剪贴板", this::prefillFromClipboard);
    }

    @Override
    protected void onDestroy() {
        if (tester != null) tester.cancel();
        super.onDestroy();
    }

    /** 非核心模块（版本页、记录页、镜像偏好）出错不应连累主界面，只记日志 */
    private void safe(String tag, Runnable body) {
        try {
            body.run();
        } catch (Throwable t) {
            android.util.Log.w("LightMirror", tag + " 初始化失败", t);
        }
    }

    // ---------------------------------------------------------------- 主题

    /** 0 自动 / 1 深色 / 2 浅色 / 3 黑白 */
    private void resolveTheme() {
        themeMode = prefs.getInt("theme", 0);
        if (themeMode < 0 || themeMode > 3) themeMode = 0;
        mono = themeMode == 3 || (themeMode == 0 && shouldAutoMono());
    }

    private int themeRes() {
        if (mono) return R.style.AppTheme_Mono;
        if (themeMode == 1) return R.style.AppTheme;
        return R.style.AppTheme_Light;   // 浅色：自动模式下的普通设备默认
    }

    /**
     * 自动模式：4:3 且小尺寸（6~8 寸墨水屏的 dp 范围）才进黑白，
     * 普通 4:3 平板走浅色，避免一块好屏被强行做成黑白。
     */
    private boolean shouldAutoMono() {
        if (!isFourThree()) return false;
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int minPx = Math.min(dm.widthPixels, dm.heightPixels);

        // 常规判定：6~8 寸墨水屏的 dp 宽度都落在 640 以内
        float minDp = minPx / dm.density;
        if (minDp <= 640) return true;

        // 有些墨水屏 ROM 会把 density 报成 160/240，dp 就虚高了，
        // 这时用「4:3 + 物理对角线 5~8.5 寸 + 短边不超过 1600px」兜底。
        if (dm.xdpi > 100 && dm.ydpi > 100 && minPx <= 1600) {
            double w = dm.widthPixels / dm.xdpi;
            double h = dm.heightPixels / dm.ydpi;
            double diag = Math.sqrt(w * w + h * h);
            return diag >= 5.0 && diag <= 8.5;
        }
        return false;
    }

    /** 4:3 屏幕（长宽比 1.333±0.06）判定，墨水屏平板基本都落在这个区间 */
    private boolean isFourThree() {
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int max = Math.max(dm.widthPixels, dm.heightPixels);
        int min = Math.min(dm.widthPixels, dm.heightPixels);
        if (min == 0) return false;
        double ratio = (double) max / min;
        return Math.abs(ratio - 4.0 / 3.0) <= 0.06;
    }

    private int attr(int resId) {
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(resId, tv, true);
        return tv.data;
    }

    private int dimPx(int attrRes) {
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(attrRes, tv, true);
        return (int) tv.getDimension(getResources().getDisplayMetrics());
    }

    private void resolveColors() {
        cText = attr(R.attr.cText);
        cText2 = attr(R.attr.cText2);
        cText3 = attr(R.attr.cText3);
        cPrimary = attr(R.attr.cPrimary);
        cDanger = attr(R.attr.cDanger);
        cOnPrimary = attr(R.attr.cOnPrimary);
        cLine = attr(R.attr.cLine);
        cAccent = attr(R.attr.cAccent);
    }

    /** 墨水屏：加大进度条、去掉波形、把系统栏图标改成深色 */
    private void applyMonoChrome() {
        if (mono) {
            // 任务卡片的进度条在构造时按 mono 加粗、换成纯黑描边，这里只处理窗口与导航
            // 墨水屏整屏刷新会闪，切页不做淡入淡出
            getWindow().setWindowAnimations(0);

            // 11sp 标签 + 22dp 图标，64dp 略挤
            View tabbar = findViewById(R.id.tabbar);
            ViewGroup.LayoutParams tlp = tabbar.getLayoutParams();
            tlp.height = dp(68);
            tabbar.setLayoutParams(tlp);

            View divider = findViewById(R.id.dividerTab);
            if (divider != null) {
                ViewGroup.LayoutParams dlp = divider.getLayoutParams();
                dlp.height = dp(2);
                divider.setLayoutParams(dlp);
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                WindowInsetsController ctl = getWindow().getInsetsController();
                if (ctl != null) {
                    int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                    ctl.setSystemBarsAppearance(mono ? mask : 0, mask);
                }
            } else {
                getWindow().getDecorView().setSystemUiVisibility(
                        mono ? View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR : 0);
            }
        } catch (Exception ignored) {
        }
    }

    /** 4:3 平板上把整屏内容收成一条 620dp 的居中列，避免一行拉得过宽 */
    private void applyContentWidth() {
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int avail = dm.widthPixels - dp(40);
        int max = dp(MAX_CONTENT_DP);
        if (avail <= max) return;

        int[] screens = {R.id.screenHome, R.id.screenWatch, R.id.screenHistory,
                R.id.screenMirrors, R.id.screenSettings};
        for (int sid : screens) {
            ViewGroup screen = findViewById(sid);
            for (int i = 0; i < screen.getChildCount(); i++) {
                View child = screen.getChildAt(i);
                ViewGroup.LayoutParams raw = child.getLayoutParams();
                if (!(raw instanceof LinearLayout.LayoutParams)) continue;
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) raw;
                lp.width = max;
                lp.gravity = Gravity.CENTER_HORIZONTAL;
                child.setLayoutParams(lp);
            }
        }
    }

    // ---------------------------------------------------------------- 绑定

    private void bind() {
        screenHome = findViewById(R.id.screenHome);
        screenWatch = findViewById(R.id.screenWatch);
        screenHistory = findViewById(R.id.screenHistory);
        screenMirrors = findViewById(R.id.screenMirrors);
        screenSettings = findViewById(R.id.screenSettings);

        urlInput = findViewById(R.id.urlInput);
        customInput = findViewById(R.id.customInput);
        chipType = findViewById(R.id.chipType);
        fileName = findViewById(R.id.fileName);
        fileSize = findViewById(R.id.fileSize);
        mirrorHint = findViewById(R.id.mirrorHint);
        mirrorCount = findViewById(R.id.mirrorCount);
        customHint = findViewById(R.id.customHint);
        mirrorNote = findViewById(R.id.mirrorNote);

        btnStart = findViewById(R.id.btnStart);
        mirrorList = findViewById(R.id.mirrorList);
        mirrorPrefs = findViewById(R.id.mirrorPrefs);
        mirrorScroll = findViewById(R.id.mirrorScroll);
        if (mirrorPrefs != null) mirrorPrefs.setClipChildren(false);
        if (mirrorScroll != null) mirrorScroll.setClipChildren(false);
        historyList = findViewById(R.id.historyList);
        dlBlock = findViewById(R.id.dlBlock);
        taskList = findViewById(R.id.taskList);
        taskCount = findViewById(R.id.taskCount);
    }

    private void monoType() {
        Typeface monoFace = Typeface.MONOSPACE;
        urlInput.setTypeface(monoFace);
        fileName.setTypeface(monoFace, Typeface.BOLD);
    }

    private void tint(ImageView v, int color) {
        if (v != null) v.setImageTintList(ColorStateList.valueOf(color));
    }

    // ---------------------------------------------------------------- Tab

    private void setupTabs() {
        findViewById(R.id.tabHome).setOnClickListener(v -> switchTab(TAB_HOME));
        findViewById(R.id.tabWatch).setOnClickListener(v -> switchTab(TAB_WATCH));
        findViewById(R.id.tabHistory).setOnClickListener(v -> switchTab(TAB_HISTORY));
        findViewById(R.id.tabMirror).setOnClickListener(v -> switchTab(TAB_MIRROR));
        findViewById(R.id.tabSettings).setOnClickListener(v -> switchTab(TAB_SETTINGS));
    }

    private void switchTab(int tab) {
        activeTab = tab;
        screenHome.setVisibility(tab == TAB_HOME ? View.VISIBLE : View.GONE);
        screenWatch.setVisibility(tab == TAB_WATCH ? View.VISIBLE : View.GONE);
        screenHistory.setVisibility(tab == TAB_HISTORY ? View.VISIBLE : View.GONE);
        screenMirrors.setVisibility(tab == TAB_MIRROR ? View.VISIBLE : View.GONE);
        screenSettings.setVisibility(tab == TAB_SETTINGS ? View.VISIBLE : View.GONE);

        int[] icons = {R.id.icoHome, R.id.icoWatch, R.id.icoHistory, R.id.icoMirror, R.id.icoSettings};
        int[] labels = {R.id.lblHome, R.id.lblWatch, R.id.lblHistory, R.id.lblMirror, R.id.lblSettings};
        for (int i = 0; i < icons.length; i++) {
            boolean on = i == tab;
            ImageView ic = findViewById(icons[i]);
            TextView lb = findViewById(labels[i]);
            tint(ic, on ? cPrimary : cText3);
            lb.setTextColor(on ? cPrimary : cText3);
        }
        if (tab == TAB_WATCH && watch != null) watch.onShown();
        if (tab == TAB_HISTORY) renderHistory();
        if (tab == TAB_MIRROR) renderMirrorPrefs();
        if (tab == TAB_HOME && !testing && results.isEmpty()) {
            renderMirrorList(enabledMirrors(), -1);
        }
    }

    // ---------------------------------------------------------------- 首页

    private void setupHome() {
        btnStart.setOnClickListener(v -> startFlow());
        findViewById(R.id.btnRetest).setOnClickListener(v -> retest());
        findViewById(R.id.btnClearDone).setOnClickListener(v -> clearFinished());
        findViewById(R.id.btnCancelAll).setOnClickListener(v -> {
            engine.cancelAll();
            toast("已向所有任务发送取消");
        });

        ImageView paste = findViewById(R.id.btnPaste);
        tint(paste, cText2);
        paste.setOnClickListener(v -> {
            String s = readClipboard();
            if (s == null || s.length() == 0) {
                toast("剪贴板里没有链接");
                return;
            }
            urlInput.setText(s);
            urlInput.setSelection(s.length());
        });

        urlInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
                updateParsed();
            }
        });
        updateParsed();
    }

    private void updateParsed() {
        GithubUrlParser.Info info = GithubUrlParser.parse(urlInput.getText().toString());
        if (info == null) {
            chipType.setText("等待链接");
            fileName.setText("");
            fileSize.setText("");
            return;
        }
        if (!info.downloadable) {
            chipType.setText("不是文件链接");
            fileName.setText(info.repo);
            fileSize.setText("仓库地址 · 去「版本」页挑文件");
            return;
        }
        chipType.setText(info.kind);
        fileName.setText(info.fileName);
        fileSize.setText("· 大小待测");
    }

    /** 版本页选中某个附件后回到首页直接开跑 */
    private void pickUrl(String url, String repo, String tag) {
        pendingTag = tag;
        pendingRepo = repo;
        switchTab(TAB_HOME);
        urlInput.setText(url);
        urlInput.setSelection(url.length());
        startFlow();
    }

    /** 这个地址是不是已经排上了（待测速清单或引擎里都算），批量时用来挡重复 */
    private boolean isQueued(String url) {
        if (url == null || url.isEmpty()) return false;
        for (GithubUrlParser.Info p : pendingTasks) {
            if (url.equals(p.url)) return true;
        }
        return engine.hasTask(url);
    }

    /**
     * 批量模式：勾选的文件一次性进队列，统一测一次速，再按并发数同时开跑。
     * 已经下过 / 已在队列里的会被挡掉，避免重复下一份。
     */
    private void pickBatch(List<WatchController.Pick> items) {
        if (items == null || items.isEmpty()) return;

        if (MAX_TASKS - cards.size() - pendingTasks.size() < items.size()) {
            clearFinished();       // 先腾地方，不碰正在下的
        }

        int room = MAX_TASKS - cards.size() - pendingTasks.size();
        BatchPlanner.Result plan = BatchPlanner.plan(items, this::isQueued, room);
        if (plan.nothing()) {
            toast(plan.message());
            return;
        }
        pendingTasks.addAll(plan.accepted);

        switchTab(TAB_HOME);
        urlInput.setText("");
        updateParsed();
        toast(plan.message());
        startBatchFlow();
    }

    /** 批量入口的起跑：排名还在有效期内就直接复用，否则只测一次速 */
    private void startBatchFlow() {
        if (pendingTasks.isEmpty()) return;
        long ttl = Settings.rankTtlMs(this);
        if (ttl > 0 && ranked != null && !ranked.isEmpty() && !rankDirty
                && System.currentTimeMillis() - rankedAt < ttl) {
            flushPending();
            toast("开始并发下载 · 复用镜像排名（" + Settings.ttlMin(this) + " 分钟内有效）");
            return;
        }
        if (testing) {
            toast("测速中，测完自动开始");
            return;
        }
        runSpeedTest(pendingTasks.get(0).url);
    }

    private void startFlow() {
        final String raw = urlInput.getText().toString().trim();
        final GithubUrlParser.Info info = GithubUrlParser.parse(raw);
        if (info == null || !info.downloadable) {
            String repo = ReleaseChecker.parseRepo(raw);
            if (repo != null && watch != null) {
                switchTab(TAB_WATCH);
                watch.fillRepo(repo);
                toast("这是仓库地址，已切到版本页挑文件");
                return;
            }
            toast("请粘贴 GitHub 文件链接：release 附件 / archive / raw");
            return;
        }
        if (cards.size() >= MAX_TASKS) {
            toast("任务队列已满（最多 " + MAX_TASKS + " 个），先清除已完成");
            return;
        }
        info.tag = pendingTag;
        info.watchRepo = pendingRepo;
        pendingTag = null;
        pendingRepo = null;
        pendingTasks.add(info);
        urlInput.setText("");       // 方便接着粘下一个链接
        updateParsed();

        // 排名还在有效期内：直接复用，不必每个文件都重测一遍
        long ttl = Settings.rankTtlMs(this);
        if (ttl > 0 && ranked != null && !ranked.isEmpty() && !rankDirty
                && System.currentTimeMillis() - rankedAt < ttl) {
            flushPending();
            toast("已加入队列 · 复用镜像排名（" + Settings.ttlMin(this) + " 分钟内有效）");
            return;
        }
        if (testing) {
            toast("测速中，已加入下载队列");
            return;
        }
        runSpeedTest(info.url);
    }

    private void retest() {
        if (testing) {
            toast("正在测速…");
            return;
        }
        ranked = null;
        rankDirty = true;
        String probe = lastProbeUrl;
        GithubUrlParser.Info info = GithubUrlParser.parse(urlInput.getText().toString().trim());
        if (info != null && info.downloadable) probe = info.url;
        if (probe.isEmpty()) {
            toast("先粘贴一个文件链接再测速");
            return;
        }
        runSpeedTest(probe);
    }

    private void runSpeedTest(final String url) {
        final List<Mirror> enabled = enabledMirrors();
        if (enabled.isEmpty()) {
            toast("请先在「镜像」页启用至少一个镜像");
            pendingTasks.clear();
            return;
        }
        lastProbeUrl = url;
        results.clear();
        testing = true;
        renderMirrorList(enabled, -1);
        btnStart.setEnabled(false);
        btnStart.setText(R.string.btn_testing);
        mirrorHint.setText("准备测速…");
        if (tester != null) tester.cancel();
        tester = new SpeedTester(Settings.chunkKB(this) * 1024, Settings.timeoutSec(this) * 1000);
        final int myRun = ++runId;

        SpeedTester.Listener listener = new SpeedTester.Listener() {
            @Override
            public void onStart(int index, int total, Mirror m) {
                ui.post(() -> {
                    if (myRun != runId) return;
                    mirrorHint.setText("正在测试 " + index + "/" + total + " · " + m.name);
                    markTesting(index - 1);
                });
            }

            @Override
            public void onResult(int index, int total, SpeedTester.Result r) {
                ui.post(() -> {
                    if (myRun != runId) return;
                    results.add(r);
                    r.mirror.record(r.ok, r.latencyMs, r.speed);
                    MirrorCatalog.save(MainActivity.this, mirrors);
                    fillRow(index - 1, r);
                    if (activeTab == TAB_MIRROR) renderMirrorPrefs();
                });
            }

            @Override
            public void onFinish(List<SpeedTester.Result> all) {
                ui.post(() -> {
                    if (myRun != runId) return;
                    onTestFinished(enabled);
                });
            }
        };
        new Thread(() -> tester.run(enabled, url, listener), "speedtest").start();
    }

    private void onTestFinished(List<Mirror> probed) {
        testing = false;
        btnStart.setEnabled(true);
        btnStart.setText(R.string.btn_start);
        if (results.isEmpty()) {
            mirrorHint.setText("测速未完成");
            pendingTasks.clear();
            return;
        }
        List<SpeedTester.Result> sorted = new ArrayList<>(results);
        Collections.sort(sorted, (a, b) -> {
            if (a.ok != b.ok) return a.ok ? -1 : 1;
            return Double.compare(b.score(), a.score());
        });
        List<Mirror> order = new ArrayList<>();
        for (SpeedTester.Result r : sorted) order.add(r.mirror);
        renderMirrorList(order, sorted.get(0).ok ? 0 : -1);

        SpeedTester.Result best = sorted.get(0);
        if (!best.ok) {
            mirrorHint.setText("所有镜像都不可用");
            pendingTasks.clear();
            toast("测速全部失败，稍后再试或换网络");
            return;
        }
        mirrorHint.setText(String.format(Locale.US, "最快：%s · %.1f MB/s",
                best.mirror.name, best.speed));
        ranked = new ArrayList<>();
        for (SpeedTester.Result r : sorted) if (r.ok) ranked.add(r.mirror);
        rankedAt = System.currentTimeMillis();
        rankDirty = false;
        flushPending();
    }

    private void flushPending() {
        if (pendingTasks.isEmpty()) return;
        for (GithubUrlParser.Info info : pendingTasks) enqueueTask(info);
        pendingTasks.clear();
    }

    /** 镜像目录变了，旧排名作废 */
    private void invalidateRank() {
        rankDirty = true;
        if (activeTab == TAB_HOME && !testing) renderMirrorList(enabledMirrors(), -1);
    }

    // ---------------------------------------------------------------- 镜像行

    private void renderMirrorList(List<Mirror> list, int bestIndex) {
        mirrorList.removeAllViews();
        rows.clear();
        for (int i = 0; i < list.size(); i++) {
            RowHolder h = buildRow(i + 1, list.get(i), i == bestIndex);
            rows.add(h);
            mirrorList.addView(h.wrap);
            SpeedTester.Result r = findResult(list.get(i).name);
            if (r != null) fillRow(i, r);
        }
    }

    private SpeedTester.Result findResult(String name) {
        for (SpeedTester.Result r : results) {
            if (r.mirror.name.equals(name)) return r;
        }
        return null;
    }

    private RowHolder buildRow(int rank, Mirror m, boolean best) {
        RowHolder h = new RowHolder();

        FrameLayout wrap = new FrameLayout(this);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wlp.bottomMargin = dp(2);
        wrap.setLayoutParams(wlp);
        wrap.setBackgroundResource(best ? R.drawable.bg_row_win : 0);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), dp(11), dp(4), dp(14));
        wrap.addView(row, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView rankView = new TextView(this);
        rankView.setLayoutParams(new LinearLayout.LayoutParams(dp(26), dp(26)));
        rankView.setGravity(Gravity.CENTER);
        rankView.setText(String.valueOf(rank));
        rankView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        rankView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        rankView.setTextColor(best ? cOnPrimary : cText3);
        rankView.setBackgroundResource(best ? R.drawable.bg_rank_win : R.drawable.bg_rank);
        row.addView(rankView);

        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        mlp.setMarginStart(dp(12));
        main.setLayoutParams(mlp);

        LinearLayout nameRow = new LinearLayout(this);
        nameRow.setOrientation(LinearLayout.HORIZONTAL);
        nameRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView name = new TextView(this);
        name.setText(m.name);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        name.setTextColor(cText);
        name.setSingleLine(true);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        nameRow.addView(name);

        TextView badge = new TextView(this);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.setMarginStart(dp(6));
        badge.setLayoutParams(blp);
        badge.setText("最快");
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setTextColor(cOnPrimary);
        badge.setBackgroundResource(R.drawable.bg_rank_win);
        badge.setPadding(dp(6), dp(2), dp(6), dp(2));
        badge.setVisibility(best ? View.VISIBLE : View.GONE);
        nameRow.addView(badge);

        main.addView(nameRow);

        TextView meta = new TextView(this);
        LinearLayout.LayoutParams metlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        metlp.topMargin = dp(4);
        meta.setLayoutParams(metlp);
        meta.setText("待测速");
        meta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        meta.setTextColor(cText3);
        meta.setTypeface(Typeface.MONOSPACE);
        main.addView(meta);
        row.addView(main);

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.END);

        TextView speedView = new TextView(this);
        speedView.setText("--");
        speedView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        speedView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        speedView.setTextColor(cText);
        speedView.setGravity(Gravity.END);
        right.addView(speedView);

        TextView lat = new TextView(this);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.topMargin = dp(3);
        lat.setLayoutParams(llp);
        lat.setText("--");
        lat.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        lat.setTextColor(cText3);
        lat.setTypeface(Typeface.MONOSPACE);
        lat.setGravity(Gravity.END);
        right.addView(lat);
        row.addView(right);

        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgress(0);
        bar.setProgressDrawable(getDrawable(mono ? R.drawable.progress_score_mono : R.drawable.progress_score));
        FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(mono ? 3 : 2));
        barLp.gravity = Gravity.BOTTOM;
        barLp.leftMargin = dp(10);
        barLp.rightMargin = dp(10);
        barLp.bottomMargin = dp(4);
        wrap.addView(bar, barLp);

        h.wrap = wrap;
        h.rank = rankView;
        h.name = name;
        h.badge = badge;
        h.meta = meta;
        h.speed = speedView;
        h.lat = lat;
        h.bar = bar;
        return h;
    }

    private void markTesting(int index) {
        if (index < 0 || index >= rows.size()) return;
        RowHolder h = rows.get(index);
        h.speed.setText("…");
        h.speed.setTextColor(cPrimary);
        h.lat.setText("探测中");
        h.lat.setTextColor(cPrimary);
    }

    private void fillRow(int index, SpeedTester.Result r) {
        if (index < 0 || index >= rows.size()) return;
        RowHolder h = rows.get(index);
        h.speed.setTextColor(cText);
        h.lat.setTextColor(cText3);
        if (!r.ok) {
            h.speed.setText("—");
            h.speed.setTextColor(cText3);
            h.lat.setText(r.error.isEmpty() ? "失败" : r.error);
            h.lat.setTextColor(cDanger);
            h.meta.setText("无响应");
            if (!mono) h.wrap.setAlpha(0.42f);
            h.bar.setProgress(0);
            return;
        }
        h.wrap.setAlpha(1f);
        h.speed.setText(String.format(Locale.US, "%.1f", r.speed));
        h.lat.setText(r.latencyMs + " ms");
        if (r.latencyMs < 250) h.lat.setTextColor(cPrimary);
        h.meta.setText(String.format(Locale.US, "得分 %d · Range %s", (int) r.score(), chunkLabel()));
        h.bar.setProgress((int) r.score());
    }

    // ---------------------------------------------------------------- 下载

    private static final String PREF_SAVE_DIR = "saveDir";

    private File privateDownloadDir() {
        File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (dir == null) dir = new File(getFilesDir(), "Download");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private File publicDownloadDir() {
        return new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), "LightMirror");
    }

    /** 有没有写公共目录的权限：11+ 看「所有文件访问」，10 及以下看存储权限 */
    private boolean canWritePublic() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                return Environment.isExternalStorageManager();
            }
            return checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) {
            // 个别 ROM / 测试环境里这个查询会抛，按“没权限”处理，回退私有目录
            return false;
        }
    }

    /** 当前真正落盘的目录；选的位置不可写就退回私有目录，不让人下了一半才发现失败 */
    private File downloadDir() {
        String pref = prefs.getString(PREF_SAVE_DIR, "");
        File dir;
        if (!pref.isEmpty()) {
            dir = new File(pref);
        } else if (canWritePublic()) {
            dir = publicDownloadDir();
        } else {
            dir = privateDownloadDir();
        }
        try {
            if (!dir.exists() && !dir.mkdirs()) throw new java.io.IOException("mkdir 失败");
            if (!dir.isDirectory() || !dir.canWrite()) throw new java.io.IOException("不可写");
            return dir;
        } catch (Exception e) {
            if (!pref.isEmpty()) toast("保存位置不可写，已退回应用私有目录");
            File fb = privateDownloadDir();
            fb.mkdirs();
            return fb;
        }
    }

    // ---------------------------------------------------------------- 保存位置

    private void setupSavePath() {
        savePath = findViewById(R.id.savePath);
        savePathSub = findViewById(R.id.savePathSub);
        View row = findViewById(R.id.rowSavePath);
        if (row != null) row.setOnClickListener(v -> showSavePathDialog());
        refreshSavePath();
    }

    private void refreshSavePath() {
        if (savePath == null || savePathSub == null) return;
        File dir = downloadDir();
        String label;
        if (dir.equals(privateDownloadDir())) label = "应用私有";
        else if (dir.equals(publicDownloadDir())) label = "系统下载";
        else label = "自定义";
        savePath.setText(label + " ›");
        savePathSub.setText(dir.getAbsolutePath());
    }

    private void showSavePathDialog() {
        final String priv = privateDownloadDir().getAbsolutePath();
        final String pub = publicDownloadDir().getAbsolutePath();
        final String[] items = {
                "系统「下载」目录（推荐）\n" + pub,
                "应用私有目录\n" + priv,
                "自定义路径…"
        };
        new AlertDialog.Builder(this)
                .setTitle("文件保存到哪")
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        usePublicDir();
                    } else if (which == 1) {
                        prefs.edit().putString(PREF_SAVE_DIR, priv).apply();
                        refreshSavePath();
                        toast("已改为应用私有目录");
                    } else {
                        showCustomPathDialog();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void usePublicDir() {
        if (canWritePublic()) {
            File d = publicDownloadDir();
            if (!d.exists() && !d.mkdirs()) {
                toast("建目录失败，可能权限还没生效");
                askAllFiles();
                return;
            }
            prefs.edit().putString(PREF_SAVE_DIR, d.getAbsolutePath()).apply();
            refreshSavePath();
            toast("以后都存到 " + d.getAbsolutePath());
            return;
        }
        askAllFiles();
    }

    private void askAllFiles() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            new AlertDialog.Builder(this)
                    .setTitle("需要「所有文件访问」权限")
                    .setMessage("要把文件存进系统「下载」目录，需要允许本应用管理所有文件。\n\n"
                            + "点「去授权」后，把「允许管理所有文件」打开，再返回本应用即可。")
                    .setPositiveButton("去授权", (d, w) -> {
                        try {
                            Intent i = new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                            i.setData(Uri.parse("package:" + getPackageName()));
                            startActivity(i);
                        } catch (Exception e) {
                            try {
                                startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                            } catch (Exception e2) {
                                toast("系统没有这个设置页，可以改用自定义路径");
                            }
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
        } else {
            requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1001);
        }
    }

    private void showCustomPathDialog() {
        final EditText et = new EditText(this);
        String cur = prefs.getString(PREF_SAVE_DIR, "");
        et.setText(cur.isEmpty() ? publicDownloadDir().getAbsolutePath() : cur);
        et.setSingleLine(true);
        et.setHint("/storage/emulated/0/LightMirror");
        et.setTextSize(TypedValue.COMPLEX_UNIT_PX, dimPx(R.attr.dT2));
        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(et);

        new AlertDialog.Builder(this)
                .setTitle("自定义保存路径")
                .setMessage("填一个完整路径，例如 /storage/emulated/0/MyApp")
                .setView(box)
                .setPositiveButton("保存", (d, w) -> {
                    String p = et.getText().toString().trim();
                    if (p.isEmpty()) return;
                    File f = new File(p);
                    if (!f.exists() && !f.mkdirs()) {
                        toast("建不了这个目录，检查路径或先授权");
                        return;
                    }
                    if (!f.canWrite()) {
                        toast("这个目录不可写，先授权「所有文件访问」再试");
                        return;
                    }
                    prefs.edit().putString(PREF_SAVE_DIR, f.getAbsolutePath()).apply();
                    refreshSavePath();
                    toast("以后都存到 " + f.getAbsolutePath());
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void fillVersion() {
        TextView v = findViewById(R.id.appVersion);
        if (v == null) return;
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            long code = Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
            v.setText(pi.versionName + " (build " + code + ")");
        } catch (Exception e) {
            v.setText("未知");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshSavePath();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1001) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                usePublicDir();
            } else {
                toast("没给存储权限，仍存到应用私有目录");
            }
        }
    }

    private void enqueueTask(final GithubUrlParser.Info info) {
        if (ranked == null || ranked.isEmpty()) {
            toast("镜像排名还没准备好");
            return;
        }
        final TaskCard card = new TaskCard(info);
        taskList.addView(card.card);
        applyEnginePrefs();

        DownloadEngine.Listener l = new DownloadEngine.Listener() {
            @Override
            public void onState(DownloadEngine.State st, String msg) {
                ui.post(() -> card.onState(st, msg));
            }

            @Override
            public void onMirror(String name, int attempt) {
                ui.post(() -> card.onMirror(name, attempt));
            }

            @Override
            public void onProgress(long got, long total, double mbps) {
                ui.post(() -> card.paint(got, total, mbps));
            }

            @Override
            public void onDone(File file, String mirrorName) {
                ui.post(() -> card.onDone(file, mirrorName));
            }

            @Override
            public void onPlan(int threads) {
                ui.post(() -> card.onPlan(threads));
            }
        };
        int id = engine.submit(downloadDir(), info.url, info.fileName, ranked, l);
        card.id = id;
        cards.put(id, card);
        updateTaskCount();
    }

    private void updateTaskCount() {
        int active = engine.activeCount();
        taskCount.setText(active + " 个进行中 / " + cards.size() + " 个任务");
        dlBlock.setVisibility(cards.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void clearFinished() {
        java.util.Iterator<java.util.Map.Entry<Integer, TaskCard>> it = cards.entrySet().iterator();
        while (it.hasNext()) {
            java.util.Map.Entry<Integer, TaskCard> e = it.next();
            DownloadEngine.Task t = engine.get(e.getKey());
            if (t == null || !t.active()) {
                taskList.removeView(e.getValue().card);
                it.remove();
            }
        }
        engine.clearFinished();
        updateTaskCount();
    }

    private String clock(long sec) {
        return String.format(Locale.US, "%02d:%02d", sec / 60, sec % 60);
    }

    /** 一张任务卡片：自己管自己的进度、计时、按钮和完成后的打开动作 */
    private class TaskCard {
        int id;
        final View card;
        final TextView name, src, pct, spd, got, elapsed, eta;
        final ProgressBar bar;
        final TextView btnPause, btnCancel, btnOpen, btnShare;
        final LinearLayout actionRow;
        final GithubUrlParser.Info info;

        File file;
        long lastPaint;
        int lastPct = -1;
        double lastSpeed;
        String srcMirror = "";
        int srcAttempt, srcThreads;

        TaskCard(final GithubUrlParser.Info info) {
            this.info = info;

            LinearLayout root = new LinearLayout(MainActivity.this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackground(getDrawable(R.drawable.bg_dl_card));
            root.setPadding(dp(14), dp(12), dp(14), dp(12));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(10);
            root.setLayoutParams(lp);
            card = root;

            LinearLayout top = hRow();
            name = label(info.fileName, R.attr.cText, R.attr.dT2, true);
            fitTwoLines(name);
            name.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            nlp.setMarginEnd(dp(10));
            top.addView(name, nlp);
            pct = label("0%", R.attr.cPrimary, R.attr.dT5, true);
            pct.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            top.addView(pct);
            root.addView(top);

            src = label("排队中", R.attr.cText3, R.attr.dT0, false);
            src.setSingleLine(true);
            src.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            slp.topMargin = dp(4);
            src.setLayoutParams(slp);
            root.addView(src);

            bar = new ProgressBar(MainActivity.this, null, android.R.attr.progressBarStyleHorizontal);
            bar.setMax(100);
            bar.setProgress(0);
            bar.setIndeterminate(false);
            bar.setProgressDrawable(getDrawable(mono
                    ? R.drawable.progress_dl_mono : R.drawable.progress_dl));
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(mono ? 10 : 6));
            blp.topMargin = dp(10);
            bar.setLayoutParams(blp);
            root.addView(bar);

            LinearLayout r1 = hRow();
            r1.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    0f));
            LinearLayout.LayoutParams r1lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            r1lp.topMargin = dp(10);
            r1.setLayoutParams(r1lp);
            spd = label("0.0 MB/s", R.attr.cText2, R.attr.dT1, false);
            r1.addView(spd, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            got = label("0 / —", R.attr.cText2, R.attr.dT1, false);
            got.setGravity(Gravity.END);
            r1.addView(got, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            root.addView(r1);

            LinearLayout r2 = hRow();
            LinearLayout.LayoutParams r2lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            r2lp.topMargin = dp(5);
            r2.setLayoutParams(r2lp);
            elapsed = label("已用 00:00", R.attr.cText2, R.attr.dT1, false);
            r2.addView(elapsed, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            eta = label("剩余 --:--", R.attr.cText2, R.attr.dT1, false);
            eta.setGravity(Gravity.END);
            r2.addView(eta, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            root.addView(r2);

            LinearLayout btns = hRow();
            LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            btnLp.topMargin = dp(12);
            btns.setLayoutParams(btnLp);
            btnPause = ghost("暂停", R.attr.cText);
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(0, dp(46), 1f);
            plp.setMarginEnd(dp(4));
            btns.addView(btnPause, plp);
            btnCancel = ghost("取消", R.attr.cDanger);
            LinearLayout.LayoutParams clp2 = new LinearLayout.LayoutParams(0, dp(46), 1f);
            clp2.setMarginStart(dp(4));
            btns.addView(btnCancel, clp2);
            root.addView(btns);

            actionRow = hRow();
            actionRow.setVisibility(View.GONE);
            LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            alp.topMargin = dp(8);
            actionRow.setLayoutParams(alp);
            btnOpen = ghost("安装", R.attr.cOnPrimary);
            btnOpen.setBackground(getDrawable(R.drawable.bg_primary));
            btnOpen.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(0, dp(46), 1f);
            olp.setMarginEnd(dp(4));
            actionRow.addView(btnOpen, olp);
            btnShare = ghost("其他应用打开", R.attr.cText);
            LinearLayout.LayoutParams shlp = new LinearLayout.LayoutParams(0, dp(46), 1f);
            shlp.setMarginStart(dp(4));
            actionRow.addView(btnShare, shlp);
            root.addView(actionRow);

            btnPause.setOnClickListener(v -> {
                DownloadEngine.Task t = engine.get(id);
                if (t == null || !t.active()) return;
                if (t.state == DownloadEngine.State.PAUSED) {
                    engine.resume(id);
                    btnPause.setText("暂停");
                } else {
                    engine.pause(id);
                    btnPause.setText("继续");
                }
            });
            btnCancel.setOnClickListener(v -> engine.cancel(id));
            btnOpen.setOnClickListener(v -> {
                if (file == null || !file.exists()) {
                    toast("文件已被移动或清理");
                    return;
                }
                openFile(file);
            });
            btnShare.setOnClickListener(v -> {
                if (file == null || !file.exists()) {
                    toast("文件已被移动或清理");
                    return;
                }
                shareFile(file);
            });
        }

        private LinearLayout hRow() {
            LinearLayout l = new LinearLayout(MainActivity.this);
            l.setOrientation(LinearLayout.HORIZONTAL);
            l.setGravity(Gravity.CENTER_VERTICAL);
            return l;
        }

        private TextView label(String s, int colorAttr, int sizeAttr, boolean bold) {
            TextView t = new TextView(MainActivity.this);
            t.setText(s);
            t.setTextColor(attr(colorAttr));
            t.setTextSize(TypedValue.COMPLEX_UNIT_PX, dimPx(sizeAttr));
            if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            return t;
        }

        private TextView ghost(String s, int colorAttr) {
            TextView t = new TextView(MainActivity.this);
            t.setText(s);
            t.setGravity(Gravity.CENTER);
            t.setTextColor(attr(colorAttr));
            t.setTextSize(TypedValue.COMPLEX_UNIT_PX, dimPx(R.attr.dT2));
            t.setBackground(getDrawable(R.drawable.bg_ghost));
            t.setClickable(true);
            t.setFocusable(true);
            return t;
        }

        void onMirror(String name, int attempt) {
            srcMirror = name;
            srcAttempt = attempt;
            paintSrc();
        }

        void onPlan(int threads) {
            srcThreads = threads;
            paintSrc();
        }

        private void paintSrc() {
            if (srcMirror.isEmpty()) return;
            String how = srcThreads > 1 ? srcThreads + " 线程" : "单线程";
            src.setText("via " + srcMirror + " · " + how
                    + (srcAttempt > 1 ? " · 第 " + srcAttempt + " 次尝试" : " · 断点续传"));
        }

        void onState(DownloadEngine.State st, String msg) {
            switch (st) {
                case QUEUED:
                    src.setText("排队中 · 等前面的任务腾出位置");
                    btnPause.setEnabled(false);
                    break;
                case RUNNING:
                    if (srcMirror.isEmpty()) src.setText("正在下载…");
                    else paintSrc();
                    btnPause.setEnabled(true);
                    btnPause.setText("暂停");
                    break;
                case PAUSED:
                    src.setText("已暂停 · 继续后从断点接上");
                    btnPause.setText("继续");
                    spd.setText("0.0 MB/s");
                    break;
                case CANCELLED:
                    src.setText("已取消");
                    btnPause.setEnabled(false);
                    btnCancel.setEnabled(false);
                    break;
                case FAILED:
                    src.setText(msg);
                    btnPause.setEnabled(false);
                    btnCancel.setEnabled(false);
                    HistoryStore.Item bad = new HistoryStore.Item(
                            name.getText().toString(), info.repo, "", "failed", 0, 0);
                    bad.url = info.url;
                    HistoryStore.add(MainActivity.this, bad);
                    toast(msg);
                    break;
                default:
                    break;
            }
            updateTaskCount();
        }

        void paint(long gotBytes, long total, double mbps) {
            DownloadEngine.Task t = engine.get(id);
            long sec = t == null ? 0 : t.elapsedSec();
            long now = System.currentTimeMillis();
            int p = total > 0 ? (int) Math.min(100, gotBytes * 100 / total) : -1;
            // 墨水屏刷新慢：非整百分比变化时最多 1.1 秒刷一次，减少残影
            if (mono && p == lastPct && now - lastPaint < 1100) return;
            lastPaint = now;
            lastPct = p;
            lastSpeed = mbps;

            spd.setText(String.format(Locale.US, "%.1f MB/s", mbps));
            elapsed.setText("已用 " + clock(sec));
            if (total > 0) {
                bar.setIndeterminate(false);
                bar.setProgress(p);
                pct.setText(p + "%");
                got.setText(String.format(Locale.US, "%.1f / %.1f MB",
                        gotBytes / 1048576.0, total / 1048576.0));
                double avg = sec > 0 ? (gotBytes / 1048576.0) / sec : mbps;
                double speed = avg > 0.05 ? avg : mbps;
                if (speed > 0.05) {
                    eta.setText("剩余 " + clock((long) ((total - gotBytes) / (speed * 1048576.0))));
                } else {
                    eta.setText("剩余 --:--");
                }
            } else {
                bar.setIndeterminate(true);
                pct.setText("—");
                got.setText(String.format(Locale.US, "%.1f MB", gotBytes / 1048576.0));
                eta.setText("剩余 --:--");
            }
        }

        void onDone(File f, String mirrorName) {
            file = f;
            DownloadEngine.Task t = engine.get(id);
            long sec = Math.max(1, t == null ? 0 : t.elapsedSec());
            pct.setText("100%");
            bar.setIndeterminate(false);
            bar.setProgress(100);
            spd.setText(String.format(Locale.US, "%.1f MB/s", f.length() / 1048576.0 / sec));
            got.setText(String.format(Locale.US, "%.1f / %.1f MB",
                    f.length() / 1048576.0, f.length() / 1048576.0));
            eta.setText("剩余 00:00");
            elapsed.setText("已用 " + clock(sec));
            src.setText("已完成 · " + FileMime.label(f.getName()));
            btnPause.setEnabled(false);
            btnCancel.setEnabled(false);
            btnOpen.setText(f.getName().toLowerCase(Locale.US).endsWith(".apk") ? "安装" : "打开");
            actionRow.setVisibility(View.VISIBLE);

            HistoryStore.Item item = new HistoryStore.Item(f.getName(), info.repo,
                    mirrorName, "done", f.length(), lastSpeed);
            item.url = info.url;
            HistoryStore.add(MainActivity.this, item);
            if (info.watchRepo != null) {
                WatchStore.markDownloaded(MainActivity.this, info.watchRepo, info.tag, f.getName());
                if (watch != null) watch.reload();
            }
            toast("下载完成，可直接安装或交给其他应用打开");
            renderHistory();
            updateTaskCount();
        }
    }

    // ---------------------------------------------------------------- 打开 / 安装

    private static boolean isApk(String name) {
        return name != null && name.toLowerCase(Locale.US).endsWith(".apk");
    }

    /**
     * 安装包必须真是个 zip：镜像偶尔会把错误页（HTML / 限流提示）原样存成 .apk，
     * 交给安装器只会得到一句「解析程序包时出现问题」，看不出到底哪错了，这里先自己认出来。
     */
    private static boolean looksLikeApk(File f) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            byte[] head = new byte[4];
            int n = in.read(head);
            if (n < 4) return false;
            return head[0] == 'P' && head[1] == 'K'
                    && (head[2] == 3 || head[2] == 5 || head[2] == 7);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 把 content:// 的读权限显式发给所有可能接手这个 Intent 的应用。
     * 正常走 FLAG_GRANT_READ_URI_PERMISSION 就够，但个别 ROM 的安装器不认，
     * 只认自己拿到的授权，这里补一刀，免得它读到空流再报解析失败。
     */
    private void grantToHandlers(Intent intent, Uri uri) {
        try {
            for (android.content.pm.ResolveInfo ri :
                    getPackageManager().queryIntentActivities(intent, 0)) {
                if (ri.activityInfo != null && ri.activityInfo.packageName != null) {
                    grantUriPermission(ri.activityInfo.packageName, uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void showBrokenApk(File file) {
        new AlertDialog.Builder(this)
                .setTitle("这个文件不是安装包")
                .setMessage(file.getName() + " 下载到的内容看着像网页或提示页，不是真正的 APK。"
                        + "多半是镜像返回了限流页，重新下载一次、或者换个镜像再试。")
                .setPositiveButton("知道了", null)
                .show();
    }

    private void openFile(File file) {
        if (file == null || !file.isFile()) {
            toast("文件已经不在了，可能被清理过");
            return;
        }
        // APK 要先有「安装未知应用」的授权，否则点了没反应
        if (isApk(file.getName())
                && Build.VERSION.SDK_INT >= 26
                && !getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(this)
                    .setTitle("需要安装权限")
                    .setMessage("系统还没允许本应用安装应用。打开「安装未知应用」后返回，再点一次安装即可。")
                    .setPositiveButton("去设置", (d, w) -> {
                        try {
                            Intent i = new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
                            i.setData(Uri.parse("package:" + getPackageName()));
                            startActivity(i);
                        } catch (Exception e) {
                            toast("请到系统设置里手动允许安装未知应用");
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        if (file.length() <= 0) {
            toast("文件是空的，重新下载一次吧");
            return;
        }
        if (isApk(file.getName()) && !looksLikeApk(file)) {
            showBrokenApk(file);
            return;
        }

        String mime = FileMime.of(file.getName());
        Uri uri = FileBridge.uriFor(file);
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, mime);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        // 有些 ROM 的安装器只看 ClipData 里的授权，两个都带上
        i.setClipData(ClipData.newRawUri(file.getName(), uri));
        grantToHandlers(i, uri);
        try {
            startActivity(i);
        } catch (Exception e) {
            Intent fallback = new Intent(Intent.ACTION_VIEW);
            fallback.setDataAndType(uri, "*/*");
            fallback.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            fallback.setClipData(ClipData.newRawUri(file.getName(), uri));
            grantToHandlers(fallback, uri);
            try {
                startActivity(Intent.createChooser(fallback, "选择打开方式"));
            } catch (Exception e2) {
                toast("没有可以打开 " + mime + " 的应用");
            }
        }
    }

    private void shareFile(File file) {
        if (file == null || !file.isFile()) {
            toast("文件已经不在了，可能被清理过");
            return;
        }
        Uri uri = FileBridge.uriFor(file);
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, FileMime.of(file.getName()));
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        i.setClipData(ClipData.newRawUri(file.getName(), uri));
        grantToHandlers(i, uri);
        try {
            startActivity(Intent.createChooser(i, "用其他应用打开"));
        } catch (Exception e) {
            toast("没有可以处理该文件的应用");
        }
    }

    // ---------------------------------------------------------------- 记录

    private void setupHistory() {
        tint((ImageView) findViewById(R.id.btnClear), cText2);

        findViewById(R.id.btnHistoryPick).setOnClickListener(v -> setHistoryPicking(!historyPicking));
        findViewById(R.id.historySub).setOnClickListener(v -> openHistoryMaxDialog());
        findViewById(R.id.btnClear).setOnClickListener(v -> confirmClearHistory());

        findViewById(R.id.btnSelectAll).setOnClickListener(v -> {
            List<HistoryStore.Item> shown = shownHistory();
            boolean allOn = !shown.isEmpty();
            for (HistoryStore.Item it : shown) {
                if (!historySel.contains(HistoryStore.key(it))) {
                    allOn = false;
                    break;
                }
            }
            historySel.clear();
            if (!allOn) for (HistoryStore.Item it : shown) historySel.add(HistoryStore.key(it));
            paintHistory();
        });

        findViewById(R.id.btnDeleteSel).setOnClickListener(v -> confirmDeleteSelected());
    }

    /** 当前会被显示出来的那些记录：按设置的上限截断 */
    private List<HistoryStore.Item> shownHistory() {
        List<HistoryStore.Item> all = HistoryStore.load(this);
        int max = Settings.historyMax(this);
        return all.size() > max ? new ArrayList<>(all.subList(0, max)) : all;
    }

    private void renderHistory() {
        historyList.removeAllViews();
        historyPainters.clear();

        List<HistoryStore.Item> all = HistoryStore.load(this);
        int max = Settings.historyMax(this);
        final List<HistoryStore.Item> shown =
                all.size() > max ? new ArrayList<>(all.subList(0, max)) : all;

        // 选中的东西可能已经被删了，清掉不存在的 key
        Set<String> alive = new HashSet<>();
        for (HistoryStore.Item it : all) alive.add(HistoryStore.key(it));
        historySel.retainAll(alive);
        if (historyPicking && shown.isEmpty()) historyPicking = false;

        TextView sub = findViewById(R.id.historySub);
        if (sub != null) {
            sub.setText(all.isEmpty()
                    ? "共 0 条 · 只保存在本机"
                    : "共 " + all.size() + " 条 · 显示最近 " + shown.size() + " 条 · 点此改");
        }

        paintHistoryChrome();

        if (shown.isEmpty()) {
            historyList.addView(buildEmptyHistory());
            return;
        }

        String lastDay = "";
        for (HistoryStore.Item it : shown) {
            String day = dayLabel(it.time);
            if (!day.equals(lastDay)) {
                TextView d = new TextView(this);
                LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                dlp.topMargin = dp(18);
                dlp.bottomMargin = dp(6);
                d.setLayoutParams(dlp);
                d.setText(day);
                d.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                d.setTextColor(cText3);
                d.setLetterSpacing(0.12f);
                historyList.addView(d);
                lastDay = day;
            }
            historyList.addView(buildHistoryRow(it));
        }

        // 被上限挡住的部分说清楚，别让人以为记录丢了
        if (all.size() > shown.size()) {
            TextView more = new TextView(this);
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            mlp.topMargin = dp(14);
            more.setLayoutParams(mlp);
            more.setGravity(Gravity.CENTER);
            more.setText("还有 " + (all.size() - shown.size())
                    + " 条更早的记录没显示 · 点上方「共 " + all.size() + " 条」可调大上限");
            more.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            more.setTextColor(cText3);
            more.setPadding(dp(8), dp(6), dp(8), dp(6));
            historyList.addView(more);
        }
    }

    /** 顶部按钮文案 + 底部操作条的显隐，勾选变化时只刷这部分 */
    private void paintHistoryChrome() {
        TextView pick = findViewById(R.id.btnHistoryPick);
        if (pick != null) pick.setText(historyPicking ? "取消" : "选择");

        View bar = findViewById(R.id.historyBar);
        if (bar != null) bar.setVisibility(historyPicking ? View.VISIBLE : View.GONE);
        applyHistoryListInset();

        List<HistoryStore.Item> shown = shownHistory();
        boolean allOn = !shown.isEmpty();
        for (HistoryStore.Item it : shown) {
            if (!historySel.contains(HistoryStore.key(it))) {
                allOn = false;
                break;
            }
        }
        TextView allBtn = findViewById(R.id.btnSelectAll);
        if (allBtn != null) allBtn.setText(allOn ? "取消全选" : "全选");

        TextView del = findViewById(R.id.btnDeleteSel);
        if (del != null) {
            int n = historySel.size();
            del.setText(n == 0 ? "删除选中（先勾选）" : "删除选中 " + n + " 条");
            del.setEnabled(n > 0);
            del.setAlpha(n > 0 ? 1f : 0.45f);
        }
    }

    /**
     * 底部选择条是浮在列表上的，得给列表补一块同样高的底部留白，
     * 否则滚到底时最后一条记录正好被它盖住——截图里就是这样。
     */
    private void applyHistoryListInset() {
        View sv = findViewById(R.id.historyScroll);
        if (sv == null) return;
        int base = dp(16);
        int extra = 0;
        View bar = findViewById(R.id.historyBar);
        if (historyPicking && bar != null) {
            int h = bar.getMeasuredHeight();
            if (h <= 0) {
                // 第一次显示时还没量过，先按 10+40+10 估，布局完再照真实高度补一次
                h = dp(60);
                bar.post(this::applyHistoryListInset);
            }
            extra = h + dp(12);
        }
        int bottom = base + extra;
        if (sv.getPaddingBottom() != bottom) {
            sv.setPadding(sv.getPaddingLeft(), sv.getPaddingTop(), sv.getPaddingRight(), bottom);
        }
    }

    private void paintHistory() {
        for (Runnable r : new ArrayList<>(historyPainters)) r.run();
        paintHistoryChrome();
    }

    private void setHistoryPicking(boolean on) {
        if (historyPicking == on) return;
        historyPicking = on;
        historySel.clear();
        renderHistory();
    }

    private void toggleHistory(String key) {
        if (!historySel.remove(key)) historySel.add(key);
        paintHistory();
    }

    /** 历史列表显示多少条：历史页副标题和设置页都能进这里 */
    private void openHistoryMaxDialog() {
        askNumber("历史记录显示条数", "列表最多显示多少条，调小不会删掉记录",
                Settings.historyMax(this), Settings.HIST_MIN, Settings.HIST_MAX, "条",
                new int[]{10, 20, 50, 100},
                n -> {
                    Settings.setHistoryMax(this, n);
                    renderHistory();
                    paintSettingValues();
                    toast("历史最多显示 " + n + " 条");
                });
    }

    private void confirmDeleteSelected() {
        if (historySel.isEmpty()) {
            toast("先勾选要删除的记录");
            return;
        }
        final List<HistoryStore.Item> targets = new ArrayList<>();
        for (HistoryStore.Item it : HistoryStore.load(this)) {
            if (historySel.contains(HistoryStore.key(it))) targets.add(it);
        }
        if (targets.isEmpty()) {
            toast("这些记录已经不在了");
            return;
        }
        askDeleteMode("删除 " + targets.size() + " 条记录", targets, false);
    }

    private void confirmClearHistory() {
        final List<HistoryStore.Item> all = HistoryStore.load(this);
        if (all.isEmpty()) {
            toast("还没有下载记录");
            return;
        }
        askDeleteMode("清空全部 " + all.size() + " 条记录", all, true);
    }

    /**
     * 删除前先问清楚要不要连文件一起删：
     * 记录删了可以重下，文件删了就真没了，所以两个选项都给清楚，别让用户猜。
     *
     * 注意：这里不能用 setItems + setMessage —— 两者同时设置时列表会被说明文字顶掉，
     * 弹窗里只剩一个「取消」，所以整块内容自己拼。
     */
    private void askDeleteMode(String title, final List<HistoryStore.Item> targets, final boolean all) {
        int files = 0;
        for (HistoryStore.Item it : targets) {
            if (historyFile(it) != null) files++;
        }
        final int fileCount = files;

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(2), dp(20), dp(6));

        TextView note = new TextView(this);
        note.setText(fileCount > 0
                ? fileCount + " 个文件还在手机里。一起删掉就找不回来了；只删记录的话，文件留在原处。"
                : "这些文件已经不在手机里了，两种删法都只会动记录。");
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        note.setTextColor(cText2);
        note.setLineSpacing(dp(4), 1f);
        box.addView(note);

        LinearLayout optKeep = deleteOption("只删记录",
                "文件留在手机原处，之后还能打开", false);
        LinearLayout optBoth = deleteOption("记录和文件一起删",
                fileCount > 0 ? "同时从手机里删掉 " + fileCount + " 个文件，无法恢复"
                        : "没有可删的文件，只会清掉记录", true);
        optKeep.setId(R.id.optKeepRecords);
        optBoth.setId(R.id.optDeleteFiles);

        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        olp.topMargin = dp(12);
        box.addView(optKeep, olp);

        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(8);
        box.addView(optBoth, blp);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(box)
                .setNegativeButton("取消", null)
                .create();

        optKeep.setOnClickListener(v -> {
            dialog.dismiss();
            doDelete(targets, false, all);
        });
        optBoth.setOnClickListener(v -> {
            dialog.dismiss();
            doDelete(targets, true, all);
        });

        dialog.show();
    }

    /** 删除弹窗里的一个选项：标题 + 一行说明，整块可点 */
    private LinearLayout deleteOption(String title, String desc, boolean danger) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackgroundResource(R.drawable.bg_ghost);
        row.setPadding(dp(14), dp(13), dp(14), dp(13));
        row.setClickable(true);
        row.setFocusable(true);
        row.setMinimumHeight(dp(56));
        pressFeedback(row);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
        t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setTextColor(danger ? cDanger : cText);
        row.addView(t);

        TextView d = new TextView(this);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = dp(3);
        d.setLayoutParams(dlp);
        d.setText(desc);
        d.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        d.setTextColor(cText2);   // 深色下 cText3 太灰，这一行是要人认真读的
        row.addView(d);

        return row;
    }

    /** 按下时轻微变淡，弹层里的小方块才有「能点」的手感 */
    private void pressFeedback(final View v) {
        v.setOnTouchListener((view, ev) -> {
            if (ev.getAction() == MotionEvent.ACTION_DOWN) {
                view.setAlpha(0.86f);
            } else if (ev.getAction() == MotionEvent.ACTION_UP
                    || ev.getAction() == MotionEvent.ACTION_CANCEL) {
                view.setAlpha(1f);
            }
            return false;
        });
    }

    private void doDelete(final List<HistoryStore.Item> targets, boolean withFiles, boolean all) {
        int files = 0, failed = 0;
        if (withFiles) {
            for (HistoryStore.Item it : targets) {
                File f = historyFile(it);
                if (f == null) continue;
                if (f.delete()) {
                    files++;
                } else {
                    failed++;
                }
            }
        }

        int rec = all ? HistoryStore.clear(this) : HistoryStore.remove(this, targets);

        historySel.clear();
        if (HistoryStore.count(this) == 0) historyPicking = false;
        renderHistory();

        if (!withFiles) {
            toast("已删除 " + rec + " 条记录 · 文件保留");
            return;
        }
        String msg = "已删除 " + rec + " 条记录 · " + files + " 个文件";
        if (failed > 0) msg += "，" + failed + " 个删不掉";
        if (failed > 0) msg += "（可能被其它应用占用）";
        toast(msg);
    }

    /** 记录对应的文件可能留在几个候选目录里（换过保存路径），挨个找 */
    private File historyFile(HistoryStore.Item it) {
        if (it.file == null || it.file.isEmpty()) return null;
        File[] dirs = {downloadDir(), publicDownloadDir(), privateDownloadDir()};
        for (File d : dirs) {
            if (d == null) continue;
            File f = new File(d, it.file);
            if (f.isFile()) return f;
        }
        return null;
    }

    /**
     * 长文件名 / 长标签：最多两行，仍放不下就往下缩字号（缩到原来的 3/4）。
     * 字号按当前主题的实际大小算，黑白主题字号更大也不会被压小。
     */
    private void fitTwoLines(TextView v) {
        fitLines(v, 2);
    }

    /** lines = 最多几行；超出才截，末尾留省略号说明还有 */
    private void fitLines(TextView v, int lines) {
        v.setSingleLine(false);
        v.setMaxLines(lines);
        v.setEllipsize(android.text.TextUtils.TruncateAt.END);
        v.setLineSpacing(0, 1.08f);
        float sp = v.getTextSize() / getResources().getDisplayMetrics().scaledDensity;
        int max = Math.max(9, Math.round(sp));
        int min = Math.max(8, Math.round(sp * 0.78f));
        if (min < max) {
            v.setAutoSizeTextTypeUniformWithConfiguration(min, max, 1,
                    TypedValue.COMPLEX_UNIT_SP);
        }
    }

    /** 勾选框 / 选中描边都要现画，给个圆角矩形的快捷构造 */
    private android.graphics.drawable.GradientDrawable roundBg(int fill, int stroke, float radiusDp) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }

    private View buildEmptyHistory() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(0, dp(56), 0, dp(20));

        LinearLayout circle = new LinearLayout(this);
        circle.setGravity(Gravity.CENTER);
        circle.setBackgroundResource(R.drawable.bg_dashed_circle);
        circle.setLayoutParams(new LinearLayout.LayoutParams(dp(76), dp(76)));
        ImageView ic = new ImageView(this);
        ic.setImageResource(R.drawable.ic_empty_dl);
        tint(ic, cText3);
        circle.addView(ic, new LinearLayout.LayoutParams(dp(30), dp(30)));
        box.addView(circle);

        TextView t = new TextView(this);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(16);
        t.setLayoutParams(tlp);
        t.setText("还没有下载记录");
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(cText);
        box.addView(t);

        TextView d = new TextView(this);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(230),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = dp(6);
        d.setLayoutParams(dlp);
        d.setGravity(Gravity.CENTER);
        d.setText("去下载台粘一个 GitHub 链接，测速后会留下每个任务的速度与镜像来源。");
        d.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        d.setTextColor(cText3);
        d.setLineSpacing(dp(3), 1f);
        box.addView(d);
        return box;
    }

    private View buildHistoryRow(final HistoryStore.Item it) {
        final boolean done = "done".equals(it.state);
        final String key = HistoryStore.key(it);
        final boolean picking = historyPicking;
        final boolean picked = historySel.contains(key);

        final LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setTag("hist:" + key);
        card.setBackgroundResource(R.drawable.bg_card);
        card.setPadding(dp(14), dp(13), dp(14), dp(13));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(8);
        card.setLayoutParams(clp);

        final TextView box;
        if (picking) {
            box = new TextView(this);
            box.setGravity(Gravity.CENTER);
            box.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            box.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            box.setTextColor(cOnPrimary);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(22), dp(22));
            blp.setMarginEnd(dp(11));
            card.addView(box, blp);
        } else {
            box = null;
        }

        ImageView ic = new ImageView(this);
        ic.setLayoutParams(new LinearLayout.LayoutParams(dp(38), dp(38)));
        ic.setBackgroundResource(R.drawable.bg_ico_box);
        ic.setPadding(dp(10), dp(10), dp(10), dp(10));
        ic.setImageResource(done ? R.drawable.ic_check
                : "failed".equals(it.state) ? R.drawable.ic_error : R.drawable.ic_close);
        tint(ic, done ? cPrimary : cDanger);
        card.addView(ic);

        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        mlp.setMarginStart(dp(12));
        main.setLayoutParams(mlp);

        TextView f = new TextView(this);
        f.setText(it.file);
        f.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        f.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        f.setTextColor(cText);
        fitLines(f, 3);
        main.addView(f);

        TextView sub = new TextView(this);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(4);
        sub.setLayoutParams(slp);
        String sizeText = it.size > 0 ? String.format(Locale.US, "%.1f MB · ", it.size / 1048576.0) : "";
        String stateText = done ? "" : ("failed".equals(it.state) ? "失败 · " : "已取消 · ");
        sub.setText(it.repo + " · " + stateText + sizeText + (it.mirror.isEmpty() ? "—" : it.mirror));
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        sub.setTextColor(cText3);
        main.addView(sub);
        card.addView(main);

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.END);

        TextView sp = new TextView(this);
        sp.setText(it.speed > 0 ? String.format(Locale.US, "%.1f MB/s", it.speed) : "—");
        sp.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        sp.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        sp.setTextColor(done ? cPrimary : cText3);
        sp.setGravity(Gravity.END);
        right.addView(sp);

        TextView tm = new TextView(this);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(3);
        tm.setLayoutParams(tlp);
        tm.setText(new SimpleDateFormat("HH:mm", Locale.US).format(new Date(it.time)));
        tm.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tm.setTextColor(cText3);
        tm.setGravity(Gravity.END);
        right.addView(tm);
        card.addView(right);

        // 勾选态只重画这一行，不重建整个列表，滚动位置才不会跳
        Runnable paint = () -> {
            boolean on = historySel.contains(key);
            if (box != null) {
                box.setText(on ? "✓" : "");
                box.setBackground(on ? roundBg(cPrimary, 0, 6) : roundBg(0, cLine, 6));
            }
            if (on) card.setBackground(roundBg(attr(R.attr.cSurface), cPrimary, 20));
            else card.setBackgroundResource(R.drawable.bg_card);
        };
        historyPainters.add(paint);
        paint.run();

        card.setOnClickListener(v -> {
            if (historyPicking) {
                toggleHistory(key);
                return;
            }
            File file = historyFile(it);
            if (file != null) {
                openFile(file);
                return;
            }
            if (it.url != null && it.url.length() > 0) {
                urlInput.setText(it.url);
                switchTab(TAB_HOME);
                toast("文件不在了，链接已回填");
            } else {
                toast("文件已被清理");
            }
        });

        // 长按直接进选择模式并勾上这条，省一次点「选择」
        card.setOnLongClickListener(v -> {
            if (!historyPicking) {
                historyPicking = true;
                historySel.clear();
            }
            if (!historySel.contains(key)) historySel.add(key);
            renderHistory();
            return true;
        });

        return card;
    }

    private String dayLabel(long time) {
        SimpleDateFormat key = new SimpleDateFormat("yyyyMMdd", Locale.US);
        SimpleDateFormat show = new SimpleDateFormat("MM-dd", Locale.US);
        String today = key.format(new Date());
        String that = key.format(new Date(time));
        if (today.equals(that)) return "今天";
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_YEAR, -1);
        if (key.format(c.getTime()).equals(that)) return "昨天";
        return show.format(new Date(time));
    }

    // ---------------------------------------------------------------- 镜像页

    /**
     * 设置里的探测分片存的是 KB，整数 MB 就按 MB 说，别写成「2048 KB」。
     */
    private String chunkLabel() {
        int kb = Settings.chunkKB(this);
        return kb % 1024 == 0 ? (kb / 1024) + " MB" : kb + " KB";
    }

    /** 镜像页顶部说明里的分片大小要跟着设置走，不能写死 */
    private void paintMirrorNote() {
        if (mirrorNote == null) return;
        mirrorNote.setText("逐个发 " + chunkLabel() + " Range 请求，得分 = 速度 70% + 延迟 30%。"
                + "多个文件可同时下载，共用这份排名；失败自动顺延到下一个镜像。");
    }

    /** 改完分片设置后，首页已经跑完的测速结果也要换成新的分片说法 */
    private void repaintResultMeta() {
        for (int i = 0; i < rows.size() && i < results.size(); i++) {
            SpeedTester.Result r = results.get(i);
            if (!r.ok) continue;
            rows.get(i).meta.setText(String.format(Locale.US,
                    "得分 %d · Range %s", (int) r.score(), chunkLabel()));
        }
    }

    private void setupMirrors() {
        tint(findViewById(R.id.btnAddMirror), cOnPrimary);
        tint(findViewById(R.id.icInfo), cAccent);

        addMode = prefs.getString("addMode", Mirror.MODE_PREFIX);
        findViewById(R.id.addModePrefix).setOnClickListener(v -> {
            addMode = Mirror.MODE_PREFIX;
            prefs.edit().putString("addMode", addMode).apply();
            paintAddMode();
        });
        findViewById(R.id.addModePath).setOnClickListener(v -> {
            addMode = Mirror.MODE_GH_PATH;
            prefs.edit().putString("addMode", addMode).apply();
            paintAddMode();
        });
        paintAddMode();

        findViewById(R.id.btnAddMirror).setOnClickListener(v -> addCustomMirror());
        findViewById(R.id.btnRestoreMirrors).setOnClickListener(v -> {
            mirrors = MirrorCatalog.restoreBuiltins(this, mirrors);
            invalidateRank();
            renderMirrorPrefs();
            toast("已恢复内置镜像，可继续用 ↑↓ 调顺序");
        });
    }

    private void paintAddMode() {
        TextView a = findViewById(R.id.addModePrefix);
        TextView b = findViewById(R.id.addModePath);
        boolean path = Mirror.MODE_GH_PATH.equals(addMode);
        styleChip(a, !path);
        styleChip(b, path);
        customHint.setText(path
                ? "拼 GitHub 路径：如 https://xget.xi-xu.me/gh/ → 后面接 owner/repo/releases/...（自动去掉 https://github.com/）"
                : "拼完整链接：如 https://ghfast.top/ → 后面接完整的 https://github.com/owner/repo/...");
    }

    private void styleChip(TextView c, boolean on) {
        if (on) {
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
            g.setColor(cPrimary);
            g.setCornerRadius(dp(18));
            c.setBackground(g);
            c.setTextColor(cOnPrimary);
            c.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        } else {
            c.setBackground(getDrawable(R.drawable.bg_chip));
            c.setTextColor(attr(R.attr.cChipText));
            c.setTypeface(Typeface.DEFAULT);
        }
    }

    private void addCustomMirror() {
        String raw = customInput.getText().toString().trim();
        if (raw.isEmpty()) {
            toast("填一个镜像域名");
            return;
        }
        if (!raw.startsWith("http://") && !raw.startsWith("https://")) raw = "https://" + raw;
        String base = raw.endsWith("/") ? raw : raw + "/";
        String name = raw.replace("https://", "").replace("http://", "");
        if (name.endsWith("/")) name = name.substring(0, name.length() - 1);
        for (Mirror m : mirrors) {
            if (m.base.equalsIgnoreCase(base)) {
                toast("这个镜像已经在列表里");
                return;
            }
        }
        Mirror m = new Mirror(name, base, addMode);
        mirrors.add(m);
        MirrorCatalog.save(this, mirrors);
        customInput.setText("");
        invalidateRank();
        renderMirrorPrefs();
        toast("已添加 " + name + " · " + m.modeLabel());
    }

    private void renderMirrorPrefs() {
        paintMirrorNote();
        mirrorPrefs.removeAllViews();
        for (int i = 0; i < mirrors.size(); i++) {
            final int idx = i;
            final Mirror m = mirrors.get(i);

            // 每行 = 内容行 + 1dp 分隔线，作为 mirrorPrefs 的一个 child
            LinearLayout wrap = new LinearLayout(this);
            wrap.setOrientation(LinearLayout.VERTICAL);
            wrap.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(8), 0, dp(8));
            row.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            // 左侧拖拽手柄：按住就能上下拖，不用一格一格点箭头
            ImageView handle = new ImageView(this);
            handle.setImageResource(R.drawable.ic_drag);
            handle.setPadding(dp(11), dp(11), dp(11), dp(11));
            handle.setContentDescription("按住拖动排序");
            handle.setClickable(true);
            tint(handle, cText3);
            row.addView(handle, new LinearLayout.LayoutParams(dp(44), dp(52)));
            bindDragHandle(handle, wrap);

            LinearLayout k = new LinearLayout(this);
            k.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            klp.setMarginStart(dp(2));
            k.setLayoutParams(klp);

            TextView name = new TextView(this);
            name.setText(m.name);
            name.setTextSize(TypedValue.COMPLEX_UNIT_PX, dimPx(R.attr.dT2));
            name.setTextColor(m.enabled ? cText : cText3);
            name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            k.addView(name);

            TextView sub = new TextView(this);
            sub.setText(m.isDirect() ? "直接访问 github.com，通常最慢"
                    : m.base + " · " + m.modeLabel());
            sub.setTextSize(TypedValue.COMPLEX_UNIT_PX, dimPx(R.attr.dT0));
            sub.setTextColor(cText3);
            sub.setSingleLine(true);
            sub.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            slp.topMargin = dp(2);
            sub.setLayoutParams(slp);
            k.addView(sub);

            LinearLayout bottom = new LinearLayout(this);
            bottom.setOrientation(LinearLayout.HORIZONTAL);
            bottom.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            blp.topMargin = dp(3);
            bottom.setLayoutParams(blp);

            TextView status = new TextView(this);
            status.setText(m.statusText());
            status.setTextSize(TypedValue.COMPLEX_UNIT_PX, dimPx(R.attr.dT0));
            status.setTextColor(m.failStreak >= 3 ? cDanger : cText3);
            bottom.addView(status, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView del = new TextView(this);
            del.setText("删除");
            del.setGravity(Gravity.CENTER);
            del.setMinWidth(dp(48));
            del.setHeight(dp(44));
            del.setTextSize(TypedValue.COMPLEX_UNIT_PX, dimPx(R.attr.dT1));
            del.setTextColor(cDanger);
            del.setClickable(true);
            del.setFocusable(true);
            del.setOnClickListener(v -> confirmDelete(m, idx));
            bottom.addView(del);
            k.addView(bottom);
            row.addView(k);

            Switch sw = new Switch(this);
            sw.setChecked(m.enabled);
            sw.setShowText(false);
            styleSwitch(sw);
            sw.setOnCheckedChangeListener((buttonView, isChecked) -> {
                m.enabled = isChecked;
                MirrorCatalog.save(MainActivity.this, mirrors);
                invalidateRank();
                updateMirrorCount();
                renderMirrorPrefs();
            });
            row.addView(sw);
            wrap.addView(row);

            View line = new View(this);
            line.setBackgroundColor(cLine);
            wrap.addView(line, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
            mirrorPrefs.addView(wrap);
        }
        updateRowDividers();
        updateMirrorCount();
    }

    private void updateRowDividers() {
        int n = mirrorPrefs.getChildCount();
        for (int i = 0; i < n; i++) {
            View wrap = mirrorPrefs.getChildAt(i);
            if (!(wrap instanceof ViewGroup)) continue;
            ViewGroup g = (ViewGroup) wrap;
            if (g.getChildCount() < 2) continue;
            g.getChildAt(g.getChildCount() - 1)
                    .setVisibility(i == n - 1 ? View.INVISIBLE : View.VISIBLE);
        }
    }

    // ---------------------------------------------------------------- 拖拽排序

    /** 按住 ≡ 手柄上下拖动：被拖的行跟手，其余行实时让位，松手落定 */
    private void bindDragHandle(final View handle, final View row) {
        handle.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    dragRow = row;
                    dragMoved = false;
                    dragIndex = mirrorPrefs.indexOfChild(row);
                    dragTarget = dragIndex;
                    dragHeight = heightOf(row);
                    dragGrabOffset = fingerInParent(ev.getRawY()) - slotTop(dragIndex);
                    row.setElevation(dp(12));
                    row.setAlpha(0.97f);
                    if (mirrorScroll != null) {
                        // 拖拽期间不让 ScrollView 抢事件，滚动交给边缘自动滚
                        mirrorScroll.requestDisallowInterceptTouchEvent(true);
                    }
                    v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    return true;

                case MotionEvent.ACTION_MOVE: {
                    if (dragRow == null) return false;
                    dragMoved = true;
                    autoScrollDuringDrag(ev.getRawY());
                    float top = fingerInParent(ev.getRawY()) - dragGrabOffset;
                    dragRow.setTranslationY(top - slotTop(dragIndex));
                    int target = targetIndexFor(top);
                    if (target != dragTarget) {
                        dragTarget = target;
                        shiftOtherRows();
                    }
                    return true;
                }

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (dragRow == null) return false;
                    endDrag();
                    return true;

                default:
                    return false;
            }
        });
    }

    /** 手指在 mirrorPrefs 内容坐标系里的位置（已扣掉 padding 和滚动） */
    private float fingerInParent(float rawY) {
        int[] loc = new int[2];
        mirrorPrefs.getLocationOnScreen(loc);
        return rawY - loc[1] - mirrorPrefs.getPaddingTop();
    }

    /** 第 index 行的槽位 top：按顺序累加高度，不依赖 layout 是否已完成 */
    private int slotTop(int index) {
        int top = 0;
        int n = mirrorPrefs.getChildCount();
        for (int i = 0; i < index && i < n; i++) {
            top += heightOf(mirrorPrefs.getChildAt(i));
        }
        return top;
    }

    private int heightOf(View v) {
        return v == null ? 0 : (v.getHeight() > 0 ? v.getHeight() : v.getMeasuredHeight());
    }

    /** 被拖行中心越过了哪一行的中点，就落到那个位置 */
    private int targetIndexFor(float top) {
        int n = mirrorPrefs.getChildCount();
        if (dragIndex < 0 || n == 0) return dragIndex;
        float center = top + dragHeight / 2f;
        int target = dragIndex;
        for (int i = 0; i < n; i++) {
            if (i == dragIndex) continue;
            float c = slotTop(i) + heightOf(mirrorPrefs.getChildAt(i)) / 2f;
            if (i > dragIndex && center > c) {
                target = i;
            } else if (i < dragIndex && center < c) {
                target = i;
                break;
            }
        }
        return target;
    }

    /** 其余行滑开，给被拖的行腾位置 */
    private void shiftOtherRows() {
        int n = mirrorPrefs.getChildCount();
        for (int i = 0; i < n; i++) {
            View c = mirrorPrefs.getChildAt(i);
            if (c == dragRow) continue;
            int shift = 0;
            if (dragTarget > dragIndex && i > dragIndex && i <= dragTarget) {
                shift = -dragHeight;
            } else if (dragTarget < dragIndex && i >= dragTarget && i < dragIndex) {
                shift = dragHeight;
            }
            c.animate().cancel();
            c.animate().translationY(shift).setDuration(110).start();
        }
    }

    /** 拖到列表上下边缘时自动滚动，长列表也能拖到底 */
    private void autoScrollDuringDrag(float rawY) {
        if (mirrorScroll == null) return;
        int[] loc = new int[2];
        mirrorScroll.getLocationOnScreen(loc);
        int top = loc[1];
        int bottom = top + mirrorScroll.getHeight();
        int edge = dp(80);
        int step = dp(9);
        if (rawY < top + edge) {
            if (mirrorScroll.canScrollVertically(-1)) mirrorScroll.scrollBy(0, -step);
        } else if (rawY > bottom - edge) {
            if (mirrorScroll.canScrollVertically(1)) mirrorScroll.scrollBy(0, step);
        }
    }

    private void endDrag() {
        final View row = dragRow;
        final int from = dragIndex;
        final int to = dragTarget;
        final boolean moved = dragMoved;
        dragRow = null;
        dragIndex = -1;
        dragTarget = -1;

        row.setElevation(0);
        row.setAlpha(1f);
        row.setTranslationY(0);

        if (!moved || from < 0 || to < 0 || from == to) {
            for (int i = 0; i < mirrorPrefs.getChildCount(); i++) {
                mirrorPrefs.getChildAt(i).animate().cancel();
                mirrorPrefs.getChildAt(i).setTranslationY(0);
            }
            return;
        }

        Mirror m = mirrors.remove(from);
        mirrors.add(to, m);
        MirrorCatalog.save(this, mirrors);
        invalidateRank();
        renderMirrorPrefs();
        toast("顺序已保存 · 测速按这个顺序");
    }

    private void confirmDelete(final Mirror m, final int idx) {
        new android.app.AlertDialog.Builder(this)
                .setTitle("删除镜像")
                .setMessage("删除「" + m.name + "」？内置镜像可以用「恢复内置」找回来。")
                .setPositiveButton("删除", (d, w) -> {
                    MirrorCatalog.markRemoved(this, m.base);
                    mirrors.remove(idx);
                    MirrorCatalog.save(this, mirrors);
                    invalidateRank();
                    renderMirrorPrefs();
                    toast("已删除 " + m.name);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void updateMirrorCount() {
        int on = 0;
        for (Mirror m : mirrors) if (m.enabled) on++;
        mirrorCount.setText("已启用 " + on + " / " + mirrors.size());
        if (activeTab == TAB_HOME && results.isEmpty() && !testing) {
            mirrorHint.setText(on + " 个候选 · 逐个探测");
        }
    }

    // ---------------------------------------------------------------- 设置

    private void setupSettings() {
        buildFormatSection();
        setupSavePath();
        fillVersion();

        Switch failover = findViewById(R.id.swFailover);
        Switch resume = findViewById(R.id.swResume);
        Switch cache = findViewById(R.id.swCache);
        Switch multi = findViewById(R.id.swMulti);
        styleSwitch(failover);
        styleSwitch(resume);
        styleSwitch(cache);
        styleSwitch(multi);

        failover.setChecked(Settings.failover(this));
        resume.setChecked(Settings.resume(this));
        cache.setChecked(Settings.cacheRank(this));
        multi.setChecked(Settings.multiThread(this));

        failover.setOnCheckedChangeListener((v, on) -> {
            Settings.setFailover(this, on);
            applyEnginePrefs();
            paintSettingValues();
        });
        resume.setOnCheckedChangeListener((v, on) -> {
            Settings.setResume(this, on);
            applyEnginePrefs();
        });
        cache.setOnCheckedChangeListener((v, on) -> {
            Settings.setCacheRank(this, on);
            paintSettingValues();
        });
        multi.setOnCheckedChangeListener((v, on) -> {
            Settings.setMultiThread(this, on);
            applyEnginePrefs();
            paintSettingValues();
        });

        // 版本页的挑文件方式：放这里做成滑块，版本页自己不再显示切换条
        segWatchMode = findViewById(R.id.segWatchMode);
        watchModeSub = findViewById(R.id.watchModeSub);
        segWatchMode.setLabels("单个下载", "批量勾选");
        paintWatchMode();
        segWatchMode.setOnPickListener(i -> {
            boolean batch = i == 1;
            if (Settings.batchSelect(this) == batch) return;
            Settings.setBatchSelect(this, batch);
            if (watch != null) watch.clearPicks();   // 换模式了，之前勾的作废
            paintWatchMode();
            toast(batch ? "版本页改为批量勾选" : "版本页改为单个下载");
        });

        // 失败后最多顺延几个镜像：1~10 任意数字
        findViewById(R.id.rowFailoverMax).setOnClickListener(v -> {
            if (!Settings.failover(this)) {
                toast("先打开「失败自动换镜像」");
                return;
            }
            askNumber("最多重试几个镜像", "一个镜像失败后，按测速排名继续试几个",
                    Settings.failoverMax(this), Settings.TRY_MIN, Settings.TRY_MAX, "个",
                    new int[]{1, 2, 3, 5, 10},
                    n -> {
                        Settings.setFailoverMax(this, n);
                        applyEnginePrefs();
                        paintSettingValues();
                        toast(n == 1 ? "失败后只换 1 个镜像" : "失败后最多顺延 " + n + " 个镜像");
                    });
        });

        // 同时下载任务数：1~10 任意数字，直接输入
        findViewById(R.id.rowConcurrent).setOnClickListener(v -> askNumber(
                "同时下载任务数", "一次最多同时下载几个文件",
                Settings.concurrent(this), Settings.CONC_MIN, Settings.CONC_MAX, "个",
                new int[]{1, 2, 3, 5, 8, 10},
                n -> {
                    Settings.setConcurrent(this, n);
                    applyEnginePrefs();
                    paintSettingValues();
                    toast("并发任务数已设为 " + n);
                }));

        // 单个文件的连接数
        findViewById(R.id.rowThreads).setOnClickListener(v -> {
            if (!Settings.multiThread(this)) {
                toast("先打开「单文件多线程」");
                return;
            }
            askNumber("每个文件连接数", "把一个文件切成几段同时拉",
                    Settings.threads(this), Settings.THREADS_MIN, Settings.THREADS_MAX, "段",
                    new int[]{1, 2, 4, 6, 10},
                    n -> {
                        Settings.setThreads(this, n);
                        applyEnginePrefs();
                        paintSettingValues();
                        toast(n == 1 ? "已改为单连接" : "每个文件 " + n + " 段并发");
                    });
        });

        // 测速探测分片
        findViewById(R.id.rowChunk).setOnClickListener(v -> askNumber(
                "探测分片", "测速时每个镜像拉多少数据来估算速度",
                Settings.chunkKB(this), Settings.CHUNK_MIN, Settings.CHUNK_MAX, "KB",
                new int[]{128, 256, 512, 1024},
                n -> {
                    Settings.setChunkKB(this, n);
                    paintSettingValues();
                    paintMirrorNote();
                    repaintResultMeta();
                    if (activeTab == TAB_MIRROR) renderMirrorPrefs();
                    toast("探测分片已设为 " + n + " KB");
                }));

        // 单个镜像超时
        findViewById(R.id.rowTimeout).setOnClickListener(v -> askNumber(
                "单个镜像超时", "超过这个时间没测完，直接判负换下一个",
                Settings.timeoutSec(this), Settings.TIMEOUT_MIN, Settings.TIMEOUT_MAX, "秒",
                new int[]{5, 8, 12, 20},
                n -> {
                    Settings.setTimeoutSec(this, n);
                    paintSettingValues();
                    toast("单镜像超时已设为 " + n + " 秒");
                }));

        // 历史页显示多少条
        findViewById(R.id.rowHistoryMax).setOnClickListener(v -> openHistoryMaxDialog());

        // 测速结果复用周期
        findViewById(R.id.rowTtl).setOnClickListener(v -> {
            if (!Settings.cacheRank(this)) {
                toast("先打开「复用测速结果」");
                return;
            }
            askNumber("复用周期", "这段时间内再下载，直接沿用上次的镜像排名",
                    Settings.ttlMin(this), Settings.TTL_MIN, Settings.TTL_MAX, "分钟",
                    new int[]{5, 10, 30, 60},
                    n -> {
                        Settings.setTtlMin(this, n);
                        paintSettingValues();
                        toast("测速结果复用 " + n + " 分钟");
                    });
        });

        paintSettingValues();

        int[] chipIds = {R.id.themeAuto, R.id.themeDark, R.id.themeLight, R.id.themeMono};

        int[] modes = {0, 1, 2, 3};
        for (int i = 0; i < chipIds.length; i++) {
            final int mode = modes[i];
            TextView chip = findViewById(chipIds[i]);
            chip.setOnClickListener(v -> {
                if (engine.isBusy()) {
                    toast("下载中不能切换主题");
                    return;
                }
                prefs.edit().putInt("theme", mode).apply();
                recreate();
            });
            boolean on = themeMode == mode;
            if (on) {
                android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
                g.setColor(cPrimary);
                g.setCornerRadius(dp(10));
                chip.setBackground(g);
                chip.setTextColor(cOnPrimary);
                chip.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
            } else {
                chip.setBackground(getDrawable(R.drawable.bg_chip));
                chip.setTextColor(attr(R.attr.cChipText));
                chip.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.NORMAL);
            }
        }

        TextView sub = findViewById(R.id.themeSub);
        String now;
        if (themeMode == 0) {
            now = "自动跟随：本机 " + (mono ? "按墨水屏黑白显示" : "按浅色显示")
                    + "（4:3 小屏自动切黑白）";
        } else if (themeMode == 1) {
            now = "深色：深底亮字，夜间不刺眼";
        } else if (themeMode == 2) {
            now = "浅色：纸白底 + 墨绿主色，白天更清爽";
        } else {
            now = "黑白：纯黑纯白、2px 描边、关动画与波形，减少残影";
        }
        sub.setText(now);
    }

    // ---------------------------------------------------------------- 追踪格式（大范围）

    /**
     * 大范围：所有项目默认追踪哪些格式。
     * 只在这里统一改；项目卡片上的小范围只能从这里挑，而且只列该项目真实存在的格式。
     */
    private void buildFormatSection() {
        LinearLayout host = findViewById(R.id.fmtSection);
        if (host == null) return;
        host.removeAllViews();

        TextView title = new TextView(this);
        title.setText("追踪格式");
        title.setTextColor(attr(R.attr.cText));
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, dimPx(R.attr.dT3));
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setPadding(0, dp(4), 0, dp(10));
        host.addView(title);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(getDrawable(R.drawable.bg_card));
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.bottomMargin = dp(14);
        host.addView(card, clp);

        TextView t1 = new TextView(this);
        t1.setText("大范围：所有项目默认追踪这些格式");
        t1.setTextColor(attr(R.attr.cText));
        t1.setTextSize(TypedValue.COMPLEX_UNIT_PX, dimPx(R.attr.dT2));
        card.addView(t1);

        TextView t2 = new TextView(this);
        t2.setText("勾选或取消立刻生效。每个项目还能在卡片右上角单独收窄，"
                + "那里只会列出这个项目真实存在的格式。");
        t2.setTextColor(attr(R.attr.cText3));
        t2.setTextSize(TypedValue.COMPLEX_UNIT_PX, dimPx(R.attr.dT1));
        t2.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams t2lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        t2lp.topMargin = dp(3);
        card.addView(t2, t2lp);

        ChipFlow flow = new ChipFlow(this);
        flow.setGaps(dp(8), dp(8));
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.topMargin = dp(10);
        card.addView(flow, flp);

        List<String> on = Settings.globalFormats(this);
        for (String ext : Settings.formatMenu(this)) flow.addView(fmtChip(ext, on.contains(ext)));

        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        tools.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams toolsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        toolsLp.topMargin = dp(10);
        card.addView(tools, toolsLp);

        TextView add = fmtTool("+ 添加格式", true);
        add.setOnClickListener(v -> askAddFormat());
        tools.addView(add);

        TextView reset = fmtTool("恢复默认", false);
        reset.setOnClickListener(v -> {
            Settings.setGlobalFormats(this, java.util.Arrays.asList(Settings.DEFAULT_FORMATS));
            buildFormatSection();
            if (watch != null) watch.reload();
            toast("已恢复默认：" + String.join(" / ", Settings.DEFAULT_FORMATS).toUpperCase(Locale.US));
        });
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(36));
        rlp.leftMargin = dp(8);
        tools.addView(reset, rlp);

        TextView note = new TextView(this);
        note.setText("长按自定义格式可以删除；内置格式只能取消勾选。");
        note.setTextColor(attr(R.attr.cText3));
        note.setTextSize(TypedValue.COMPLEX_UNIT_PX, dimPx(R.attr.dT0));
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = dp(8);
        card.addView(note, nlp);
    }

    private TextView fmtChip(final String ext, final boolean on) {
        TextView c = new TextView(this);
        c.setText(Settings.fmtLabel(ext));
        c.setGravity(Gravity.CENTER);
        c.setMinWidth(dp(58));
        c.setHeight(dp(36));
        c.setPadding(dp(14), 0, dp(14), 0);
        c.setTextSize(TypedValue.COMPLEX_UNIT_PX, dimPx(R.attr.dT1));
        paintFmtChip(c, on);
        c.setOnClickListener(v -> toggleGlobalFormat(ext));
        c.setOnLongClickListener(v -> {
            if (Settings.isBuiltin(ext)) {
                toast("内置格式不能删，取消勾选即可");
                return true;
            }
            confirmRemoveFormat(ext);
            return true;
        });
        return c;
    }

    private void paintFmtChip(TextView c, boolean on) {
        if (on) {
            android.graphics.drawable.GradientDrawable g =
                    new android.graphics.drawable.GradientDrawable();
            g.setColor(attr(R.attr.cPrimary));
            g.setCornerRadius(dp(18));
            c.setBackground(g);
            c.setTextColor(attr(R.attr.cOnPrimary));
            c.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        } else {
            c.setBackground(getDrawable(R.drawable.bg_chip));
            c.setTextColor(attr(R.attr.cChipText));
            c.setTypeface(Typeface.DEFAULT);
        }
    }

    private TextView fmtTool(String text, boolean primary) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setGravity(Gravity.CENTER);
        v.setHeight(dp(36));
        v.setPadding(dp(14), 0, dp(14), 0);
        v.setTextSize(TypedValue.COMPLEX_UNIT_PX, dimPx(R.attr.dT1));
        if (primary) {
            v.setBackground(getDrawable(R.drawable.bg_primary));
            v.setTextColor(attr(R.attr.cOnPrimary));
            v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        } else {
            v.setBackground(getDrawable(R.drawable.bg_chip));
            v.setTextColor(attr(R.attr.cChipText));
        }
        return v;
    }

    private void toggleGlobalFormat(String ext) {
        List<String> g = Settings.globalFormats(this);
        if (g.contains(ext)) {
            if (g.size() <= 1) {
                toast("至少留一个格式");
                return;
            }
            g.remove(ext);
        } else {
            g.add(ext);
        }
        Settings.setGlobalFormats(this, g);
        buildFormatSection();
        if (watch != null) watch.reload();
        toast((g.contains(ext) ? "已启用 " : "已取消 ") + Settings.fmtLabel(ext));
    }

    private void askAddFormat() {
        EditText et = new EditText(this);
        et.setHint("例如 iso、epub");
        et.setSingleLine(true);
        et.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        et.setPadding(dp(12), dp(10), dp(12), dp(10));
        et.setBackgroundResource(R.drawable.bg_input);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(et, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setTitle("添加追踪格式")
                .setMessage("填扩展名就行，加完会出现在这里，也会出现在每个项目的格式菜单里。")
                .setView(box)
                .setPositiveButton("添加", (d, w) -> {
                    String raw = et.getText().toString().trim();
                    String e = raw.toLowerCase(Locale.US).replaceAll("[^a-z0-9]", "");
                    if (e.isEmpty()) {
                        toast("扩展名不合法");
                        return;
                    }
                    Settings.addFormat(this, e);
                    buildFormatSection();
                    if (MainActivity.this.watch != null) MainActivity.this.watch.reload();
                    toast("已添加 " + Settings.fmtLabel(e));
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmRemoveFormat(final String ext) {
        new AlertDialog.Builder(this)
                .setTitle("删除格式 " + Settings.fmtLabel(ext) + "？")
                .setMessage("只从菜单里删掉，已经下载的文件不受影响。")
                .setPositiveButton("删除", (d, w) -> {
                    Settings.removeFormat(this, ext);
                    buildFormatSection();
                    if (watch != null) watch.reload();
                    toast("已删除 " + Settings.fmtLabel(ext));
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 设置页所有「当前值」文案集中刷新，改哪一项都只调这一个方法 */
    private void paintSettingValues() {
        int conc = Settings.concurrent(this);
        setText(R.id.concValue, conc + " 个 ›");
        setText(R.id.concSub, conc == 1
                ? "单任务独占带宽，一次只下一个文件"
                : "最多 " + conc + " 个文件同时下载，共用同一份镜像排名");

        boolean multi = Settings.multiThread(this);
        int threads = Settings.threads(this);
        setText(R.id.threadsValue, threads + " 线程 ›");
        setText(R.id.threadsSub, multi
                ? "每个文件切 " + threads + " 段并发拉取，镜像支持 Range 时生效"
                : "已关闭：每个文件只用 1 条连接");
        View rowThreads = findViewById(R.id.rowThreads);
        if (rowThreads != null) {
            rowThreads.setAlpha(multi ? 1f : 0.45f);
            rowThreads.setEnabled(multi);
        }

        boolean failover = Settings.failover(this);
        int tries = Settings.failoverMax(this);
        setText(R.id.failoverMaxValue, tries + " 个 ›");
        setText(R.id.failoverMaxSub, failover
                ? "一个失败就换下一个"
                : "已关闭自动换镜像，这项暂时不生效");
        View rowTries = findViewById(R.id.rowFailoverMax);
        if (rowTries != null) {
            rowTries.setAlpha(failover ? 1f : 0.45f);
            rowTries.setEnabled(failover);
        }

        setText(R.id.chunkValue, Settings.chunkKB(this) + " KB ›");
        setText(R.id.timeoutValue, Settings.timeoutSec(this) + " 秒 ›");

        boolean cache = Settings.cacheRank(this);
        int ttl = Settings.ttlMin(this);
        setText(R.id.ttlValue, ttl + " 分钟 ›");
        setText(R.id.cacheSub, cache
                ? ttl + " 分钟内复用同一份测速排名"
                : "已关闭：每次开始下载都重新测速");
        View rowTtl = findViewById(R.id.rowTtl);
        if (rowTtl != null) {
            rowTtl.setAlpha(cache ? 1f : 0.45f);
            rowTtl.setEnabled(cache);
        }

        int hist = Settings.historyMax(this);
        setText(R.id.historyMaxValue, hist + " 条 ›");
        setText(R.id.historyMaxSub, "列表最多显示 " + hist + " 条，调小不会删掉记录");

        paintWatchMode();
    }

    /**
     * 版本页的挑文件方式：滑块停在哪一侧，版本页就是哪种行为。
     * 这个选择只在这里改，别处不会自己动它。
     */
    private void paintWatchMode() {
        if (segWatchMode == null) return;
        boolean batch = Settings.batchSelect(this);
        int want = batch ? 1 : 0;
        if (segWatchMode.getSelected() != want) segWatchMode.setSelected(want);
        if (watchModeSub != null) {
            watchModeSub.setText(batch
                    ? "每行前面有方框，勾选几个后一次性测速、并发下载"
                    : "点一个文件就直接测速下载，适合只要一个包的情况");
        }
    }

    private void setText(int id, String s) {
        TextView v = findViewById(id);
        if (v != null) v.setText(s);
    }

    /** 把设置页的参数一次性灌进引擎，避免各处各读一遍 */
    private void applyEnginePrefs() {
        if (engine == null) return;
        engine.setConcurrent(Settings.concurrent(this));
        engine.setSegments(Settings.threads(this));
        engine.setMultiThread(Settings.multiThread(this));
        engine.setTimeoutMs(Settings.timeoutSec(this) * 1000);
        engine.setResumeEnabled(Settings.resume(this));
        engine.setMaxAttempts(Settings.failover(this) ? Settings.failoverMax(this) : 1);
    }

    /** 数字设置：可以直接输入，也给几个常用值一键填 */
    private void askNumber(String title, String hint, int cur, int min, int max,
                           String unit, int[] presets, java.util.function.IntConsumer apply) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(2), dp(20), 0);

        EditText et = new EditText(this);
        et.setId(android.R.id.input);   // 固定 id，方便自动化测试找到输入框
        et.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        et.setText(String.valueOf(cur));
        et.setSelection(et.getText().length());
        et.setSingleLine(true);
        et.setTextSize(TypedValue.COMPLEX_UNIT_PX, dimPx(R.attr.dT4));
        et.setBackgroundResource(R.drawable.bg_input);
        et.setPadding(dp(12), dp(10), dp(12), dp(10));
        box.addView(et);

        if (presets != null && presets.length > 0) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.topMargin = dp(10);
            row.setLayoutParams(rlp);
            for (int i = 0; i < presets.length; i++) {
                final int p = presets[i];
                TextView chip = new TextView(this);
                chip.setText(String.valueOf(p));
                chip.setGravity(Gravity.CENTER);
                chip.setTextSize(TypedValue.COMPLEX_UNIT_PX, dimPx(R.attr.dT2));
                chip.setTextColor(attr(R.attr.cChipText));
                chip.setBackgroundResource(R.drawable.bg_chip);
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, dp(40), 1f);
                if (i > 0) clp.setMarginStart(dp(6));
                row.addView(chip, clp);
                chip.setOnClickListener(v -> {
                    et.setText(String.valueOf(p));
                    et.setSelection(et.getText().length());
                });
            }
            box.addView(row);
        }

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(hint + "（" + min + "~" + max + " " + unit + "）")
                .setView(box)
                .setPositiveButton("确定", null)
                .setNegativeButton("取消", null)
                .create();
        dialog.show();
        // 按钮在 show() 之后才装好，这里接管点击，越界就不关闭对话框
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            int n;
            try {
                n = Integer.parseInt(et.getText().toString().trim());
            } catch (Exception e) {
                toast("请输入 " + min + "~" + max + " 的数字");
                return;
            }
            if (n < min || n > max) {
                toast("超出范围，只能填 " + min + "~" + max);
                return;
            }
            apply.accept(n);
            dialog.dismiss();
        });
    }

    // ---------------------------------------------------------------- 工具

    private List<Mirror> enabledMirrors() {
        List<Mirror> out = new ArrayList<>();
        for (Mirror m : mirrors) if (m.enabled) out.add(m);
        return out;
    }

    private void prefillFromClipboard() {
        String s = readClipboard();
        if (s == null) return;
        GithubUrlParser.Info info = GithubUrlParser.parse(s);
        if (info != null && info.downloadable) {
            urlInput.setText(s);
            urlInput.setSelection(s.length());
        }
    }

    private String readClipboard() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) return null;
            ClipData clip = cm.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return null;
            CharSequence t = clip.getItemAt(0).coerceToText(this);
            return t == null ? null : t.toString().trim();
        } catch (Exception e) {
            return null;
        }
    }

    private void styleSwitch(Switch sw) {
        int[][] states = new int[][]{{android.R.attr.state_checked}, {}};
        sw.setThumbTintList(new ColorStateList(states,
                new int[]{attr(R.attr.cThumbOn), attr(R.attr.cThumbOff)}));
        sw.setTrackTintList(new ColorStateList(states,
                new int[]{attr(R.attr.cSwitchTrackOn), attr(R.attr.cSwitchTrackOff)}));
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
