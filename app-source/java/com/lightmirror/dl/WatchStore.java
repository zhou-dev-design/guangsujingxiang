package com.lightmirror.dl;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 追踪的项目。只存仓库、格式、以及「已经下到哪个版本」。 */
public final class WatchStore {

    private static final String KEY = "watch_v1";

    public static class Watch {
        public String repo = "";
        /** 老版本只有一个 kind，现在只用来迁移，不再参与筛选 */
        public String kind = "全部";
        /** 这个项目自己的格式小范围；空 = 跟随设置里的大范围 */
        public final List<String> formats = new ArrayList<>();
        /** 选中的标签指纹（和 ReleaseChecker.Asset.vkey 对应），可多选；空 = 全部文件 */
        public final List<String> tags = new ArrayList<>();
        /** 只看哪种架构的安装包，空串表示全部显示。安卓端默认 arm64-v8a */
        public String abiFilter = "arm64-v8a";
        public String lastTag = "";
        public String lastFile = "";
        /** 老版本的单选标签，读取时并进 tags，之后不再写 */
        public String variant = "";
        public String variantLabel = "";
        /** 标签行是否摊开 */
        public boolean tagsOpen = false;
        public long checkedAt;

        public Watch() {
        }

        public Watch(String repo) {
            this.repo = repo;
        }
    }

    private WatchStore() {
    }

    private static SharedPreferences p(Context ctx) {
        return MirrorCatalog.prefs(ctx);
    }

    public static List<Watch> load(Context ctx) {
        List<Watch> out = new ArrayList<>();
        String raw = p(ctx).getString(KEY, null);
        if (raw == null) return out;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Watch w = new Watch();
                w.repo = o.optString("repo");
                w.kind = o.optString("kind", "全部");
                readList(o.optJSONArray("formats"), w.formats);
                readList(o.optJSONArray("tags"), w.tags);
                w.abiFilter = o.optString("abiFilter", "arm64-v8a");
                w.lastTag = o.optString("lastTag");
                w.lastFile = o.optString("lastFile");
                w.variant = o.optString("variant");
                w.variantLabel = o.optString("variantLabel");
                // 老版本的单选标签是「上次下载的目标」，不是筛选条件；
                // 不搬进 tags，免得升级后又被一个旧标签挡住别的格式。
                w.tagsOpen = o.optBoolean("tagsOpen", false);
                w.checkedAt = o.optLong("checkedAt");
                if (!w.repo.isEmpty()) out.add(w);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public static void save(Context ctx, List<Watch> list) {
        JSONArray arr = new JSONArray();
        try {
            for (Watch w : list) {
                JSONObject o = new JSONObject();
                o.put("repo", w.repo);
                o.put("kind", w.kind);
                o.put("formats", writeList(w.formats));
                o.put("tags", writeList(w.tags));
                o.put("abiFilter", w.abiFilter == null ? "" : w.abiFilter);
                o.put("lastTag", w.lastTag);
                o.put("lastFile", w.lastFile);
                o.put("variant", w.variant == null ? "" : w.variant);
                o.put("variantLabel", w.variantLabel == null ? "" : w.variantLabel);
                o.put("tagsOpen", w.tagsOpen);
                o.put("checkedAt", w.checkedAt);
                arr.put(o);
            }
        } catch (Exception ignored) {
        }
        p(ctx).edit().putString(KEY, arr.toString()).apply();
    }

    private static void readList(JSONArray arr, List<String> into) {
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            String s = arr.optString(i, "").trim().toLowerCase(java.util.Locale.US);
            if (!s.isEmpty() && !into.contains(s)) into.add(s);
        }
    }

    private static JSONArray writeList(List<String> list) {
        JSONArray arr = new JSONArray();
        if (list != null) for (String s : list) arr.put(s);
        return arr;
    }

    public static Watch find(Context ctx, String repo) {
        for (Watch w : load(ctx)) if (w.repo.equalsIgnoreCase(repo)) return w;
        return null;
    }

    public static void upsert(Context ctx, String repo) {
        List<Watch> list = load(ctx);
        for (Watch w : list) {
            if (w.repo.equalsIgnoreCase(repo)) return;
        }
        list.add(new Watch(repo));
        save(ctx, list);
    }

    /** 项目自己的格式小范围；传空表示跟随设置里的大范围 */
    public static void setFormats(Context ctx, String repo, List<String> fmts) {
        List<Watch> list = load(ctx);
        for (Watch w : list) {
            if (w.repo.equalsIgnoreCase(repo)) {
                w.formats.clear();
                if (fmts != null) for (String f : fmts) if (!w.formats.contains(f)) w.formats.add(f);
                save(ctx, list);
                return;
            }
        }
    }

    /** 多选标签：传空表示不过滤，显示该版本全部文件 */
    public static void setTags(Context ctx, String repo, List<String> tags) {
        List<Watch> list = load(ctx);
        for (Watch w : list) {
            if (w.repo.equalsIgnoreCase(repo)) {
                w.tags.clear();
                if (tags != null) for (String t : tags) if (!w.tags.contains(t)) w.tags.add(t);
                w.variant = "";
                w.variantLabel = "";
                save(ctx, list);
                return;
            }
        }
    }

    public static void remove(Context ctx, String repo) {
        List<Watch> list = load(ctx);
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).repo.equalsIgnoreCase(repo)) list.remove(i);
        }
        save(ctx, list);
    }

    /** 手动换过一次架构偏好后，以后这个项目都按它找 */
    public static void setAbiFilter(Context ctx, String repo, String abi) {
        List<Watch> list = load(ctx);
        for (Watch w : list) {
            if (w.repo.equalsIgnoreCase(repo)) {
                w.abiFilter = abi == null ? "" : abi;
                save(ctx, list);
                return;
            }
        }
    }

    /** 选中某个标签（单选用法保留给老调用）：以后「下载最新」就下它 */
    public static void setVariant(Context ctx, String repo, String vkey, String label) {
        List<Watch> list = load(ctx);
        for (Watch w : list) {
            if (w.repo.equalsIgnoreCase(repo)) {
                w.variant = vkey == null ? "" : vkey;
                w.variantLabel = label == null ? "" : label;
                w.tags.clear();
                if (w.variant != null && !w.variant.isEmpty()) w.tags.add(w.variant);
                w.tagsOpen = false;
                save(ctx, list);
                return;
            }
        }
    }

    /** 摊开 / 收起全部标签 */
    public static void setTagsOpen(Context ctx, String repo, boolean open) {
        List<Watch> list = load(ctx);
        for (Watch w : list) {
            if (w.repo.equalsIgnoreCase(repo)) {
                w.tagsOpen = open;
                save(ctx, list);
                return;
            }
        }
    }

    /** 接口拉取成功后回填时间戳，让「10 分钟内不重复查」真正生效 */
    public static void markChecked(Context ctx, String repo) {
        if (repo == null || repo.isEmpty()) return;
        List<Watch> list = load(ctx);
        boolean dirty = false;
        for (Watch w : list) {
            if (w.repo.equalsIgnoreCase(repo)) {
                long now = System.currentTimeMillis();
                if (w.checkedAt != now) {
                    w.checkedAt = now;
                    dirty = true;
                }
            }
        }
        if (dirty) save(ctx, list);
    }

    /** 下载完成后回填：这个仓库已经拿到了哪个 tag 的哪个文件 */
    public static void markDownloaded(Context ctx, String repo, String tag, String file) {
        if (repo == null || repo.isEmpty()) return;
        List<Watch> list = load(ctx);
        for (Watch w : list) {
            if (w.repo.equalsIgnoreCase(repo)) {
                w.lastTag = tag == null ? "" : tag;
                w.lastFile = file == null ? "" : file;
                w.checkedAt = System.currentTimeMillis();
                save(ctx, list);
                return;
            }
        }
    }
}
