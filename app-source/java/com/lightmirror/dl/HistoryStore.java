package com.lightmirror.dl;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 下载记录，只存本机。
 * <p>
 * 这里只管「存多少」——硬上限 500 条，防止无限增长；
 * 界面上「显示多少条」是另一个设置（{@link Settings#historyMax}），
 * 调小显示条数不会把记录删掉，调回来还能看见。
 */
public final class HistoryStore {

    private static final String KEY = "history_v1";
    /** 存储硬上限：超过就丢最老的，跟界面显示条数无关 */
    public static final int HARD_MAX = 500;

    public static class Item {
        public String file = "";
        public String repo = "";
        public String mirror = "";
        public String url = "";
        public String state = "done"; // done / failed / cancelled
        public long size;
        public double speed;
        public long time;

        public Item() {
        }

        public Item(String file, String repo, String mirror, String state, long size, double speed) {
            this.file = file;
            this.repo = repo;
            this.mirror = mirror;
            this.state = state;
            this.size = size;
            this.speed = speed;
            this.time = System.currentTimeMillis();
        }
    }

    private HistoryStore() {
    }

    private static SharedPreferences p(Context ctx) {
        return MirrorCatalog.prefs(ctx);
    }

    public static List<Item> load(Context ctx) {
        List<Item> out = new ArrayList<>();
        String raw = p(ctx).getString(KEY, null);
        if (raw == null) return out;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Item it = new Item();
                it.file = o.optString("file");
                it.repo = o.optString("repo");
                it.mirror = o.optString("mirror");
                it.url = o.optString("url");
                it.state = o.optString("state", "done");
                it.size = o.optLong("size");
                it.speed = o.optDouble("speed");
                it.time = o.optLong("time");
                out.add(it);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public static void add(Context ctx, Item item) {
        List<Item> list = load(ctx);
        list.add(0, item);
        while (list.size() > HARD_MAX) list.remove(list.size() - 1);
        save(ctx, list);
    }

    /** 一行的稳定标识：时间 + 文件名，够唯一，也不用给老数据补字段 */
    public static String key(Item it) {
        return it.time + "|" + it.file;
    }

    /** 按条删除，返回真正删掉的条数 */
    public static int remove(Context ctx, Collection<Item> targets) {
        if (targets == null || targets.isEmpty()) return 0;
        Set<String> keys = new HashSet<>();
        for (Item it : targets) keys.add(key(it));
        List<Item> list = load(ctx);
        List<Item> keep = new ArrayList<>(list.size());
        int removed = 0;
        for (Item it : list) {
            if (keys.contains(key(it))) removed++;
            else keep.add(it);
        }
        if (removed > 0) save(ctx, keep);
        return removed;
    }

    /** 记录条数，不必把整份 JSON 解析两遍 */
    public static int count(Context ctx) {
        return load(ctx).size();
    }

    /** 清空全部记录，返回原来有多少条 */
    public static int clear(Context ctx) {
        int n = load(ctx).size();
        p(ctx).edit().remove(KEY).apply();
        return n;
    }

    private static void save(Context ctx, List<Item> list) {
        JSONArray arr = new JSONArray();
        try {
            for (Item it : list) {
                JSONObject o = new JSONObject();
                o.put("file", it.file);
                o.put("repo", it.repo);
                o.put("mirror", it.mirror);
                o.put("url", it.url);
                o.put("state", it.state);
                o.put("size", it.size);
                o.put("speed", it.speed);
                o.put("time", it.time);
                arr.put(o);
            }
        } catch (Exception ignored) {
        }
        p(ctx).edit().putString(KEY, arr.toString()).apply();
    }
}
