package com.wxalbum.nativeapp;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** 任务引擎：解析 → 抓取 → 清洗 → 打包 → 登记书架/历史。 */
public final class Engine {

    public static final Engine I = new Engine();

    public volatile String phase = "idle";
    public volatile String message = "准备就绪";
    public volatile Model.Album album;
    public volatile List<Model.Article> articles = new ArrayList<>();
    public volatile int total;
    public volatile int done;
    public volatile int ok;
    public volatile int fail;
    public volatile String current = "";
    public volatile int rev;
    public volatile String resultPath = "";
    public volatile String resultName = "";
    public volatile long resultSize;
    public volatile int resultChapters;
    public volatile int resultChars;
    public volatile int resultImages;

    public final List<String> logs = Collections.synchronizedList(new ArrayList<>());
    public final List<Stores.WatchItem> watch = Collections.synchronizedList(new ArrayList<>());
    public final List<Stores.HistItem> history = Collections.synchronizedList(new ArrayList<>());
    public volatile boolean checking;

    private volatile boolean cancelFlag;
    private ExecutorService worker;
    private final Handler main = new Handler(Looper.getMainLooper());

    public interface Done {
        void run(boolean success, String msg);
    }

    public static class Opts {
        public boolean images = false;
        public boolean bookinfo = true;
        public boolean numbering = true;
        public String titlemode = "auto";
        public long delayMs = 500;
        public int concurrency = 4;
        public String title = "";
        public String author = "";
        public String targetPath = "";
        public boolean register = true;
    }

    private Engine() {
    }

    private ExecutorService worker() {
        if (worker == null || worker.isShutdown()) {
            worker = Executors.newSingleThreadExecutor();
        }
        return worker;
    }

    public boolean busy() {
        return "parsing".equals(phase) || "fetching".equals(phase) || "building".equals(phase);
    }

    public void log(String s) {
        logs.add(s);
        while (logs.size() > 500) logs.remove(0);
    }

    private void set(String p, String m) {
        phase = p;
        message = m;
        rev++;
    }

    public void cancel() {
        cancelFlag = true;
    }

    public void loadStores(Context c) {
        List<Stores.WatchItem> w = Stores.loadWatch(c);
        watch.clear();
        watch.addAll(w);
        List<Stores.HistItem> h = Stores.loadHistory(c);
        history.clear();
        history.addAll(h);
    }

    /* ==================== 解析 ==================== */

    public void parse(final Context ctx, final String input, final boolean expand, final Done cb) {
        if (busy()) {
            toast(cb, false, "有任务正在进行");
            return;
        }
        cancelFlag = false;
        logs.clear();
        set("parsing", "正在解析链接…");
        worker().submit(() -> {
            boolean ok = false;
            String msg = "";
            try {
                String raw = input == null ? "" : input.trim();
                if (raw.isEmpty()) throw new Exception("链接为空");
                if (raw.contains("appmsgalbum") && raw.contains("album_id=")) {
                    String biz = WxApi.param(raw, "__biz");
                    String aid = WxApi.param(raw, "album_id");
                    if (biz.isEmpty() || aid.isEmpty()) throw new Exception("链接里缺少 __biz 或 album_id");
                    loadAlbum(ctx, biz, aid);
                    msg = "已载入《" + album.title + "》" + articles.size() + " 篇";
                } else if (raw.contains("mp.weixin.qq.com")) {
                    loadArticle(ctx, raw, expand);
                    msg = album.url != null && articles.size() > 1
                            ? "已反查到合集《" + album.title + "》，共 " + articles.size() + " 篇"
                            : "已载入单篇文章";
                } else {
                    throw new Exception("无法识别的链接，请粘贴公众号合集或文章链接");
                }
                ok = true;
                set("ready", msg);
                log(msg);
            } catch (Exception e) {
                msg = e.getMessage() == null ? e.toString() : e.getMessage();
                set("error", msg);
                log("解析失败：" + msg);
            }
            final boolean fok = ok;
            final String fmsg = msg;
            main.post(() -> cb.run(fok, fmsg));
        });
    }

    private void loadAlbum(Context ctx, String biz, String albumId) throws Exception {
        WxApi.AlbumResult r = WxApi.fetchAlbum(biz, albumId, this::log);
        album = r.album;
        total = r.articles.size();
        done = 0;
        ok = 0;
        fail = 0;
        current = "";
        articles = r.articles;
    }

    private void loadArticle(Context ctx, String url, boolean expand) throws Exception {
        String html = WxApi.get(url);
        Model.Chapter ch = WxApi.parseArticle(html);
        String[] found = expand ? WxApi.sniffAlbum(html, url) : null;
        if (found != null) {
            try {
                loadAlbum(ctx, found[0], found[1]);
                String mid = WxApi.param(url, "mid");
                if (mid.isEmpty()) {
                    java.util.regex.Matcher m = java.util.regex.Pattern
                            .compile("var\\s+mid\\s*=\\s*[\"']?(\\d{6,})").matcher(html);
                    if (m.find()) mid = m.group(1);
                }
                for (Model.Article a : articles) {
                    if (a.msgid.equals(mid)) a.current = true;
                }
                return;
            } catch (Exception e) {
                log("反查合集失败，按单篇处理：" + e.getMessage());
            }
        }
        Model.Album a = new Model.Album();
        a.title = ch.title.isEmpty() ? "公众号文章" : ch.title;
        a.author = ch.account;
        a.albumId = "single";
        a.url = url;
        Model.Article art = new Model.Article();
        art.title = a.title;
        art.url = url;
        art.msgid = "0";
        art.itemidx = "1";
        art.pos = 1;
        art.ctime = ch.ctime;
        art.current = true;
        List<Model.Article> list = new ArrayList<>();
        list.add(art);
        album = a;
        articles = list;
        total = 1;
        done = ok = fail = 0;
        current = "";
    }

    /* ==================== 抓取 + 打包 ==================== */

    public void build(final Context ctx, final Set<String> keys, final Opts o, final Done cb) {
        if (busy()) {
            toast(cb, false, "有任务正在进行");
            return;
        }
        cancelFlag = false;
        final Model.Album alb = album;
        if (alb == null || articles.isEmpty()) {
            toast(cb, false, "没有可生成的章节");
            return;
        }
        final List<Model.Article> picked = new ArrayList<>();
        for (Model.Article a : articles) if (keys.contains(a.key())) picked.add(a);
        if (picked.isEmpty()) {
            toast(cb, false, "请至少选择一篇");
            return;
        }
        done = 0;
        ok = 0;
        fail = 0;
        total = picked.size();
        for (Model.Article a : picked) {
            a.status = "pending";
            a.chars = 0;
        }
        set("fetching", o.concurrency > 1 ? "开始抓取（" + o.concurrency + " 线程）…" : "开始抓取正文…");

        worker().submit(() -> {
            boolean success = false;
            String msg = "";
            try {
                File out = resolveOutput(ctx, alb, o);
                List<Model.Chapter> chapters = fetchAll(ctx, alb, picked, o);
                if (cancelFlag) {
                    set("cancelled", "已取消（完成 " + ok + " 篇）");
                    finalMsg(cb, false, "已取消");
                    return;
                }
                if (chapters.isEmpty()) throw new Exception("一篇都没抓到，请检查网络或稍后重试");

                set("building", "正在打包 EPUB…");
                Cleaner.dropTemplateImages(chapters);
                Cleaner.dropCrossTitles(chapters);
                Cleaner.dropTailSignature(chapters, alb.title, o.title);
                log("开始打包：" + chapters.size() + " 章");
                EpubBuilder.Result r = EpubBuilder.build(alb, chapters, o.images, o.bookinfo, out, this::log);
                resultPath = out.getAbsolutePath();
                resultName = out.getName();
                resultSize = out.length();
                resultChapters = chapters.size();
                resultChars = r.chars;
                resultImages = r.images;

                if (o.register) {
                    try {
                        record(ctx, alb, chapters, o, out);
                    } catch (Exception e) {
                        log("登记书架/历史失败：" + e.getMessage());
                    }
                }
                success = true;
                msg = "完成：" + out.getName();
                set("done", msg);
                log("已生成：" + out.getName() + "（" + Model.fmtSize(out.length()) + "）");
            } catch (Exception e) {
                msg = e.getMessage() == null ? e.toString() : e.getMessage();
                set("error", "生成失败：" + msg);
                log("生成失败：" + msg);
            }
            final boolean f = success;
            final String m = msg;
            main.post(() -> cb.run(f, m));
        });
    }

    private void finalMsg(Done d, boolean ok, String m) {
        main.post(() -> d.run(ok, m));
    }

    private File resolveOutput(Context ctx, Model.Album alb, Opts o) {
        String name = (o.title == null || o.title.isEmpty()) ? alb.title : o.title;
        name = safe(name);
        if (o.targetPath != null && !o.targetPath.isEmpty()) {
            return new File(o.targetPath);
        }
        return new File(Stores.outDir(ctx), name + ".epub");
    }

    public static String safe(String s) {
        String r = s == null ? "" : s.replaceAll("[\\\\/:*?\"<>|\\r\\n]+", "_").trim();
        if (r.isEmpty()) r = "公众号合集";
        if (r.length() > 80) r = r.substring(0, 80);
        return r;
    }

    private List<Model.Chapter> fetchAll(Context ctx, Model.Album alb, List<Model.Article> picked, Opts o)
            throws Exception {
        int n = Math.max(1, Math.min(10, o.concurrency));
        ExecutorService pool = n > 1
                ? Executors.newFixedThreadPool(n)
                : Executors.newSingleThreadExecutor();
        final Model.Chapter[] slots = new Model.Chapter[picked.size()];
        final AtomicInteger idx = new AtomicInteger(0);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < picked.size(); i++) {
            final int index = i;
            final Model.Article art = picked.get(i);
            futures.add(pool.submit(() -> {
                if (cancelFlag) return null;
                art.status = "fetching";
                current = art.title;
                rev++;
                try {
                    Model.Chapter ch = fetchOne(ctx, alb, art, o);
                    slots[index] = ch;
                    art.status = "done";
                    art.chars = Cleaner.chars(ch.blocks);
                    synchronized (this) {
                        ok++;
                        done++;
                    }
                    log(String.format(Locale.US, "  [%d/%d] ✓ %s %s", index + 1, picked.size(),
                            Model.fmtChars(art.chars), art.title));
                } catch (Exception e) {
                    art.status = "fail";
                    synchronized (this) {
                        fail++;
                        done++;
                    }
                    log(String.format(Locale.US, "  [%d/%d] ✗ %s", index + 1, picked.size(),
                            e.getMessage()));
                }
                rev++;
                if (o.delayMs > 0) {
                    try {
                        Thread.sleep(o.delayMs + (long) (Math.random() * o.delayMs * 0.6));
                    } catch (InterruptedException ignored) {
                    }
                }
                return null;
            }));
        }
        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.MINUTES);

        List<Model.Chapter> out = new ArrayList<>();
        for (int i = 0; i < slots.length; i++) {
            Model.Chapter ch = slots[i];
            if (ch == null) continue;
            Model.Article art = picked.get(i);
            int no = out.size() + 1;
            String raw = ch.raw == null || ch.raw.isEmpty() ? art.title : ch.raw;
            String[] picked2 = Cleaner.pickTitle(raw, ch.blocks, o.titlemode);
            String title = picked2[0];
            int drop = Integer.parseInt(picked2[1]);
            if (drop >= 0 && drop < ch.blocks.size()) ch.blocks.remove(drop);
            if (o.numbering) title = Cleaner.numbering(title, no, picked.size());
            ch.title = title;
            ch.raw = raw;
            out.add(ch);
        }
        return out;
    }

    private Model.Chapter fetchOne(Context ctx, Model.Album alb, Model.Article art, Opts o) throws Exception {
        File cache = new File(Stores.cacheDir(ctx, alb.albumId), art.key() + ".json");
        Model.Chapter ch = readCache(cache);
        if (ch != null) return ch;
        String html = WxApi.get(art.url, "https://mp.weixin.qq.com/");
        ch = WxApi.parseArticle(html);
        if (ch.title.isEmpty()) ch.title = art.title;
        ch.raw = ch.title;
        Cleaner.strip(ch.blocks);
        writeCache(cache, ch);
        return ch;
    }

    private static String readFile(File f) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private Model.Chapter readCache(File f) {
        if (!f.exists()) return null;
        try {
            String s = readFile(f);
            JSONObject o = new JSONObject(s);
            Model.Chapter ch = new Model.Chapter();
            ch.title = o.optString("title");
            ch.raw = o.optString("raw", ch.title);
            ch.account = o.optString("account");
            ch.ctime = o.optLong("ctime");
            JSONArray arr = o.optJSONArray("blocks");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject b = arr.getJSONObject(i);
                    ch.blocks.add(new Model.Block(b.optInt("t"), b.optString("v")));
                }
            }
            return ch.blocks.isEmpty() ? null : ch;
        } catch (Exception e) {
            return null;
        }
    }

    private void writeCache(File f, Model.Chapter ch) {
        try {
            JSONObject o = new JSONObject();
            o.put("title", ch.title);
            o.put("raw", ch.raw);
            o.put("account", ch.account);
            o.put("ctime", ch.ctime);
            JSONArray arr = new JSONArray();
            for (Model.Block b : ch.blocks) {
                JSONObject x = new JSONObject();
                x.put("t", b.type);
                x.put("v", b.text);
                arr.put(x);
            }
            o.put("blocks", arr);
            try (FileOutputStream os = new FileOutputStream(f)) {
                os.write(o.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
        }
    }

    private void record(Context ctx, Model.Album alb, List<Model.Chapter> chapters, Opts o, File out) {
        String title = (o.title == null || o.title.isEmpty()) ? alb.title : o.title;
        String author = (o.author == null || o.author.isEmpty()) ? alb.author : o.author;
        Stores.HistItem h = new Stores.HistItem();
        h.id = String.valueOf(System.currentTimeMillis());
        h.title = title;
        h.author = author;
        h.path = out.getAbsolutePath();
        h.name = out.getName();
        h.size = out.length();
        h.chapters = chapters.size();
        h.chars = resultChars;
        h.albumId = alb.albumId;
        h.cover = alb.cover;
        h.mode = o.images ? "插图版" : "纯文字";
        h.time = Stores.now();
        Stores.historyPut(ctx, h);
        history.clear();
        history.addAll(Stores.loadHistory(ctx));

        if (!"single".equals(alb.albumId) && !alb.albumId.isEmpty()) {
            Stores.WatchItem w = new Stores.WatchItem();
            w.albumId = alb.albumId;
            w.biz = alb.biz;
            w.title = title;
            w.albumTitle = alb.title;
            w.author = author;
            w.cover = alb.cover;
            w.count = chapters.size();
            w.epub = out.getAbsolutePath();
            w.images = o.images;
            w.lastBuild = Stores.now();
            for (Model.Article a : articles) w.keys.add(a.key());
            Stores.upsertWatch(ctx, w);
            refreshWatch(ctx);
        }
    }

    public void refreshWatch(Context ctx) {
        watch.clear();
        watch.addAll(Stores.loadWatch(ctx));
    }

    /* ==================== 书架 ==================== */

    public void checkWatch(final Context ctx, final Done cb) {
        if (checking) {
            toast(cb, false, "正在检查中");
            return;
        }
        checking = true;
        worker().submit(() -> {
            int fresh = 0;
            List<Stores.WatchItem> items = Stores.loadWatch(ctx);
            for (Stores.WatchItem w : items) {
                try {
                    WxApi.AlbumResult r = WxApi.fetchAlbum(w.biz, w.albumId, null);
                    Set<String> cur = new HashSet<>();
                    for (Model.Article a : r.articles) cur.add(a.key());
                    Set<String> old = new HashSet<>(w.keys);
                    int add = 0;
                    for (String k : cur) if (!old.contains(k)) add++;
                    int del = 0;
                    for (String k : old) if (!cur.contains(k)) del++;
                    w.newCount = add;
                    w.removed = del;
                    w.totalNow = r.articles.size();
                    w.lastCheck = Stores.now();
                    w.error = "";
                    fresh += add;
                } catch (Exception e) {
                    w.error = e.getMessage() == null ? "检查失败" : e.getMessage();
                    w.newCount = -1;
                }
            }
            Stores.saveWatch(ctx, items);
            refreshWatch(ctx);
            checking = false;
            final int f = fresh;
            set("ready", fresh > 0 ? ("发现 " + fresh + " 章更新") : "全部已是最新");
            main.post(() -> cb.run(true, f > 0 ? ("有 " + f + " 章更新") : "全部已是最新"));
        });
    }

    public void updateWatch(final Context ctx, final String albumId, final Done cb) {
        Stores.WatchItem w = Stores.findWatch(ctx, albumId);
        if (w == null) {
            toast(cb, false, "书架里没有这本书");
            return;
        }
        String url = WxApi.ALBUM_API + "?__biz=" + w.biz + "&action=getalbum&album_id=" + w.albumId;
        parse(ctx, url, false, (ok, msg) -> {
            if (!ok) {
                cb.run(false, msg);
                return;
            }
            Opts o = new Opts();
            o.images = w.images;
            o.bookinfo = true;
            o.numbering = true;
            o.delayMs = 400;
            o.concurrency = Stores.prefs(ctx).concurrency;
            o.title = w.title;
            o.author = w.author;
            o.targetPath = w.epub;
            Set<String> keys = new LinkedHashSet<>();
            for (Model.Article a : articles) keys.add(a.key());
            build(ctx, keys, o, cb);
        });
    }

    /* ==================== 工具 ==================== */

    private void toast(Done d, boolean ok, String msg) {
        main.post(() -> d.run(ok, msg));
    }

    public void shutdown() {
        cancelFlag = true;
        if (worker != null) worker.shutdownNow();
    }
}
