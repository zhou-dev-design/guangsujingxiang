package com.wxalbum.nativeapp;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 本地存储：设置、书架、历史。全部是 filesDir 下的 JSON，便于排查。 */
public final class Stores {

    public static final int THEME_DARK = 0;
    public static final int THEME_LIGHT = 1;
    public static final int THEME_MONO = 2;
    public static final int THEME_AUTO = 3;

    public static class Prefs {
        public int theme = THEME_AUTO;
        public int concurrency = 4;
        public String treeUri = "";
        public boolean images = false;
        public boolean bookinfo = true;
    }

    public static class WatchItem {
        public String albumId = "";
        public String biz = "";
        public String title = "";
        public String albumTitle = "";
        public String author = "";
        public String cover = "";
        public int count;
        public List<String> keys = new ArrayList<>();
        public String epub = "";
        public boolean images;
        public boolean bookinfo = true;
        public boolean numbering = true;
        public String titlemode = "auto";
        public String lastBuild = "";
        public String lastCheck = "";
        public int newCount = -1;
        public int removed;
        public int totalNow = -1;
        public String error = "";
    }

    public static class HistItem {
        public String id = "";
        public String title = "";
        public String author = "";
        public String path = "";
        public String name = "";
        public long size;
        public int chapters;
        public int chars;
        public String albumId = "";
        public String cover = "";
        public String mode = "纯文字";
        public String time = "";
        public String uri = "";
        public boolean exists = true;
    }

    private Stores() {
    }

    /* ---------------- 设置 ---------------- */

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("wxalbum", Context.MODE_PRIVATE);
    }

    public static Prefs prefs(Context c) {
        Prefs p = new Prefs();
        SharedPreferences s = sp(c);
        p.theme = s.getInt("theme", THEME_AUTO);
        p.concurrency = s.getInt("concurrency", 4);
        p.treeUri = s.getString("tree", "");
        p.images = s.getBoolean("images", false);
        p.bookinfo = s.getBoolean("bookinfo", true);
        return p;
    }

    public static void savePrefs(Context c, Prefs p) {
        sp(c).edit()
                .putInt("theme", p.theme)
                .putInt("concurrency", p.concurrency)
                .putString("tree", p.treeUri)
                .putBoolean("images", p.images)
                .putBoolean("bookinfo", p.bookinfo)
                .apply();
    }

    /* ---------------- 文件 ---------------- */

    public static File watchFile(Context c) {
        return new File(c.getFilesDir(), "watch.json");
    }

    public static File historyFile(Context c) {
        return new File(c.getFilesDir(), "history.json");
    }

    public static File outDir(Context c) {
        File d = new File(c.getFilesDir(), "output");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static File cacheDir(Context c, String albumId) {
        File d = new File(new File(c.getFilesDir(), "cache"), albumId.isEmpty() ? "single" : albumId);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private static String read(File f) {
        try {
            if (!f.exists()) return "";
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                return new String(bos.toByteArray(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            return "";
        }
    }

    private static void write(File f, String s) {
        try {
            try (FileOutputStream o = new FileOutputStream(f)) {
                o.write(s.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) {
        }
    }

    public static String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(new Date());
    }

    /* ---------------- 书架 ---------------- */

    public static List<WatchItem> loadWatch(Context c) {
        List<WatchItem> out = new ArrayList<>();
        String raw = read(watchFile(c));
        if (raw.isEmpty()) return out;
        try {
            JSONArray arr = new JSONObject(raw).optJSONArray("items");
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                WatchItem w = new WatchItem();
                w.albumId = o.optString("album_id");
                w.biz = o.optString("biz");
                w.title = o.optString("book_title");
                w.albumTitle = o.optString("album_title");
                w.author = o.optString("author");
                w.cover = o.optString("cover");
                w.count = o.optInt("count");
                w.epub = o.optString("epub");
                w.images = o.optBoolean("images");
                w.bookinfo = o.optBoolean("bookinfo", true);
                w.numbering = o.optBoolean("numbering", true);
                w.titlemode = o.optString("titlemode", "auto");
                w.lastBuild = o.optString("last_build");
                w.lastCheck = o.optString("last_check");
                JSONArray ks = o.optJSONArray("keys");
                if (ks != null) {
                    for (int k = 0; k < ks.length(); k++) w.keys.add(ks.optString(k));
                }
                out.add(w);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public static void saveWatch(Context c, List<WatchItem> items) {
        JSONArray arr = new JSONArray();
        for (WatchItem w : items) {
            JSONObject o = new JSONObject();
            try {
                o.put("album_id", w.albumId);
                o.put("biz", w.biz);
                o.put("book_title", w.title);
                o.put("album_title", w.albumTitle);
                o.put("author", w.author);
                o.put("cover", w.cover);
                o.put("count", w.count);
                o.put("epub", w.epub);
                o.put("images", w.images);
                o.put("bookinfo", w.bookinfo);
                o.put("numbering", w.numbering);
                o.put("titlemode", w.titlemode);
                o.put("last_build", w.lastBuild);
                o.put("last_check", w.lastCheck);
                JSONArray ks = new JSONArray();
                for (String k : w.keys) ks.put(k);
                o.put("keys", ks);
            } catch (Exception ignored) {
            }
            arr.put(o);
        }
        JSONObject root = new JSONObject();
        try {
            root.put("items", arr);
        } catch (Exception ignored) {
        }
        write(watchFile(c), root.toString());
    }

    public static void upsertWatch(Context c, WatchItem item) {
        List<WatchItem> items = loadWatch(c);
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).albumId.equals(item.albumId)) {
                items.set(i, item);
                saveWatch(c, items);
                return;
            }
        }
        items.add(0, item);
        saveWatch(c, items);
    }

    public static void removeWatch(Context c, String albumId) {
        List<WatchItem> items = loadWatch(c);
        List<WatchItem> keep = new ArrayList<>();
        for (WatchItem w : items) if (!w.albumId.equals(albumId)) keep.add(w);
        saveWatch(c, keep);
    }

    public static WatchItem findWatch(Context c, String albumId) {
        for (WatchItem w : loadWatch(c)) if (w.albumId.equals(albumId)) return w;
        return null;
    }

    /* ---------------- 历史 ---------------- */

    public static List<HistItem> loadHistory(Context c) {
        List<HistItem> out = new ArrayList<>();
        String raw = read(historyFile(c));
        if (raw.isEmpty()) return out;
        try {
            JSONArray arr = new JSONObject(raw).optJSONArray("items");
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                HistItem h = new HistItem();
                h.id = o.optString("id");
                h.title = o.optString("title");
                h.author = o.optString("author");
                h.path = o.optString("path");
                h.name = o.optString("name");
                h.size = o.optLong("size");
                h.chapters = o.optInt("chapters");
                h.chars = o.optInt("chars");
                h.albumId = o.optString("album_id");
                h.cover = o.optString("cover");
                h.mode = o.optString("mode", "纯文字");
                h.time = o.optString("time");
                h.uri = o.optString("uri");
                h.exists = o.optBoolean("exists", true) && !h.path.isEmpty() && new File(h.path).exists();
                out.add(h);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public static void saveHistory(Context c, List<HistItem> items) {
        JSONArray arr = new JSONArray();
        for (HistItem h : items) {
            JSONObject o = new JSONObject();
            try {
                o.put("id", h.id);
                o.put("title", h.title);
                o.put("author", h.author);
                o.put("path", h.path);
                o.put("name", h.name);
                o.put("size", h.size);
                o.put("chapters", h.chapters);
                o.put("chars", h.chars);
                o.put("album_id", h.albumId);
                o.put("cover", h.cover);
                o.put("mode", h.mode);
                o.put("time", h.time);
                o.put("uri", h.uri);
                o.put("exists", h.exists);
            } catch (Exception ignored) {
            }
            arr.put(o);
        }
        JSONObject root = new JSONObject();
        try {
            root.put("items", arr);
        } catch (Exception ignored) {
        }
        write(historyFile(c), root.toString());
    }

    /** 同一路径覆盖旧记录（更新时）。 */
    public static void historyPut(Context c, HistItem item) {
        List<HistItem> items = loadHistory(c);
        for (int i = 0; i < items.size(); i++) {
            if (!item.path.isEmpty() && items.get(i).path.equals(item.path)) {
                item.id = items.get(i).id;
                items.set(i, item);
                saveHistory(c, items);
                return;
            }
        }
        items.add(0, item);
        if (items.size() > 500) items = items.subList(0, 500);
        saveHistory(c, items);
    }

    public static void historyDelete(Context c, List<String> ids, String mode) {
        List<HistItem> items = loadHistory(c);
        List<HistItem> keep = new ArrayList<>();
        for (HistItem h : items) {
            if (!ids.contains(h.id)) {
                keep.add(h);
                continue;
            }
            if ("file".equals(mode) || "both".equals(mode)) {
                if (!h.path.isEmpty()) {
                    File f = new File(h.path);
                    if (f.exists()) f.delete();
                }
            }
            if ("file".equals(mode)) {
                h.exists = false;
                h.uri = "";
                keep.add(h);
            }
        }
        saveHistory(c, keep);
    }
}
