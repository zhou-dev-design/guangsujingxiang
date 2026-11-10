package com.lightmirror.dl;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 镜像目录：内置默认值 + 用户增删启停排序，全部落在 SharedPreferences。 */
public final class MirrorCatalog {

    private static final String PREFS = "lightmirror";
    private static final String KEY = "catalog_v1";
    private static final String KEY_MIGRATED = "catalog_migrated_v2";
    private static final String KEY_REMOVED = "catalog_removed_v1";

    private MirrorCatalog() {
    }

    public static List<Mirror> defaults() {
        List<Mirror> list = new ArrayList<>();
        list.add(new Mirror("ghfast.top", "https://ghfast.top/"));
        // /gh/ 前缀式镜像：拼的是 GitHub 路径，不是完整链接
        list.add(new Mirror("xget.xi-xu.me/gh", "https://xget.xi-xu.me/gh/", Mirror.MODE_GH_PATH));
        list.add(new Mirror("gh-proxy.com", "https://gh-proxy.com/"));
        list.add(new Mirror("ghproxy.net", "https://ghproxy.net/"));
        list.add(new Mirror("hub.gitmirror.com", "https://hub.gitmirror.com/"));
        list.add(new Mirror("gh.llkk.cc", "https://gh.llkk.cc/"));
        list.add(new Mirror("ghproxy.cn", "https://ghproxy.cn/"));
        list.add(new Mirror("ghps.cc", "https://ghps.cc/"));
        list.add(new Mirror("github.moeyy.xyz", "https://github.moeyy.xyz/"));
        list.add(new Mirror("GitHub 直连", ""));
        return list;
    }

    public static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static List<Mirror> load(Context ctx) {
        String raw = prefs(ctx).getString(KEY, null);
        if (raw == null) {
            prefs(ctx).edit().putBoolean(KEY_MIGRATED, true).apply();
            return defaults();
        }
        List<Mirror> list = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Mirror m = new Mirror(o.optString("name"), o.optString("base"),
                        o.optString("mode", Mirror.MODE_PREFIX));
                m.enabled = o.optBoolean("enabled", true);
                m.lastCheck = o.optLong("lastCheck");
                m.lastLatency = o.optInt("lastLatency");
                m.lastSpeed = o.optDouble("lastSpeed");
                m.failStreak = o.optInt("failStreak");
                list.add(m);
            }
        } catch (Exception ignored) {
        }
        if (list.isEmpty()) return defaults();

        // 老版本升级：一次性把新增的内置镜像补进来（用户主动删过的不再补回）
        if (!prefs(ctx).getBoolean(KEY_MIGRATED, false)) {
            list = mergeBuiltins(ctx, list, false);
            save(ctx, list);
            prefs(ctx).edit().putBoolean(KEY_MIGRATED, true).apply();
        }
        return list;
    }

    public static void save(Context ctx, List<Mirror> list) {
        JSONArray arr = new JSONArray();
        try {
            for (Mirror m : list) {
                JSONObject o = new JSONObject();
                o.put("name", m.name);
                o.put("base", m.base);
                o.put("mode", m.mode);
                o.put("enabled", m.enabled);
                o.put("lastCheck", m.lastCheck);
                o.put("lastLatency", m.lastLatency);
                o.put("lastSpeed", m.lastSpeed);
                o.put("failStreak", m.failStreak);
                arr.put(o);
            }
        } catch (Exception ignored) {
        }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply();
    }

    private static Set<String> removed(Context ctx) {
        Set<String> out = new HashSet<>();
        String raw = prefs(ctx).getString(KEY_REMOVED, null);
        if (raw == null) return out;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) out.add(arr.optString(i));
        } catch (Exception ignored) {
        }
        return out;
    }

    /** 用户手动删掉的镜像，别在下次启动时又自动冒出来 */
    public static void markRemoved(Context ctx, String base) {
        Set<String> set = removed(ctx);
        set.add(base);
        JSONArray arr = new JSONArray();
        for (String s : set) arr.put(s);
        prefs(ctx).edit().putString(KEY_REMOVED, arr.toString()).apply();
    }

    /** 「恢复内置镜像」按钮：连删过的也一并恢复 */
    public static List<Mirror> restoreBuiltins(Context ctx, List<Mirror> current) {
        prefs(ctx).edit().remove(KEY_REMOVED).apply();
        List<Mirror> merged = mergeBuiltins(ctx, current, true);
        save(ctx, merged);
        return merged;
    }

    private static List<Mirror> mergeBuiltins(Context ctx, List<Mirror> current, boolean ignoreRemoved) {
        Set<String> gone = ignoreRemoved ? new HashSet<>() : removed(ctx);
        List<Mirror> out = new ArrayList<>(current);
        for (Mirror d : defaults()) {
            boolean exists = false;
            for (Mirror m : out) {
                if (m.base.equalsIgnoreCase(d.base)) {
                    exists = true;
                    // 老数据里已有同域名但没标记模式的，纠正为内置模式
                    if (!m.isDirect() && !Mirror.MODE_GH_PATH.equals(m.mode)
                            && Mirror.MODE_GH_PATH.equals(d.mode)) {
                        m.mode = d.mode;
                    }
                    break;
                }
            }
            if (!exists && !gone.contains(d.base)) out.add(d);
        }
        return out;
    }

    /** 上移 / 下移一位，返回是否真的动了 */
    public static boolean move(List<Mirror> list, int from, int to) {
        if (from < 0 || to < 0 || from >= list.size() || to >= list.size()) return false;
        java.util.Collections.swap(list, from, to);
        return true;
    }
}
