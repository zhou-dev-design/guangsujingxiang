package com.lightmirror.dl;

import java.util.Locale;

/**
 * 一个镜像 = 前缀 + 拼接方式。
 *
 * 两种拼接：
 * - prefix（默认）：把完整原始链接拼在后面，例如 https://ghfast.top/https://github.com/a/b/...
 * - ghpath：只拼 GitHub 的路径，例如 https://xget.xi-xu.me/gh/a/b/releases/...
 *
 * 另外记录最近一次测速成绩，方便在管理页判断哪些镜像「长期用不上」。
 */
public class Mirror {

    public static final String MODE_PREFIX = "prefix";
    public static final String MODE_GH_PATH = "ghpath";

    public final String name;
    public final String base;
    public String mode = MODE_PREFIX;
    public boolean enabled = true;

    public long lastCheck;
    public int lastLatency;
    public double lastSpeed;
    public int failStreak;

    public Mirror(String name, String base) {
        this(name, base, MODE_PREFIX);
    }

    public Mirror(String name, String base, String mode) {
        this.name = name == null ? "" : name;
        this.base = base == null ? "" : base;
        this.mode = mode == null ? MODE_PREFIX : mode;
    }

    public boolean isDirect() {
        return base.length() == 0;
    }

    public boolean isGhPath() {
        return MODE_GH_PATH.equals(mode);
    }

    /** 拼出这个镜像实际要请求的 URL */
    public String buildUrl(String target) {
        if (isDirect()) return target;
        String b = base.endsWith("/") ? base : base + "/";
        if (isGhPath()) {
            String p = ghPath(target);
            if (p != null) return b + p;
            // 非 github.com 的链接（raw / objects 等）退回完整拼接，交由镜像自己决定
            return b + target;
        }
        return b + target;
    }

    /** 把 https://github.com/owner/repo/... 变成 owner/repo/...；非 github.com 返回 null */
    public static String ghPath(String target) {
        if (target == null) return null;
        String s = target;
        int scheme = s.indexOf("://");
        if (scheme >= 0) s = s.substring(scheme + 3);
        if (s.startsWith("github.com/")) return s.substring("github.com/".length());
        if (s.startsWith("www.github.com/")) return s.substring("www.github.com/".length());
        return null;
    }

    /** 这个镜像能不能服务这条链接 */
    public boolean supports(String target) {
        if (isDirect() || !isGhPath()) return true;
        return ghPath(target) != null;
    }

    public String modeLabel() {
        if (isDirect()) return "直连";
        return isGhPath() ? "拼 GitHub 路径" : "拼完整链接";
    }

    public void record(boolean ok, int latencyMs, double speedMbps) {
        lastCheck = System.currentTimeMillis();
        if (ok) {
            lastLatency = latencyMs;
            lastSpeed = speedMbps;
            failStreak = 0;
        } else {
            failStreak++;
        }
    }

    public String statusText() {
        if (lastCheck == 0) return "还没测过";
        if (failStreak > 0) {
            return "连续失败 " + failStreak + " 次" + (failStreak >= 3 ? " · 建议删除" : "");
        }
        return String.format(Locale.US, "上次 %.1f MB/s · %d ms", lastSpeed, lastLatency);
    }

    @Override
    public String toString() {
        return name;
    }
}
