package com.lightmirror.dl;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 「版本」页：输入仓库 → 找出最新 release 里匹配格式的附件 → 已下载的跳过，
 * 有新版本的可以直接挑版本下载。
 */
public class WatchController {

    public interface Host {
        /** 交给首页去测速下载 */
        void pickUrl(String url, String repo, String tag);

        /** 批量：先把这些文件塞进队列，再统一测速、并发开跑 */
        void pickBatch(List<Pick> items);

        /** 还能再塞几个任务，0 表示队列已满 */
        int queueRoom();

        /** 这个地址是不是已经在队列里（挡重复） */
        boolean isQueued(String url);

        void toast(String s);

        boolean isDownloading();

        /** 跳到设置页：大范围的追踪格式只在那里改 */
        void openSettings();
    }

    /** 勾选中的一个文件：带着仓库和版本号，批量提交时不用再回查 */
    public static class Pick {
        public final String url, name, tag, repo;

        public Pick(String url, String name, String tag, String repo) {
            this.url = url;
            this.name = name;
            this.tag = tag;
            this.repo = repo;
        }
    }

    /** 进入版本页时的自动检查间隔：10 分钟内查过就不重复请求 */
    private static final long CHECK_INTERVAL_MS = 10 * 60 * 1000L;

    private final Activity act;
    private final Host host;

    private EditText input;
    private TextView hint, count;
    private View empty;
    private LinearLayout list;
    private TextView fmtSummary;

    private List<WatchStore.Watch> watches = new ArrayList<>();
    private final Map<String, List<ReleaseChecker.Release>> cache = new HashMap<>();
    private final Map<String, String> errors = new HashMap<>();
    private final Set<String> loading = new HashSet<>();
    private final Set<String> downloaded = new HashSet<>();
    private final Set<String> downloadedUrls = new HashSet<>();
    /** 批量模式下勾选的文件，key = url，跨对话框保留 */
    private final Map<String, Pick> selected = new java.util.LinkedHashMap<>();
    /** 每行一个刷新闭包：勾选变化时统一重画（方块、徽标、描边） */
    private final List<Runnable> painters = new ArrayList<>();
    private TextView batchBtn;
    private final ArrayDeque<String> queue = new ArrayDeque<>();
    private boolean busy;
    private AlertDialog dialog;
    private AlertDialog picker;
    private ScrollView dlgScroll;
    private int restoreScrollY = -1;
    /** 版本弹窗的容器与铺设进度：翻页回来后原地续铺，不重建、不动滚动位置 */
    private LinearLayout dlgCol;
    private int[] dlgDone;
    private TextView dlgMoreTip;
    /** 版本列表一次铺多少个：几百个版本一次全塞进 ScrollView 会卡，分批铺 */
    private static final int VERSION_CHUNK = 50;
    /** 已经翻完所有页的项目，别每次都去翻 */
    private final Set<String> fullLoaded = new HashSet<>();
    private final Set<String> loadingAll = new HashSet<>();
    /** 当前版本弹窗对应的仓库，异步结果回来时判断还要不要刷 */
    private String openRepo = "";

    public WatchController(Activity act, Host host) {
        this.act = act;
        this.host = host;
    }

    public void bind() {
        input = act.findViewById(R.id.watchInput);
        hint = act.findViewById(R.id.watchHint);
        count = act.findViewById(R.id.watchCount);
        empty = act.findViewById(R.id.watchEmpty);   // 空态是一个容器，不是 TextView
        list = act.findViewById(R.id.watchList);

        // 大范围（哪些格式要追）只在设置页改，这里只显示结果
        fmtSummary = act.findViewById(R.id.fmtSummary);
        fmtSummary.setOnClickListener(v -> host.openSettings());
        paintFmtSummary();

        act.findViewById(R.id.btnWatchAdd).setOnClickListener(v -> addFromInput());
        act.findViewById(R.id.btnWatchRefresh).setOnClickListener(v -> checkAllNow());
        input.setOnEditorActionListener((v, actionId, event) -> {
            addFromInput();
            return true;
        });

        reload();
    }

    /** 顶部那行「追踪格式：APK / ZIP / PDF / RAR ›」，点一下去设置 */
    private void paintFmtSummary() {
        if (fmtSummary == null) return;
        List<String> g = Settings.globalFormats(act);
        String text = "追踪格式 " + fmtList(g) + "  ›";
        fmtSummary.setText(text);
        fmtSummary.setContentDescription("追踪格式：" + fmtList(g) + "，点一下去设置里调整");
    }

    private String fmtList(List<String> fmts) {
        StringBuilder sb = new StringBuilder();
        for (String f : fmts) {
            if (sb.length() > 0) sb.append(" / ");
            sb.append(Settings.fmtLabel(f));
        }
        return sb.toString();
    }

    public void reload() {
        watches = WatchStore.load(act);
        downloaded.clear();
        downloadedUrls.clear();
        for (HistoryStore.Item it : HistoryStore.load(act)) {
            if ("done".equals(it.state)) {
                downloaded.add(it.file);
                if (it.url != null && !it.url.isEmpty()) downloadedUrls.add(it.url);
            }
        }
        render();
    }

    /** 每次切到这一页调用：渲染 + 自动检查超过 10 分钟没查过的项目 */
    public void onShown() {
        reload();
        long now = System.currentTimeMillis();
        for (WatchStore.Watch w : watches) {
            boolean stale = cache.get(w.repo) == null
                    || now - w.checkedAt > CHECK_INTERVAL_MS;
            if (stale) enqueue(w.repo);
        }
    }

    /** 设置页换了挑文件方式：之前勾的作废，免得到了新模式还揣着看不见的选择 */
    public void clearPicks() {
        selected.clear();
    }

    /** 手动刷新：不看缓存，所有项目重新查一遍（受 60 次/小时限流约束） */
    public void checkAllNow() {        if (watches.isEmpty()) {
            toast("还没有追踪任何项目");
            return;
        }
        int queued = 0;
        for (WatchStore.Watch w : watches) {
            cache.remove(w.repo);
            errors.remove(w.repo);
            loading.remove(w.repo);
            queue.remove(w.repo);
            if (enqueue(w.repo)) queued++;
        }
        if (queued > 0) toast("正在检查 " + queued + " 个项目的更新…");
    }

    private void addFromInput() {
        String repo = ReleaseChecker.parseRepo(input.getText().toString());
        if (repo == null) {
            toast("地址不认识，填 owner/repo 或仓库链接");
            return;
        }
        WatchStore.upsert(act, repo);
        input.setText("");
        reload();
        cache.remove(repo);
        errors.remove(repo);
        enqueue(repo);
        hint.setText("已加入追踪 · " + repo);
    }

    /** 首页粘的是仓库地址时，直接把项目丢进来检查 */
    public void fillRepo(String repo) {
        if (repo == null) return;
        input.setText(repo);
        addFromInput();
    }

    // ------------------------------------------------------------ 拉取

    private boolean enqueue(String repo) {
        if (repo == null || loading.contains(repo)) return false;
        if (!queue.contains(repo)) queue.add(repo);
        pump();
        return true;
    }

    private void pump() {
        if (busy || queue.isEmpty()) return;
        final String repo = queue.poll();
        busy = true;
        loading.add(repo);
        render();
        ReleaseChecker.fetch(repo, new ReleaseChecker.Callback() {
            @Override
            public void onOk(List<ReleaseChecker.Release> releases) {
                cache.put(repo, releases);
                errors.remove(repo);
                loading.remove(repo);
                busy = false;
                WatchStore.markChecked(act, repo);   // 节流时间戳在这里回填
                reload();
                act.getWindow().getDecorView().postDelayed(WatchController.this::pump, 200);
            }

            @Override
            public void onError(String message) {
                errors.put(repo, message);
                loading.remove(repo);
                busy = false;
                render();
                act.getWindow().getDecorView().postDelayed(WatchController.this::pump, 200);
            }
        });
    }

    // ------------------------------------------------------------ 渲染

    private void render() {
        count.setText(watches.size() + " 个");
        empty.setVisibility(watches.isEmpty() ? View.VISIBLE : View.GONE);
        list.removeAllViews();
        for (WatchStore.Watch w : watches) list.addView(buildCard(w));
    }

    private View buildCard(WatchStore.Watch w) {
        LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(cardBg());
        int p = dp(14);
        card.setPadding(p, p, p, dp(12));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.bottomMargin = dp(10);
        card.setLayoutParams(clp);

        // 第一行：仓库名 + 删除
        LinearLayout top = new LinearLayout(act);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView name = text(w.repo, R.attr.cText, R.attr.dT2, true);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        top.addView(name, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView fmt = text(fmtBadge(w), R.attr.cText3, R.attr.dT1, false);
        fmt.setSingleLine(true);
        fmt.setPadding(dp(8), dp(6), dp(8), dp(6));
        fmt.setContentDescription("这个项目追踪的格式，点一下调整");
        fmt.setOnClickListener(v -> chooseFormats(w, false));
        top.addView(fmt);

        TextView del = text("删除", R.attr.cText3, R.attr.dT1, false);
        del.setGravity(Gravity.CENTER);
        del.setPadding(dp(10), dp(6), 0, dp(6));
        del.setOnClickListener(v -> {
            WatchStore.remove(act, w.repo);
            reload();
            toast("已取消追踪 " + w.repo);
        });
        top.addView(del);
        card.addView(top);

        // 状态行
        List<ReleaseChecker.Release> rel = cache.get(w.repo);
        String err = errors.get(w.repo);
        boolean checking = loading.contains(w.repo);

        String statusText;
        int statusColor;
        if (checking) {
            statusText = "正在获取版本…";
            statusColor = color(R.attr.cText3);
        } else if (err != null) {
            statusText = err;
            statusColor = color(R.attr.cDanger);
        } else if (rel == null) {
            statusText = "还没检查过，点一下获取";
            statusColor = color(R.attr.cText3);
        } else if (rel.isEmpty()) {
            statusText = "这个仓库还没有发布 release";
            statusColor = color(R.attr.cText3);
        } else {
            ReleaseChecker.Release latest = rel.get(0);
            boolean isNew = !w.lastTag.isEmpty() && !w.lastTag.equals(latest.tag);
            if (w.lastTag.isEmpty()) {
                statusText = "发现 " + latest.tag + " · 可以下载";
                statusColor = color(R.attr.cPrimary);
            } else if (isNew) {
                statusText = "有新版本 " + w.lastTag + " → " + latest.tag;
                statusColor = color(R.attr.cWarn);
            } else {
                statusText = "已是最新 · " + latest.tag;
                statusColor = color(R.attr.cPrimary);
            }
        }
        TextView status = text(statusText, statusColor, R.attr.dT1, false);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(5);
        status.setLayoutParams(slp);
        status.setLineSpacing(dp(2), 1f);
        card.addView(status);

        // 第三行：最新版本的文件信息（说清楚推荐哪一个，别让人猜）
        final List<ReleaseChecker.Asset> picks = (rel == null || rel.isEmpty())
                ? new ArrayList<>()
                : picksFor(w, rel.get(0));
        if (rel != null && !rel.isEmpty()) {
            ReleaseChecker.Release latest = rel.get(0);
            String unit = "文件";
            String when = latest.newest > 0 ? ReleaseChecker.timeLabel(latest.newest) : latest.date;
            String meta = "最新 " + latest.tag + (when.isEmpty() ? "" : " · " + when);
            if (picks.isEmpty()) {
                meta += " · 没有 " + unit;
            } else if (picks.size() == 1) {
                String abi = ReleaseChecker.abiLabel(picks.get(0).abi);
                meta += " · " + (abi.isEmpty() ? "" : abi + " · ")
                        + ReleaseChecker.sizeText(picks.get(0).size);
            } else {
                String abi = ReleaseChecker.abiLabel(picks.get(0).abi);
                meta += " · " + picks.size() + " 个 " + unit
                        + " · 推荐 " + (abi.isEmpty() ? picks.get(0).name : abi);
            }
            TextView m = text(meta, R.attr.cText2, R.attr.dT1, false);
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            mlp.topMargin = dp(4);
            m.setLayoutParams(mlp);
            card.addView(m);
            View tags = tagBar(w, tagSource(w, rel.get(0)), true);
            if (tags != null) card.addView(tags);
        }

        // 按钮行
        LinearLayout actions = new LinearLayout(act);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = dp(11);
        actions.setLayoutParams(alp);

        TextView versions = pill("查看版本", false);
        versions.setOnClickListener(v -> openVersions(w));
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(0, px(R.attr.dBtnH), 1f);
        versions.setLayoutParams(vlp);
        actions.addView(versions);

        final ReleaseChecker.Release latest = (rel == null || rel.isEmpty()) ? null : rel.get(0);
        final ReleaseChecker.Asset first = picks.isEmpty() ? null : picks.get(0);
        boolean already = first != null && downloaded.contains(first.name)
                && latest != null && !w.lastTag.isEmpty() && w.lastTag.equals(latest.tag);

        String label;
        if (first == null) label = "没有匹配文件";
        else if (picks.size() > 1) label = "选择文件 · " + picks.size();
        else label = already ? "已下载" : "下载最新";

        boolean primaryBtn = first != null && !(picks.size() == 1 && already);
        TextView dl = pill(label, primaryBtn);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(0, px(R.attr.dBtnH), 1f);
        dlp.leftMargin = dp(8);
        dl.setLayoutParams(dlp);
        if (first == null) {
            if (!isMono()) dl.setAlpha(0.35f);
        } else if (picks.size() > 1) {
            dl.setOnClickListener(v -> {
                if (host.queueRoom() <= 0) {
                    toast("下载队列已满，先清除已完成的任务");
                    return;
                }
                openPicker(w, latest);
            });
        } else if (!already) {
            dl.setOnClickListener(v -> {
                if (host.queueRoom() <= 0) {
                    toast("下载队列已满，先清除已完成的任务");
                    return;
                }
                host.pickUrl(first.url, w.repo, latest.tag);
            });
        } else if (!isMono()) {
            dl.setAlpha(0.45f);   // 墨水屏不用灰度表达禁用
        }
        actions.addView(dl);
        card.addView(actions);

        return card;
    }

    /** 这个项目当前要看的文件：格式 → 架构 → 选中的标签（可多选） */
    private List<ReleaseChecker.Asset> picksFor(WatchStore.Watch w, ReleaseChecker.Release r) {
        List<ReleaseChecker.Asset> picks = tagSource(w, r);
        if (w.tags != null && !w.tags.isEmpty()) {
            List<ReleaseChecker.Asset> hit = new ArrayList<>();
            for (ReleaseChecker.Asset a : picks) {
                if (tagOn(w, a)) hit.add(a);
            }
            if (!hit.isEmpty()) return hit;
        }
        return picks;
    }

    /** 这个版本里「格式 + 架构」筛完、但还没按标签过滤的文件：标签条要拿它铺全部标签 */
    private List<ReleaseChecker.Asset> tagSource(WatchStore.Watch w, ReleaseChecker.Release r) {
        List<ReleaseChecker.Asset> order = ReleaseChecker.pickOrder(r.assets, storedFormats(w));
        return ReleaseChecker.filterByAbi(order, w.abiFilter);
    }

    /** 存下来的小范围（空 = 跟随设置） */
    private List<String> storedFormats(WatchStore.Watch w) {
        return Settings.projectFormats(act, w.formats);
    }

    /** 这个项目最新版真实存在的格式：只在设置的大范围里挑，按设置的顺序 */
    private List<String> availableFormats(String repo) {
        List<ReleaseChecker.Release> rel = cache.get(repo);
        List<String> out = new ArrayList<>();
        if (rel == null || rel.isEmpty()) return out;
        java.util.Set<String> have = new java.util.HashSet<>();
        for (ReleaseChecker.Asset a : rel.get(0).assets) {
            if (a.ext.isEmpty() || ReleaseChecker.noise(a.name)) continue;
            if (!ReleaseChecker.installableExt(a.ext)) continue;
            have.add(a.ext);
        }
        for (String f : Settings.globalFormats(act)) if (have.contains(f)) out.add(f);
        return out;
    }

    /** 卡片上真正生效的格式 = 小范围 ∩ 这个项目真实存在的格式 */
    private List<String> effectiveFormats(WatchStore.Watch w) {
        List<String> avail = availableFormats(w.repo);
        List<String> cur = storedFormats(w);
        if (avail.isEmpty()) return cur;
        List<String> keep = new ArrayList<>();
        for (String f : cur) if (avail.contains(f)) keep.add(f);
        return keep;
    }

    private String fmtBadge(WatchStore.Watch w) {
        List<String> eff = effectiveFormats(w);
        if (eff.isEmpty()) return cache.get(w.repo) == null ? "格式…" : "无匹配";
        if (eff.size() >= 4) return eff.size() + " 种格式";
        return fmtList(eff).replace(" / ", "·");
    }

    /**
     * 这个项目追踪哪些格式：多选，而且只列这个项目真实存在的格式。
     * 大范围在设置页；这里只能在大范围里收窄。
     */
    private void chooseFormats(final WatchStore.Watch w, final boolean reopen) {
        final List<String> avail = availableFormats(w.repo);
        if (avail.isEmpty()) {
            if (loading.contains(w.repo)) {
                toast("正在获取版本，稍后再试");
            } else {
                toast("先检查一次更新，才知道这个项目有哪些格式");
                enqueue(w.repo);
            }
            return;
        }
        final List<String> cur = storedFormats(w);
        String[] labels = new String[avail.size()];
        final boolean[] checked = new boolean[avail.size()];
        for (int i = 0; i < avail.size(); i++) {
            labels[i] = Settings.fmtLabel(avail.get(i));
            checked[i] = cur.contains(avail.get(i));
        }
        new AlertDialog.Builder(act)
                .setTitle("这个项目追踪哪些格式")
                .setMultiChoiceItems(labels, checked, (d, which, on) -> checked[which] = on)
                .setPositiveButton("保存", (d, which) -> {
                    List<String> sel = new ArrayList<>();
                    for (int i = 0; i < avail.size(); i++) if (checked[i]) sel.add(avail.get(i));
                    if (sel.isEmpty()) {
                        toast("至少留一个格式");
                        return;
                    }
                    boolean follow = sel.size() == avail.size()
                            && avail.size() == Settings.globalFormats(act).size();
                    w.formats.clear();
                    if (!follow) w.formats.addAll(sel);
                    WatchStore.setFormats(act, w.repo, w.formats);
                    render();
                    toast(follow ? "已跟随设置里的全部格式" : "这个项目只看 " + fmtList(sel));
                    if (reopen) openVersions(w);
                })
                .setNeutralButton("跟随设置", (d, which) -> {
                    w.formats.clear();
                    WatchStore.setFormats(act, w.repo, w.formats);
                    render();
                    toast("已跟随设置里的格式");
                    if (reopen) openVersions(w);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 标签行。标签 = 同一格式里去掉产品名和版本号后真正不同的部分，例如「便携版 · ZIP」。
     * 支持多选：点一下选中，再点一下取消；选中几个就只看这几个。
     * 标签名永远单行，太长就缩字号，不折行——折行看着太碎。
     */
    private View tagBar(final WatchStore.Watch w, List<ReleaseChecker.Asset> picks, boolean withToggle) {
        final LinkedHashMap<String, ReleaseChecker.Asset> uniq = new LinkedHashMap<>();
        for (ReleaseChecker.Asset a : picks) {
            if (a.variant == null || a.variant.isEmpty()) continue;
            String k = ReleaseChecker.labelKey(a);
            if (!uniq.containsKey(k)) uniq.put(k, a);
        }
        if (uniq.isEmpty()) return null;

        int onCount = 0;
        for (ReleaseChecker.Asset a : uniq.values()) if (tagOn(w, a)) onCount++;
        final int onTotal = onCount;
        final boolean collapsed = !w.tagsOpen && onCount > 0;

        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(7);
        row.setLayoutParams(rlp);

        TextView head = text("标签", R.attr.cText3, R.attr.dT1, false);
        head.setPadding(0, 0, dp(8), 0);
        head.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(head);

        LinearLayout chips = new LinearLayout(act);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setGravity(Gravity.CENTER_VERTICAL);
        int shown = 0;
        for (Map.Entry<String, ReleaseChecker.Asset> e : uniq.entrySet()) {
            boolean on = tagOn(w, e.getValue());
            if (collapsed && !on) continue;
            chips.addView(tagChip(w, e.getValue(), on));
            shown++;
        }
        // 选中的标签在这个版本里一枚都没有（老版本少文件）：整行不占位，别留一条空白
        if (shown == 0) return null;

        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(act);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(chips);
        row.addView(hs, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // 有选中才给「展开 / 收起」，避免一个点了没反应的按钮
        if (withToggle && onTotal > 0) {
            TextView toggle = text(collapsed ? "全部标签 " + uniq.size() : "收起",
                    R.attr.cPrimary, R.attr.dT1, false);
            toggle.setPadding(dp(10), dp(6), 0, dp(6));
            toggle.setGravity(Gravity.CENTER_VERTICAL);
            toggle.setOnClickListener(v -> {
                w.tagsOpen = collapsed;
                WatchStore.setTagsOpen(act, w.repo, w.tagsOpen);
                render();
            });
            row.addView(toggle);
        }
        return row;
    }

    private TextView tagChip(final WatchStore.Watch w, final ReleaseChecker.Asset a,
                             final boolean on) {
        TextView c = new TextView(act);
        c.setGravity(Gravity.CENTER);
        c.setPadding(dp(11), dp(7), dp(11), dp(7));
        if (on) {
            c.setBackground(bg(color(R.attr.cPrimary), 0, 9));
            c.setTextColor(color(R.attr.cOnPrimary));
            c.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        } else {
            c.setBackground(act.getDrawable(R.drawable.bg_chip));
            c.setTextColor(color(R.attr.cChipText));
        }
        // 标签名尽量放一行：先缩字号，实在放不下就靠横向滚动看全
        String label = ReleaseChecker.variantText(a);
        float base = px(R.attr.dT1);
        int cap = Math.max(dp(110), act.getResources().getDisplayMetrics().widthPixels - dp(150));
        float size = base;
        TextPaint tp = new TextPaint(c.getPaint());
        tp.setTextSize(base);
        while (size > base * 0.68f
                && tp.measureText(label) > cap - c.getPaddingLeft() - c.getPaddingRight()) {
            size -= 0.5f;
            tp.setTextSize(size);
        }
        c.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
        c.setText(label);
        c.setSingleLine(true);
        c.setEllipsize(null);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(7);
        c.setLayoutParams(lp);
        c.setOnClickListener(v -> toggleTag(w, a));
        return c;
    }

    /** 这枚标签是不是选中了：新数据存语义键，老数据是文件名指纹，两种都认 */
    private boolean tagOn(WatchStore.Watch w, ReleaseChecker.Asset a) {
        if (w == null || w.tags == null || w.tags.isEmpty() || a == null) return false;
        if (w.tags.contains(ReleaseChecker.labelKey(a))) return true;
        return a.vkey != null && !a.vkey.isEmpty() && w.tags.contains(a.vkey);
    }

    /** 点标签：选中 / 取消；全取消就回到「这个版本的所有文件」 */
    private void toggleTag(WatchStore.Watch w, ReleaseChecker.Asset a) {
        List<String> next = new ArrayList<>(w.tags);
        boolean removing = tagOn(w, a);
        if (removing) {
            // 同一语义的旧指纹可能散落在多个版本里，一起清掉，否则点了取消还是亮着
            String lk = ReleaseChecker.labelKey(a);
            next.remove(lk);
            if (a.vkey != null && !a.vkey.isEmpty()) next.remove(a.vkey);
            List<ReleaseChecker.Release> rel = cache.get(w.repo);
            if (rel != null) {
                for (ReleaseChecker.Release r : rel) {
                    for (ReleaseChecker.Asset x : r.assets) {
                        if (lk.equals(ReleaseChecker.labelKey(x))
                                && x.vkey != null && !x.vkey.isEmpty()) {
                            next.remove(x.vkey);
                        }
                    }
                }
            }
        } else {
            String lk = ReleaseChecker.labelKey(a);
            if (!next.contains(lk)) next.add(lk);
        }
        w.tags.clear();
        w.tags.addAll(next);
        WatchStore.setTags(act, w.repo, w.tags);
        render();
        if (next.isEmpty()) {
            toast("已取消标签筛选，显示该版本的全部文件");
        } else if (removing) {
            toast("已取消「" + ReleaseChecker.variantText(a) + "」");
        } else {
            toast("已选中「" + ReleaseChecker.variantText(a) + "」，可继续多选");
        }
        // 版本弹窗还开着：就地重建，滚动位置尽量保持
        if (dialog != null && dialog.isShowing() && w.repo.equals(openRepo)) {
            restoreScrollY = dlgScroll == null ? 0 : dlgScroll.getScrollY();
            openVersions(w);
        }
    }

    // ------------------------------------------------------------ 版本选择

    private void openVersions(final WatchStore.Watch w) {
        List<ReleaseChecker.Release> rel = cache.get(w.repo);
        if (rel == null) {
            toast(loading.contains(w.repo) ? "正在获取版本列表…" : "先点一下检查更新");
            enqueue(w.repo);
            return;
        }
        if (rel.isEmpty()) {
            toast("这个仓库还没有 release");
            return;
        }

        migrateTags(w);

        if (dialog != null && dialog.isShowing()) dialog.dismiss();
        dialog = null;            // 旧弹窗的 dismiss 回调别把新容器清掉
        dlgCol = null;
        dlgDone = null;
        dlgMoreTip = null;
        openRepo = w.repo;
        painters.clear();          // 旧窗口的刷新闭包作废，重建时再注册

        final List<ReleaseChecker.Release> list = rel;
        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(6), dp(16), dp(6));

        // 顶部：本项目格式（只列这个项目真实存在的格式，只能在大范围里收窄）
        LinearLayout fmtRow = new LinearLayout(act);
        fmtRow.setOrientation(LinearLayout.HORIZONTAL);
        fmtRow.setGravity(Gravity.CENTER_VERTICAL);
        fmtRow.setPadding(0, dp(2), 0, dp(6));
        TextView fmtHead = text("本项目格式", R.attr.cText3, R.attr.dT1, false);
        fmtHead.setPadding(0, 0, dp(8), 0);
        fmtRow.addView(fmtHead);
        TextView fmtVal = text(fmtList(effectiveFormats(w)) + "　›", R.attr.cPrimary, R.attr.dT1, false);
        fmtVal.setSingleLine(true);
        fmtVal.setOnClickListener(v -> chooseFormats(w, true));
        fmtRow.addView(fmtVal, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView fmtHint = text("可多选", R.attr.cText3, R.attr.dT0, false);
        fmtRow.addView(fmtHint);
        col.addView(fmtRow);

        // 顶部：架构筛选。安卓端默认只看 arm64-v8a，手动换过就一直沿用
        View bar = abiBar(w, ReleaseChecker.pickOrder(list.get(0).assets, storedFormats(w)), () -> {
            dismissAll();
            openVersions(w);
        });
        if (bar != null) col.addView(bar);

        // 先铺一批，剩下的等这一帧画完再接着铺，避免几百个版本一次布局卡住
        final int[] done = {0};
        dlgCol = col;
        dlgDone = done;
        appendRest(col, w, list, done);

        // 第一页就满了 100 个，说明后面还有更早的版本，接着把剩下的页翻回来
        final boolean maybeMore = list.size() >= ReleaseChecker.PER_PAGE
                && !fullLoaded.contains(w.repo);
        if (maybeMore) {
            final TextView tip = text("正在加载更早的版本…", R.attr.cText3, R.attr.dT1, false);
            tip.setGravity(Gravity.CENTER);
            tip.setPadding(0, dp(14), 0, dp(10));
            tip.setOnClickListener(v -> loadAllVersions(w));
            col.addView(tip);
            dlgMoreTip = tip;
        }

        ScrollView sv = new ScrollView(act);
        sv.addView(col);
        sv.setPadding(0, 0, 0, dp(4));
        dlgScroll = sv;

        // 标题用自绘的：仓库名一行放不下就缩字号，不折成两三行
        LinearLayout titleBox = new LinearLayout(act);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        titleBox.setPadding(dp(24), dp(18), dp(24), dp(4));
        FitLabel title = new FitLabel(act);
        title.setText(w.repo);
        title.setBaseSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 20,
                act.getResources().getDisplayMetrics()));
        title.setLabelColor(color(R.attr.cText));
        title.setBold(true);
        titleBox.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        startDialog(new AlertDialog.Builder(act).setCustomTitle(titleBox).setView(sv), false);
        // 等窗口真的出来了再翻页：以前在 show 之前就发请求，回调早到会让 tip 永久卡住
        if (maybeMore) sv.post(() -> loadAllVersions(w));
        if (restoreScrollY > 0) {
            final int y = restoreScrollY;
            restoreScrollY = -1;
            sv.post(() -> sv.scrollTo(0, y));
        }
    }

    /**
     * 老版本把「文件名指纹」存进了 tags，换一个版本文件名结构一变，同一枚标签
     * 就对不上（表现为滑动时有的版本标签亮、有的不亮）。这里一次性换成语义键。
     */
    private void migrateTags(WatchStore.Watch w) {
        if (w == null || w.tags == null || w.tags.isEmpty()) return;
        List<ReleaseChecker.Release> rel = cache.get(w.repo);
        if (rel == null || rel.isEmpty()) return;
        // 同一个指纹在各版本里理论上对应同一枚标签；真出现多种映射就不动它
        java.util.Map<String, java.util.Set<String>> map = new java.util.HashMap<>();
        for (ReleaseChecker.Release r : rel) {
            for (ReleaseChecker.Asset a : r.assets) {
                if (a.vkey == null || a.vkey.isEmpty()) continue;
                java.util.Set<String> set = map.get(a.vkey);
                if (set == null) {
                    set = new java.util.LinkedHashSet<>();
                    map.put(a.vkey, set);
                }
                set.add(ReleaseChecker.labelKey(a));
            }
        }
        List<String> next = new ArrayList<>();
        boolean changed = false;
        for (String k : w.tags) {
            java.util.Set<String> to = map.get(k);
            if (to != null && to.size() == 1) {
                changed = true;
                String v = to.iterator().next();
                if (!next.contains(v)) next.add(v);
            } else if (!next.contains(k)) {
                next.add(k);
            }
        }
        if (!changed) return;
        w.tags.clear();
        w.tags.addAll(next);
        WatchStore.setTags(act, w.repo, w.tags);
    }

    /**
     * 分块续铺：每帧只加 VERSION_CHUNK 条，几百个版本也不会卡住主线程。
     * 只往容器末尾加，不动 ScrollView 的 scrollY，所以往下滑的时候不会跳回顶部。
     */
    private void appendRest(final LinearLayout col, final WatchStore.Watch w,
                            final List<ReleaseChecker.Release> list, final int[] done) {
        int end = Math.min(list.size(), done[0] + VERSION_CHUNK);
        appendVersions(col, w, list, done, end);
        if (done[0] < list.size()) {
            col.post(() -> {
                if (dialog == null || !dialog.isShowing() || !w.repo.equals(openRepo)) return;
                if (dlgCol != col) return;
                appendRest(col, w, list, done);
            });
        }
    }

    /** 老列表是不是新列表的前缀：是的话原地续铺，不必重建整个弹窗 */
    private boolean isPrefix(List<ReleaseChecker.Release> old,
                             List<ReleaseChecker.Release> all) {
        if (old == null || old.isEmpty() || all == null) return false;
        if (all.size() < old.size()) return false;
        for (int i = 0; i < old.size(); i++) {
            String a = old.get(i).tag;
            String b = all.get(i).tag;
            if (a == null ? b != null : !a.equals(b)) return false;
        }
        return true;
    }

    /** 铺 [done, end) 这些版本；done 会被推到 end */
    private void appendVersions(LinearLayout col, final WatchStore.Watch w,
                                List<ReleaseChecker.Release> list, int[] done, int end) {
        for (int i = done[0]; i < end; i++) {
            final ReleaseChecker.Release r = list.get(i);
            final List<ReleaseChecker.Asset> raw = ReleaseChecker.pickOrder(r.assets, storedFormats(w));
            final List<ReleaseChecker.Asset> picks = picksFor(w, r);

            LinearLayout head = new LinearLayout(act);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            hlp.topMargin = i == 0 ? dp(4) : dp(16);
            head.setLayoutParams(hlp);

            // 版本标签（tag）死守一行：地方不够就一路缩字号，不折行也不省略
            FitLabel tag = new FitLabel(act);
            tag.setText(r.tag);
            tag.setBaseSize(px(R.attr.dT2));
            tag.setLabelColor(color(R.attr.cText));
            tag.setBold(true);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            tlp.setMarginEnd(dp(6));
            tag.setLayoutParams(tlp);
            head.addView(tag);

            // 右侧：最新 / 预发布 + 北京时间（精确到分钟）
            String when = r.newest > 0 ? ReleaseChecker.timeLabel(r.newest) : r.date;
            String tail = (i == 0 ? "最新 · " : "") + (r.prerelease ? "预发布 · " : "") + when;
            TextView time = text(tail, R.attr.cText3, R.attr.dT1, false);
            time.setSingleLine(true);
            time.setGravity(Gravity.END);
            head.addView(time);
            col.addView(head);

            View tags = tagBar(w, tagSource(w, r), false);
            if (tags != null) col.addView(tags);

            if (picks.isEmpty()) {
                TextView none = text("这个版本没有 " + fmtList(storedFormats(w)) + " 文件",
                        R.attr.cText3, R.attr.dT1, false);
                LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                nlp.topMargin = dp(6);
                none.setLayoutParams(nlp);
                col.addView(none);
            } else {
                // 只铺一行「推荐文件」，其余收进选择框，列表才不会长到没法用
                col.addView(assetChoiceRow(w, r, picks.get(0), true));
                // 还有别的安装包可换时，才给「换架构」入口；纯文本/校验文件不算
                int altApk = 0;
                for (ReleaseChecker.Asset a : raw) if (ReleaseChecker.isApk(a)) altApk++;
                if (altApk > 1) {
                    col.addView(moreRow(w, r, altApk));
                }
            }
        }
        done[0] = end;
    }

    /** 把后面几页也翻回来；只在用户真的打开版本列表时才做，省接口额度 */
    private void loadAllVersions(final WatchStore.Watch w) {
        if (loadingAll.contains(w.repo) || fullLoaded.contains(w.repo)) return;
        loadingAll.add(w.repo);
        ReleaseChecker.fetchAll(w.repo, new ReleaseChecker.Callback() {
            @Override
            public void onOk(List<ReleaseChecker.Release> all) {
                loadingAll.remove(w.repo);
                if (all == null || all.isEmpty()) return;
                List<ReleaseChecker.Release> old = cache.get(w.repo);
                boolean grew = old == null || all.size() >= old.size();
                if (grew) cache.put(w.repo, all);
                fullLoaded.add(w.repo);
                WatchStore.markChecked(act, w.repo);
                if (!w.repo.equals(openRepo) || dialog == null || !dialog.isShowing()) return;
                if (!grew) return;

                if (isPrefix(old, all) && dlgCol != null && dlgDone != null) {
                    // 翻回来的就是后面的几页：原地续铺，滚动位置一点都不动
                    if (dlgMoreTip != null) {
                        dlgCol.removeView(dlgMoreTip);
                        dlgMoreTip = null;
                    }
                    appendRest(dlgCol, w, all, dlgDone);
                } else {
                    // 列表结构变了（中途发了新版、排序变了）：只能重建，但把位置带过去
                    restoreScrollY = dlgScroll == null ? 0 : dlgScroll.getScrollY();
                    openVersions(w);
                }
            }

            @Override
            public void onError(String message) {
                loadingAll.remove(w.repo);
                if (dlgMoreTip != null) {
                    dlgMoreTip.setText("加载更早的版本失败，点这里重试");
                    dlgMoreTip.setOnClickListener(v -> loadAllVersions(w));
                }
                if (w.repo.equals(openRepo)) toast("加载更早的版本失败：" + message);
            }
        });
    }

    /** 「共 N 个安装包」入口：打开带完整列表和筛选的选择框 */
    private View moreRow(final WatchStore.Watch w, final ReleaseChecker.Release r, int total) {
        TextView more = text("共 " + total + " 个安装包 · 换架构  ›",
                R.attr.cText2, R.attr.dT1, false);
        more.setGravity(Gravity.CENTER);
        more.setPadding(dp(10), dp(9), dp(10), dp(9));
        more.setBackground(act.getDrawable(R.drawable.bg_ghost));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        more.setLayoutParams(lp);
        more.setOnClickListener(v -> openPicker(w, r));
        return more;
    }

    /**
     * 选择具体文件。同一版本里有多个同类文件（arm64-v8a / armeabi-v7a / 通用包…）时，
     * 不替用户拍板，列出来让他选；第一个带「推荐」，是本机最合适的那一个。
     * 在这里手动挑了某个架构，以后这个项目就默认只看它。
     */
    private void openPicker(final WatchStore.Watch w, final ReleaseChecker.Release r) {
        final List<ReleaseChecker.Asset> raw = ReleaseChecker.pickOrder(r.assets, storedFormats(w));
        final List<ReleaseChecker.Asset> picks = picksFor(w, r);

        painters.clear();
        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(2), dp(16), dp(4));

        View bar = abiBar(w, raw, () -> {
            dismissAll();
            openPicker(w, r);
        });
        if (bar != null) col.addView(bar);

        String abi = picks.isEmpty() ? "" : ReleaseChecker.abiLabel(picks.get(0).abi);
        String head = r.tag + " · " + raw.size() + " 个文件";
        if (!abi.isEmpty()) head += " · 当前 " + abi;
        FitLabel sub = new FitLabel(act);
        sub.setText(head);
        sub.setBaseSize(px(R.attr.dT1));
        sub.setLabelColor(color(R.attr.cText3));
        sub.setPadding(0, 0, 0, dp(2));
        col.addView(sub, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        for (int i = 0; i < picks.size(); i++) {
            col.addView(assetChoiceRow(w, r, picks.get(i), i == 0));
        }

        ScrollView sv = new ScrollView(act);
        sv.addView(col);

        startDialog(new AlertDialog.Builder(act).setTitle("选择要下载的文件").setView(sv), true);
    }

    /**
     * 统一收口两个弹窗：批量模式下底部多一个「测速并下载 N 个」，
     * 选中的数量实时刷新，0 个时按钮不可点。
     */
    private void startDialog(AlertDialog.Builder b, boolean asPicker) {
        batchBtn = null;
        final boolean batch = batchOn();
        b.setNegativeButton(batch ? "关闭" : (asPicker ? "取消" : "关闭"), null);
        if (batch) b.setPositiveButton("测速并下载", null);

        final AlertDialog d = b.create();
        if (asPicker) {
            picker = d;
        } else {
            dialog = d;
            d.setOnDismissListener(x -> {
                if (dialog != d) return;      // 已经被新弹窗顶掉了
                dlgCol = null;
                dlgDone = null;
                dlgMoreTip = null;
                dlgScroll = null;
            });
        }
        if (batch) {
            d.setOnShowListener(x -> {
                batchBtn = d.getButton(AlertDialog.BUTTON_POSITIVE);
                if (batchBtn != null) batchBtn.setOnClickListener(v -> submitBatch());
                refreshBatchBtn();
            });
        }
        d.show();
    }

    private void refreshBatchBtn() {
        if (batchBtn == null) return;
        int n = selected.size();
        batchBtn.setText(n == 0 ? "测速并下载（先勾选）" : "测速并下载 " + n + " 个");
        batchBtn.setEnabled(n > 0);
        batchBtn.setAlpha(n > 0 ? 1f : 0.45f);
    }

    private void submitBatch() {
        if (selected.isEmpty()) {
            toast("先在文件前面勾上要下的那些");
            return;
        }
        if (host.queueRoom() <= 0) {
            toast("下载队列已满，先清除已完成的任务");
            return;
        }
        List<Pick> items = new ArrayList<>(selected.values());
        selected.clear();
        dismissAll();
        host.pickBatch(items);
    }

    private void dismissAll() {
        painters.clear();
        batchBtn = null;
        dlgCol = null;
        dlgDone = null;
        dlgMoreTip = null;
        if (picker != null && picker.isShowing()) picker.dismiss();
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
    }

    private boolean isDone(ReleaseChecker.Asset a) {
        return downloadedUrls.contains(a.url) || downloaded.contains(a.name);
    }

    /** 当前是不是批量勾选模式（记在本机，下次打开沿用） */
    private boolean batchOn() {
        return Settings.batchSelect(act);
    }

    /**
     * 架构筛选条。所有安装包都没标架构时返回 null（没什么可选的）。
     * value 为空串表示「全部显示」。
     */
    private View abiBar(final WatchStore.Watch w, List<ReleaseChecker.Asset> rawPicks,
                        final Runnable onChanged) {
        java.util.Set<String> abis = new java.util.LinkedHashSet<>();
        for (ReleaseChecker.Asset a : rawPicks) {
            if (!a.abi.isEmpty()) abis.add(a.abi);
        }
        if (abis.isEmpty()) return null;

        LinearLayout bar = new LinearLayout(act);
        bar.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(4);
        blp.bottomMargin = dp(4);
        bar.setLayoutParams(blp);

        TextView label = text("只看哪种安装包（记住这个选择）", R.attr.cText3, R.attr.dT1, false);
        bar.addView(label);

        LinearLayout chips = new LinearLayout(act);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(7);
        chips.setLayoutParams(clp);

        for (final String abi : abis) {
            chips.addView(abiChip(w, ReleaseChecker.abiLabel(abi), abi, onChanged));
        }
        chips.addView(abiChip(w, "全部", "", onChanged));

        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(act);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(chips);
        bar.addView(hs);
        return bar;
    }

    private TextView abiChip(final WatchStore.Watch w, String label, final String value,
                             final Runnable onChanged) {
        final String cur = w.abiFilter == null ? "" : w.abiFilter;
        final boolean on = value.equals(cur);

        TextView c = new TextView(act);
        c.setText(label);
        c.setGravity(Gravity.CENTER);
        c.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(R.attr.dT1));
        c.setMinWidth(dp(56));
        int h = dp(34);
        c.setPadding(dp(12), 0, dp(12), 0);
        c.setHeight(h);
        if (on) {
            c.setBackground(bg(color(R.attr.cPrimary), 0, 17));
            c.setTextColor(color(R.attr.cOnPrimary));
            c.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        } else {
            c.setBackground(act.getDrawable(R.drawable.bg_chip));
            c.setTextColor(color(R.attr.cChipText));
            c.setTypeface(Typeface.DEFAULT);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, h);
        lp.rightMargin = dp(8);
        c.setLayoutParams(lp);

        c.setOnClickListener(v -> {
            if (on) return;
            w.abiFilter = value;
            WatchStore.setAbiFilter(act, w.repo, value);
            render();      // 卡片同步换推荐
            onChanged.run();
        });
        return c;
    }

    /**
     * 一行文件。单个模式下点一下就走；批量模式下左边多一个方框，
     * 勾上只是进「待下载清单」，等底部按钮统一开跑。
     * 行尾会标明「已下载 / 队列中 / 已勾选」，不用再靠记忆判断。
     */
    private View assetChoiceRow(final WatchStore.Watch w, final ReleaseChecker.Release r,
                                final ReleaseChecker.Asset a, final boolean recommended) {
        final boolean batch = batchOn();
        final boolean done = isDone(a);

        final LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.setTag("asset:" + a.url);

        LinearLayout top = new LinearLayout(act);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        final TextView box = batch ? checkBox(false) : null;
        if (box != null) {
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(24), dp(24));
            clp.rightMargin = dp(9);
            top.addView(box, clp);
        }

        if (recommended) {
            TextView badge = text("推荐", R.attr.cOnPrimary, R.attr.dT0, true);
            badge.setGravity(Gravity.CENTER);
            badge.setPadding(dp(6), dp(1), dp(6), dp(1));
            badge.setBackground(bg(color(R.attr.cPrimary), 0, 5));
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            blp.rightMargin = dp(7);
            top.addView(badge, blp);
        }

        String abi = ReleaseChecker.abiLabel(a.abi);
        String title = (a.variant == null || a.variant.isEmpty())
                ? (abi.isEmpty() ? a.ext.toUpperCase(Locale.US) : abi) : a.variant;
        top.addView(text(title, R.attr.cText, R.attr.dT2, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        final TextView mark = text("", R.attr.cText3, R.attr.dT0, false);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.rightMargin = dp(8);
        top.addView(mark, mlp);
        top.addView(text(ReleaseChecker.sizeText(a.size), R.attr.cText2, R.attr.dT1, false));
        row.addView(top);

        // 文件名普遍很长：允许折两行，还是放不下就往下缩字号，尽量别截断
        TextView fn = text(a.name, R.attr.cText3, R.attr.dT1, false);
        fitWrapped(fn, TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.topMargin = dp(3);
        fn.setLayoutParams(flp);
        row.addView(fn);

        final Runnable paint = () -> {
            boolean queued = host.isQueued(a.url);
            boolean picked = selected.containsKey(a.url) && !queued;

            if (box != null) {
                box.setText(picked ? "✓" : "");
                box.setBackground(picked
                        ? bg(color(R.attr.cPrimary), 0, 6)
                        : bg(0, color(R.attr.cLine), 6));
            }
            if (queued) {
                mark.setText("队列中");
                mark.setTextColor(color(R.attr.cPrimary));
            } else if (done) {
                mark.setText("已下载");
                mark.setTextColor(color(R.attr.cText3));
            } else if (picked) {
                mark.setText("已勾选");
                mark.setTextColor(color(R.attr.cPrimary));
            } else {
                mark.setText("");
            }
            row.setBackground(bg(color(R.attr.cSurface2),
                    picked || recommended ? color(R.attr.cPrimary) : 0, 10));
            row.setAlpha(queued ? 0.55f : 1f);
        };
        painters.add(paint);
        paint.run();

        row.setOnClickListener(v -> {
            if (host.isQueued(a.url)) {
                toast("这个文件已经在下载队列里了");
                return;
            }
            if (batch) {
                if (selected.containsKey(a.url)) {
                    selected.remove(a.url);
                    paintAll();
                    return;
                }
                if (done) {
                    confirmRedownload(a, () -> {
                        selected.put(a.url, new Pick(a.url, a.name, r.tag, w.repo));
                        paintAll();
                    });
                    return;
                }
                selected.put(a.url, new Pick(a.url, a.name, r.tag, w.repo));
                paintAll();
                return;
            }
            if (host.queueRoom() <= 0) {
                toast("下载队列已满，先清除已完成的任务");
                return;
            }
            if (done) {
                confirmRedownload(a, () -> startOne(w, r, a));
                return;
            }
            startOne(w, r, a);
        });
        return row;
    }

    /** 单个模式：记住这次挑的架构，关掉弹窗，回首页测速开跑 */
    private void startOne(WatchStore.Watch w, ReleaseChecker.Release r, ReleaseChecker.Asset a) {
        String cur = w.abiFilter == null ? "" : w.abiFilter;
        if (!a.abi.isEmpty() && !a.abi.equals(cur)) {
            w.abiFilter = a.abi;
            WatchStore.setAbiFilter(act, w.repo, a.abi);
            render();
            toast("以后默认只看 " + ReleaseChecker.abiLabel(a.abi));
        }
        dismissAll();
        host.pickUrl(a.url, w.repo, r.tag);
    }

    /** 已经下过的再点一次：说清楚会发生什么，别默默多下一份 */
    private void confirmRedownload(ReleaseChecker.Asset a, final Runnable yes) {
        new AlertDialog.Builder(act)
                .setTitle("这个文件已经下过了")
                .setMessage(a.name + " 之前已经下载完成。再下一份会另存为「" + a.name + " (2)」，不会覆盖原文件。")
                .setPositiveButton("仍要再下", (d, x) -> yes.run())
                .setNegativeButton("不用了", null)
                .show();
    }

    private void paintAll() {
        for (Runnable p : new ArrayList<>(painters)) p.run();
        refreshBatchBtn();
    }

    private TextView checkBox(boolean on) {
        TextView t = new TextView(act);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(R.attr.dT1));
        t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setText(on ? "✓" : "");
        t.setTextColor(color(R.attr.cOnPrimary));
        t.setBackground(on
                ? bg(color(R.attr.cPrimary), 0, 6)
                : bg(0, color(R.attr.cLine), 6));
        return t;
    }

    // ------------------------------------------------------------ 小工具

    /**
     * 长文本：最多两行，仍放不下就按当前字号往下缩（最多缩到 78%）。
     * 用运行时字号算，深浅主题、黑白主题都合适，不会被写死的数值压小。
     */
    private void fitWrapped(TextView v, TextUtils.TruncateAt where) {
        v.setSingleLine(false);
        v.setMaxLines(2);
        v.setEllipsize(where);
        v.setLineSpacing(0, 1.08f);
        float sp = v.getTextSize() / act.getResources().getDisplayMetrics().scaledDensity;
        int max = Math.max(9, Math.round(sp));
        int min = Math.max(8, Math.round(sp * 0.78f));
        if (min < max) {
            v.setAutoSizeTextTypeUniformWithConfiguration(min, max, 1,
                    TypedValue.COMPLEX_UNIT_SP);
        }
    }

    private TextView text(String s, int colorAttr, int sizeAttr, boolean bold) {
        TextView t = new TextView(act);
        t.setText(s);
        t.setTextColor(color(colorAttr));
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(sizeAttr));
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private TextView pill(String label, boolean primary) {
        TextView t = new TextView(act);
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(R.attr.dT2));
        t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setTextColor(color(primary ? R.attr.cOnPrimary : R.attr.cText2));
        t.setBackground(act.getDrawable(primary ? R.drawable.bg_primary : R.drawable.bg_ghost));
        t.setClickable(true);
        t.setFocusable(true);
        return t;
    }

    private Drawable cardBg() {
        if (isMono()) {
            GradientDrawable g = new GradientDrawable();
            g.setColor(0xFFFFFFFF);
            g.setCornerRadius(dp(12));
            g.setStroke(dp(2), 0xFF000000);
            return g;
        }
        return act.getDrawable(R.drawable.bg_card);
    }

    private Drawable bg(int fill, int stroke, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        if (stroke != 0) g.setStroke(dp(isMono() ? 2 : 1), stroke);
        return g;
    }

    private boolean isMono() {
        TypedValue tv = new TypedValue();
        act.getTheme().resolveAttribute(R.attr.monoMode, tv, true);
        return tv.data != 0;
    }

    private int color(int attrId) {
        TypedValue tv = new TypedValue();
        act.getTheme().resolveAttribute(attrId, tv, true);
        return tv.data;
    }

    private int px(int attrId) {
        TypedValue tv = new TypedValue();
        act.getTheme().resolveAttribute(attrId, tv, true);
        return (int) tv.getDimension(act.getResources().getDisplayMetrics());
    }

    private int dp(float v) {
        return (int) (v * act.getResources().getDisplayMetrics().density + 0.5f);
    }

    private void toast(String s) {
        host.toast(s);
    }

    private String dayOf(long time) {
        return new SimpleDateFormat("MM-dd HH:mm", Locale.US).format(new Date(time));
    }
}
