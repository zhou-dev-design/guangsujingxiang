package com.lightmirror.dl;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 所有可调参数的唯一入口：读的时候统一做范围收敛，
 * 界面上输错、老版本存了脏值，都不会把引擎带进沟里。
 */
public final class Settings {

    /* 同时下载的任务数 */
    public static final int CONC_MIN = 1, CONC_MAX = 10, CONC_DEF = 3;
    /* 单个文件切几段并发拉：默认拉满，让单个文件也吃满带宽 */
    public static final int THREADS_MIN = 1, THREADS_MAX = 10, THREADS_DEF = 10;
    /* 测速探测分片 */
    public static final int CHUNK_MIN = 32, CHUNK_MAX = 4096, CHUNK_DEF = 256;   // KB
    /* 单个镜像超时 */
    public static final int TIMEOUT_MIN = 3, TIMEOUT_MAX = 60, TIMEOUT_DEF = 8;  // 秒
    /* 测速结果复用周期 */
    public static final int TTL_MIN = 1, TTL_MAX = 720, TTL_DEF = 10;            // 分钟
    /* 历史页最多显示多少条 */
    public static final int HIST_MIN = 5, HIST_MAX = 200, HIST_DEF = 20;
    /* 下载失败后最多再换几个镜像重试 */
    public static final int TRY_MIN = 1, TRY_MAX = 10, TRY_DEF = 3;

    private Settings() {
    }

    private static SharedPreferences p(Context c) {
        return MirrorCatalog.prefs(c);
    }

    public static int concurrent(Context c) {
        return clamp(p(c).getInt("concurrent", CONC_DEF), CONC_MIN, CONC_MAX);
    }

    public static void setConcurrent(Context c, int v) {
        p(c).edit().putInt("concurrent", clamp(v, CONC_MIN, CONC_MAX)).apply();
    }

    public static boolean multiThread(Context c) {
        return p(c).getBoolean("multiThread", true);
    }

    public static void setMultiThread(Context c, boolean on) {
        p(c).edit().putBoolean("multiThread", on).apply();
    }

    public static int threads(Context c) {
        return clamp(p(c).getInt("threads", THREADS_DEF), THREADS_MIN, THREADS_MAX);
    }

    public static void setThreads(Context c, int v) {
        p(c).edit().putInt("threads", clamp(v, THREADS_MIN, THREADS_MAX)).apply();
    }

    public static int chunkKB(Context c) {
        return clamp(p(c).getInt("chunkKB", CHUNK_DEF), CHUNK_MIN, CHUNK_MAX);
    }

    public static void setChunkKB(Context c, int v) {
        p(c).edit().putInt("chunkKB", clamp(v, CHUNK_MIN, CHUNK_MAX)).apply();
    }

    public static int timeoutSec(Context c) {
        return clamp(p(c).getInt("timeoutSec", TIMEOUT_DEF), TIMEOUT_MIN, TIMEOUT_MAX);
    }

    public static void setTimeoutSec(Context c, int v) {
        p(c).edit().putInt("timeoutSec", clamp(v, TIMEOUT_MIN, TIMEOUT_MAX)).apply();
    }

    public static boolean cacheRank(Context c) {
        return p(c).getBoolean("cache", true);
    }

    public static void setCacheRank(Context c, boolean on) {
        p(c).edit().putBoolean("cache", on).apply();
    }

    public static int ttlMin(Context c) {
        return clamp(p(c).getInt("rankTtlMin", TTL_DEF), TTL_MIN, TTL_MAX);
    }

    public static void setTtlMin(Context c, int v) {
        p(c).edit().putInt("rankTtlMin", clamp(v, TTL_MIN, TTL_MAX)).apply();
    }

    /** 排名实际可用时长：关掉复用就等于 0 */
    public static long rankTtlMs(Context c) {
        return cacheRank(c) ? ttlMin(c) * 60000L : 0L;
    }

    /** 版本页的挑文件方式：false = 单文件（点一个就下），true = 批量（勾选后一起下） */
    public static boolean batchSelect(Context c) {
        return p(c).getBoolean("watchBatch", false);
    }

    public static void setBatchSelect(Context c, boolean on) {
        p(c).edit().putBoolean("watchBatch", on).apply();
    }

    /** 历史页最多显示多少条（只影响列表长度，不删记录、不动已下载的文件） */
    public static int historyMax(Context c) {
        return clamp(p(c).getInt("historyMax", HIST_DEF), HIST_MIN, HIST_MAX);
    }

    public static void setHistoryMax(Context c, int v) {
        p(c).edit().putInt("historyMax", clamp(v, HIST_MIN, HIST_MAX)).apply();
    }

    /** 失败自动换镜像时，最多顺延到第几个镜像（1~10） */
    public static int failoverMax(Context c) {
        return clamp(p(c).getInt("failoverMax", TRY_DEF), TRY_MIN, TRY_MAX);
    }

    public static void setFailoverMax(Context c, int v) {
        p(c).edit().putInt("failoverMax", clamp(v, TRY_MIN, TRY_MAX)).apply();
    }

    public static boolean failover(Context c) {
        return p(c).getBoolean("failover", true);
    }

    public static void setFailover(Context c, boolean on) {
        p(c).edit().putBoolean("failover", on).apply();
    }

    public static boolean resume(Context c) {
        return p(c).getBoolean("resume", true);
    }

    public static void setResume(Context c, boolean on) {
        p(c).edit().putBoolean("resume", on).apply();
    }

    public static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    // ---------------------------------------------------------------- 追踪格式
    //
    // 两级：
    //   大范围 = 这里（设置页），所有项目默认跟着它；
    //   小范围 = 每个追踪项目自己的选择，只能是这里的子集。
    // 格式用扩展名存，顺序就是界面上的顺序。

    /** 手机端常见格式，前四个是默认勾选的 */
    public static final String[] BUILTIN_FORMATS = {
            "apk", "zip", "rar", "pdf", "7z", "apks", "exe", "msi", "dmg", "iso"};
    public static final String[] DEFAULT_FORMATS = {"apk", "zip", "rar", "pdf"};

    private static final String K_GLOBAL = "fmtGlobal";
    private static final String K_EXTRA = "fmtExtra";

    public static String fmtLabel(String ext) {
        if (ext == null) return "";
        if ("7z".equals(ext)) return "7Z";
        if ("apks".equals(ext)) return "APKS";
        return ext.toUpperCase(Locale.US);
    }

    private static List<String> csv(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) return out;
        for (String s : raw.split(",")) {
            String e = s.trim().toLowerCase(Locale.US).replace(".", "");
            if (!e.isEmpty() && !out.contains(e)) out.add(e);
        }
        return out;
    }

    private static String join(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (String s : list) {
            if (sb.length() > 0) sb.append(',');
            sb.append(s);
        }
        return sb.toString();
    }

    /** 大范围：设置页勾选的格式 */
    public static List<String> globalFormats(Context c) {
        List<String> out = csv(p(c).getString(K_GLOBAL, ""));
        if (out.isEmpty()) out.addAll(Arrays.asList(DEFAULT_FORMATS));
        return out;
    }

    public static void setGlobalFormats(Context c, List<String> fmts) {
        List<String> out = new ArrayList<>();
        for (String f : fmts) {
            String e = f.toLowerCase(Locale.US);
            if (!e.isEmpty() && !out.contains(e)) out.add(e);
        }
        if (out.isEmpty()) out.addAll(Arrays.asList(DEFAULT_FORMATS));
        p(c).edit().putString(K_GLOBAL, join(out)).apply();
    }

    /** 手动加过的自定义扩展名（内置的之外） */
    public static List<String> extraFormats(Context c) {
        return csv(p(c).getString(K_EXTRA, ""));
    }

    /** 设置页和项目的格式菜单：内置 + 自定义 */
    public static List<String> formatMenu(Context c) {
        List<String> out = new ArrayList<>(Arrays.asList(BUILTIN_FORMATS));
        for (String e : extraFormats(c)) if (!out.contains(e)) out.add(e);
        for (String e : globalFormats(c)) if (!out.contains(e)) out.add(e);
        return out;
    }

    /** 手动加一个格式，并立刻纳入大范围 */
    public static void addFormat(Context c, String ext) {
        String e = ext == null ? "" : ext.toLowerCase(Locale.US).replaceAll("[^a-z0-9]", "");
        if (e.isEmpty()) return;
        List<String> extra = extraFormats(c);
        if (!extra.contains(e) && !Arrays.asList(BUILTIN_FORMATS).contains(e)) {
            extra.add(e);
            p(c).edit().putString(K_EXTRA, join(extra)).apply();
        }
        List<String> g = globalFormats(c);
        if (!g.contains(e)) {
            g.add(e);
            setGlobalFormats(c, g);
        }
    }

    /** 从菜单里彻底删掉一个自定义格式（内置的只能取消勾选，不能删） */
    public static void removeFormat(Context c, String ext) {
        String e = ext == null ? "" : ext.toLowerCase(Locale.US);
        List<String> extra = extraFormats(c);
        if (extra.remove(e)) p(c).edit().putString(K_EXTRA, join(extra)).apply();
        List<String> g = globalFormats(c);
        if (g.remove(e) && !g.isEmpty()) setGlobalFormats(c, g);
    }

    public static boolean isBuiltin(String ext) {
        return Arrays.asList(BUILTIN_FORMATS).contains(ext == null ? "" : ext.toLowerCase(Locale.US));
    }

    /** 项目自己的小范围：空 = 跟随设置；有值也一定收敛在大范围里 */
    public static List<String> projectFormats(Context c, List<String> own) {
        List<String> g = globalFormats(c);
        if (own == null || own.isEmpty()) return g;
        List<String> keep = new ArrayList<>();
        for (String f : g) if (own.contains(f)) keep.add(f);
        return keep.isEmpty() ? g : keep;
    }
}
