package com.lightmirror.dl;

import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.net.URLDecoder;
import java.util.Locale;

/**
 * 把用户粘贴的各种 GitHub 链接统一成「可直接 HTTP 下载的原始地址」+ 文件名。
 * 支持：release 附件、archive 压缩包、blob/raw 文件、以及任意普通直链。
 */
public final class GithubUrlParser {

    public static class Info {
        public String url;       // 规范化后的原始下载地址
        public String fileName;  // 落盘文件名
        public String repo;
        /** 来自版本追踪时带上：release tag 与仓库名，用于回填「已下载版本」 */
        public String tag;
        public String watchRepo;      // owner/repo 或主机名
        public String kind;      // Release / Archive / Raw / 直链 / 页面
        public String host;
        public boolean downloadable;

        Info(String url, String fileName, String repo, String kind, String host, boolean dl) {
            this.url = url;
            this.fileName = fileName;
            this.repo = repo;
            this.kind = kind;
            this.host = host;
            this.downloadable = dl;
        }
    }

    private GithubUrlParser() {
    }

    public static Info parse(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.length() == 0) return null;
        // 去掉复制时夹带的全角空格与引号
        s = s.replace("\u3000", "").replace("\"", "").replace("'", "");
        if (!s.startsWith("http://") && !s.startsWith("https://")) return null;
        if (s.contains(" ")) s = s.substring(0, s.indexOf(' '));

        URL u;
        try {
            u = new URL(s);
        } catch (Exception e) {
            return null;
        }
        String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.US);
        String path = u.getPath() == null ? "" : u.getPath();
        String[] seg = splitPath(path);

        if (host.equals("github.com") || host.equals("www.github.com")) {
            if (seg.length < 2) return null;
            String repo = seg[0] + "/" + seg[1];
            if (seg.length >= 5 && seg[2].equals("releases") && seg[3].equals("download")) {
                return new Info(s, safeName(seg[seg.length - 1]), repo, "Release", host, true);
            }
            if (seg.length >= 4 && seg[2].equals("archive")) {
                String last = seg[seg.length - 1];
                String refName = last.contains(".") ? last.substring(0, last.lastIndexOf('.')) : last;
                String ext = last.contains(".") ? last.substring(last.lastIndexOf('.')) : "";
                return new Info(s, safeName(seg[1] + "-" + refName + ext), repo, "Archive", host, true);
            }
            if (seg.length >= 5 && (seg[2].equals("blob") || seg[2].equals("raw"))) {
                StringBuilder p = new StringBuilder();
                for (int i = 2; i < seg.length; i++) {
                    if (p.length() > 0) p.append('/');
                    p.append(seg[i]);
                }
                String rawUrl = "https://raw.githubusercontent.com/" + repo + "/" + p;
                return new Info(rawUrl, safeName(seg[seg.length - 1]), repo, "Raw", "raw.githubusercontent.com", true);
            }
            if (seg.length >= 2) {
                return new Info(s, safeName(seg[1] + "-" + seg[0]), repo, "页面", host, false);
            }
            return null;
        }

        if (host.equals("raw.githubusercontent.com") || host.equals("codeload.github.com")) {
            String repo = seg.length >= 2 ? seg[0] + "/" + seg[1] : host;
            return new Info(s, safeName(seg.length > 0 ? seg[seg.length - 1] : "download"),
                    repo, "Raw", host, true);
        }

        // 任意直链：文件名取最后一段
        String name = seg.length > 0 ? safeName(seg[seg.length - 1]) : "download.bin";
        return new Info(s, name, host, "直链", host, true);
    }

    private static String[] splitPath(String path) {
        String p = path.startsWith("/") ? path.substring(1) : path;
        if (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        if (p.length() == 0) return new String[0];
        return p.split("/");
    }

    private static String safeName(String s) {
        String name = s;
        int q = name.indexOf('?');
        if (q >= 0) name = name.substring(0, q);
        try {
            name = URLDecoder.decode(name.replace("+", "%2B"), "UTF-8");
        } catch (UnsupportedEncodingException ignored) {
        }
        name = name.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "_");
        if (name.length() > 100) {
            String ext = "";
            int dot = name.lastIndexOf('.');
            if (dot > 0 && name.length() - dot <= 8) ext = name.substring(dot);
            name = name.substring(0, 92) + ext;
        }
        if (name.length() == 0) name = "download.bin";
        return name;
    }
}
