package com.lightmirror.dl;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * 拉取 GitHub 仓库的 release 列表，并按扩展名筛出可下载附件。
 * 只用公开 API（未登录 60 次/小时），失败时给出能看懂的原因。
 *
 * 两个排序约定：
 * 1）版本一律按发布时间的倒序排（最新的在最前），不依赖接口的返回顺序；
 * 2）同一版本里的附件，按「本机能不能装」排序，最后才按文件名。
 */
public final class ReleaseChecker {

    public static class Asset {
        public String name = "";
        public String url = "";
        public long size;
        public String ext = "";
        /** 解析出来的架构标签，可能为空（例如纯文本附件） */
        public String abi = "";
        /** 这个附件最后一次被替换/上传的时间（GitHub 的 updated_at，毫秒） */
        public long updated;
        /** 差异标签：同格式里去掉产品名、版本号后真正不同的部分，例如 便携版 / 安装版 */
        public String variant = "";
        /** 跨版本稳定的指纹，用来记住用户选中的标签 */
        public String vkey = "";
    }

    public static class Release {
        public String tag = "";
        public String title = "";
        public String date = "";
        /** published_at 的毫秒值，排序用 */
        public long published;
        /** created_at 的毫秒值，作为 published 缺失时的兜底 */
        public long created;
        public boolean prerelease;
        /** 这个版本下所有附件里最新的修改时间；没有附件时退回发布时间 */
        public long newest;
        public final List<Asset> assets = new ArrayList<>();
    }

    /** 每页条数：GitHub 单页上限就是 100 */
    public static final int PER_PAGE = 100;
    /** 最多翻几页，防止超大仓库把额度一次吃光（500 个版本足够翻到最早了） */
    public static final int MAX_PAGES = 5;

    public interface Callback {
        void onOk(List<Release> releases);

        void onError(String message);
    }

    private ReleaseChecker() {
    }

    /**
     * 扩展名是否命中筛选条件。exts 为空（或 null）表示不过滤。
     * APKS 归到 APK，7Z 归到 ZIP，免得用户勾了 APK 却看不到 .apks。
     */
    public static boolean match(String fileName, java.util.Collection<String> exts) {
        String ext = extOf(fileName);
        if (ext.isEmpty()) return false;
        if (exts == null || exts.isEmpty()) return true;
        if (exts.contains(ext)) return true;
        if ("apks".equals(ext) && exts.contains("apk")) return true;
        if ("7z".equals(ext) && exts.contains("zip")) return true;
        return false;
    }

    public static String extOf(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "";
        String ext = name.substring(dot + 1).toLowerCase();
        // .tar.gz 这类双后缀按最后一段算即可
        return ext.length() > 8 ? "" : ext;
    }


    // ------------------------------------------------------------ 标签（差异部分）

    private static final java.util.regex.Pattern VER_IN_NAME = java.util.regex.Pattern.compile(
            "(?<![A-Za-z0-9])v?\\d+(?:[._-]\\d+)+(?:[a-z]\\d*)?",
            java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern BUILD_NO = java.util.regex.Pattern.compile(
            "(?<![A-Za-z0-9])r\\d{2,}(?![0-9])", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern YEAR = java.util.regex.Pattern.compile(
            "(?<!\\d)\\d{4,}(?!\\d)");
    private static final java.util.regex.Pattern DIGITS = java.util.regex.Pattern.compile("^\\d+$");

    private static final Map<String, String> WORD_ZH = new LinkedHashMap<>();
    private static final Map<String, String> PHRASE_ZH = new LinkedHashMap<>();

    static {
        WORD_ZH.put("setup", "安装版");
        WORD_ZH.put("installer", "安装版");
        WORD_ZH.put("install", "安装版");
        WORD_ZH.put("portable", "便携版");
        WORD_ZH.put("noinstall", "免安装");
        WORD_ZH.put("unsigned", "未签名");
        WORD_ZH.put("signed", "已签名");
        WORD_ZH.put("universal", "通用");
        WORD_ZH.put("full", "完整版");
        WORD_ZH.put("minimal", "精简版");
        WORD_ZH.put("lite", "轻量版");
        WORD_ZH.put("debug", "调试版");
        WORD_ZH.put("release", "正式版");
        WORD_ZH.put("user", "用户版");
        WORD_ZH.put("machine", "全局版");
        WORD_ZH.put("system", "全局版");
        WORD_ZH.put("x64", "x64");
        WORD_ZH.put("x86_64", "x86_64");
        WORD_ZH.put("x86", "32 位");
        WORD_ZH.put("win32", "32 位");
        WORD_ZH.put("arm64", "ARM64");
        WORD_ZH.put("aarch64", "ARM64");
        WORD_ZH.put("v8a", "ARM64");
        WORD_ZH.put("v7a", "ARMv7");
        WORD_ZH.put("armeabi", "ARMv7");
        WORD_ZH.put("docs", "文档");
        WORD_ZH.put("doc", "文档");
        WORD_ZH.put("manual", "手册");
        WORD_ZH.put("source", "源码");

        PHRASE_ZH.put("user setup", "用户安装");
        PHRASE_ZH.put("machine setup", "全局安装");
        PHRASE_ZH.put("self contained", "自带运行时");
        PHRASE_ZH.put("framework dependent", "需运行时");
        PHRASE_ZH.put("arm64 v8a", "ARM64");
        PHRASE_ZH.put("armeabi v7a", "ARMv7");
        PHRASE_ZH.put("arm64 v8a apk", "ARM64");
    }

    private static String stripExt(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        if (stem.toLowerCase(Locale.US).endsWith(".tar")) {
            stem = stem.substring(0, stem.length() - 4);
        }
        return stem;
    }

    /** 文件名 → 小写、CamelCase 用 - 拆开、各种分隔符统一成 -，便于逐字比对 */
    static String normStem(String name) {
        String stem = stripExt(name);
        stem = stem.replaceAll("([a-z0-9])([A-Z])", "$1-$2");
        stem = stem.replaceAll("([A-Z]+)([A-Z][a-z])", "$1-$2");
        stem = stem.toLowerCase(Locale.US);
        stem = stem.replaceAll("[^a-z0-9]+", "-");
        stem = stem.replaceAll("-{2,}", "-");
        return trimDash(stem);
    }

    private static String trimDash(String s) {
        int a = 0, b = s.length();
        while (a < b && s.charAt(a) == '-') a++;
        while (b > a && s.charAt(b - 1) == '-') b--;
        return s.substring(a, b);
    }

    static String commonPrefix(List<String> ss) {
        if (ss.size() < 2) return "";
        String p = ss.get(0);
        for (String s : ss) {
            int i = 0;
            while (i < p.length() && i < s.length() && p.charAt(i) == s.charAt(i)) i++;
            p = p.substring(0, i);
            if (p.isEmpty()) break;
        }
        if (ss.contains(p)) return p.endsWith("-") ? p : p + "-";
        int cut = p.lastIndexOf('-');
        return cut >= 0 ? p.substring(0, cut + 1) : "";
    }

    static String commonSuffix(List<String> ss, String prefix) {
        List<String> rest = new ArrayList<>();
        for (String s : ss) {
            if (prefix != null && !prefix.isEmpty()) {
                if (!s.startsWith(prefix)) return "";
                String r = s.substring(prefix.length());
                if (r.isEmpty()) return "";
                rest.add(r);
            } else {
                if (s.isEmpty()) return "";
                rest.add(s);
            }
        }
        if (rest.size() < 2) return "";
        StringBuilder first = new StringBuilder(rest.get(0)).reverse();
        for (String r : rest) {
            String rev = new StringBuilder(r).reverse().toString();
            int i = 0;
            while (i < first.length() && i < rev.length() && first.charAt(i) == rev.charAt(i)) i++;
            first.setLength(i);
        }
        String p = first.toString();
        int idx = p.indexOf('-');
        if (idx < 0) return "";
        return new StringBuilder(p.substring(0, idx + 1)).reverse().toString();
    }

    private static boolean cjk(char c) {
        return c >= '\u4e00' && c <= '\u9fff';
    }

    private static String joinWords(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p == null || p.isEmpty()) continue;
            if (sb.length() > 0) {
                char a = sb.charAt(sb.length() - 1);
                char b = p.charAt(0);
                if (!(cjk(a) && cjk(b))) sb.append(' ');
            }
            sb.append(p);
        }
        return sb.toString();
    }

    private static String friendly(List<String> tokens) {
        if (tokens.isEmpty()) return "";
        StringBuilder key = new StringBuilder();
        for (String t : tokens) {
            if (key.length() > 0) key.append(' ');
            key.append(t.toLowerCase(Locale.US));
        }
        String whole = key.toString();
        if (PHRASE_ZH.containsKey(whole)) return PHRASE_ZH.get(whole);

        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < tokens.size()) {
            boolean matched = false;
            for (int span = 3; span >= 2; span--) {
                if (i + span <= tokens.size()) {
                    StringBuilder k = new StringBuilder();
                    for (int j = 0; j < span; j++) {
                        if (j > 0) k.append(' ');
                        k.append(tokens.get(i + j).toLowerCase(Locale.US));
                    }
                    String p = PHRASE_ZH.get(k.toString());
                    if (p != null) {
                        out.add(p);
                        i += span;
                        matched = true;
                        break;
                    }
                }
            }
            if (matched) continue;
            String w = tokens.get(i);
            String low = w.toLowerCase(Locale.US);
            if (WORD_ZH.containsKey(low)) {
                out.add(WORD_ZH.get(low));
            } else if (low.length() <= 4 && low.matches("[a-z]+")) {
                out.add(low.toUpperCase(Locale.US));
            } else if (w.equals(low)) {
                out.add(w.substring(0, 1).toUpperCase(Locale.US) + w.substring(1));
            } else {
                out.add(w);
            }
            i++;
        }
        return joinWords(out);
    }

    /** 剥掉公共部分和版本号后剩下的词 → 标签 */
    private static String labelFromStem(String stem) {
        String s = stem.replaceAll("x86[-_]64", "x86_64");
        s = VER_IN_NAME.matcher(s).replaceAll("-");
        s = BUILD_NO.matcher(s).replaceAll("-");
        s = YEAR.matcher(s).replaceAll("-");
        List<String> toks = new ArrayList<>();
        for (String t : s.split("-")) {
            if (t.isEmpty() || DIGITS.matcher(t).matches()) continue;
            toks.add(t);
        }
        return friendly(toks);
    }

    /** 是不是会被手机端列出来的安装包格式 */
    static boolean installableExt(String ext) {
        if (ext == null) return false;
        switch (ext) {
            case "apk":
            case "apks":
            case "zip":
            case "rar":
            case "7z":
            case "pdf":
            case "exe":
            case "msi":
            case "dmg":
            case "iso":
                return true;
            default:
                return false;
        }
    }

    /** 调试符号、校验和、签名文件不参与标签计算 */
    static boolean noise(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.US);
        String ext = extOf(n);
        if (ext.equals("sig") || ext.equals("asc") || ext.equals("pdb")
                || ext.startsWith("sha") || ext.equals("md5")) return true;
        String stem = stripExt(n);
        for (String bad : new String[]{"symbols", "symbol", "checksums", "checksum",
                "shasums", "sha256sums", "latest.json", "abis.txt"}) {
            if (stem.equals(bad) || stem.endsWith("-" + bad) || stem.startsWith(bad + "-")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 给一个版本里的所有附件打标签：按格式分组，逐字剥掉公共前缀（产品名）
     * 和公共后缀（版本号、架构、扩展名），剩下的就是「便携版 / 安装版」这类差异。
     */
    public static void labelAll(List<Asset> assets) {
        if (assets == null || assets.isEmpty()) return;
        List<Asset> pool = new ArrayList<>();
        for (Asset a : assets) {
            if (a.name == null || a.name.isEmpty()) continue;
            a.vkey = vkeyOf(a.name);
            if (installableExt(a.ext) && !noise(a.name)) pool.add(a);
        }
        if (pool.isEmpty()) pool = assets;
        if (pool.size() == 1) {
            pool.get(0).variant = "标准";
            return;
        }

        Map<String, String> stems = new LinkedHashMap<>();
        List<String> all = new ArrayList<>();
        for (Asset a : pool) {
            String n = normStem(a.name);
            stems.put(a.name, n);
            all.add(n);
        }
        String gPrefix = commonPrefix(all);
        String gSuffix = commonSuffix(all, gPrefix);

        Map<String, List<Asset>> groups = new LinkedHashMap<>();
        for (Asset a : pool) {
            List<Asset> g = groups.get(a.ext);
            if (g == null) {
                g = new ArrayList<>();
                groups.put(a.ext, g);
            }
            g.add(a);
        }

        for (Map.Entry<String, List<Asset>> e : groups.entrySet()) {
            List<Asset> group = e.getValue();
            String prefix = gPrefix, suffix = gSuffix;
            if (group.size() >= 2) {
                List<String> names = new ArrayList<>();
                for (Asset a : group) names.add(stems.get(a.name));
                String p = commonPrefix(names);
                String s = commonSuffix(names, p);
                if (p.length() >= prefix.length()) {
                    prefix = p;
                    suffix = s;
                }
            }
            for (Asset a : group) {
                String stem = stems.get(a.name);
                String core = stem;
                if (!prefix.isEmpty() && stem.startsWith(prefix)) {
                    core = stem.substring(prefix.length());
                } else if (prefix.endsWith("-") && stem.equals(prefix.substring(0, prefix.length() - 1))) {
                    core = "";
                }
                if (!suffix.isEmpty() && core.endsWith(suffix)) {
                    core = core.substring(0, core.length() - suffix.length());
                }
                core = trimDash(core);
                String lab = labelFromStem(core);
                a.variant = lab.isEmpty() ? "标准" : lab;
            }
        }

        // 同一格式内标签撞车时补序号
        Map<String, Integer> seen = new LinkedHashMap<>();
        for (Asset a : pool) {
            String key = a.variant + "|" + a.ext;
            Integer c = seen.get(key);
            if (c == null) {
                seen.put(key, 1);
            } else {
                seen.put(key, c + 1);
                a.variant = a.variant + " #" + (c + 1);
            }
        }
    }

    /** 把版本号抹成 *，得到跨版本稳定的指纹 */
    public static String vkeyOf(String name) {
        String stem = stripExt(name).toLowerCase(Locale.US);
        stem = stem.replaceAll("\\d+(?:\\.\\d+)+[a-z]?\\d*", "*");
        stem = stem.replaceAll("(?<!\\d)\\d{4,}(?!\\d)", "*");
        stem = stem.replaceAll("(?<=-)\\d{3,}(?=-|$)", "*");   // 342 这种构建号
        stem = stem.replaceAll("[\\s_]+", "-");
        stem = stem.replaceAll("-{2,}", "-");
        return trimDash(stem) + "." + extOf(name);
    }

    /**
     * 标签的「语义键」：标签名 + 格式，跨版本稳定。
     *
     * vkey 是文件名指纹，换个版本文件名结构一变（-android-arm64-v8a 与
     * -app-universal-arm64-v8a-release），指纹就对不上，同一枚「ARM64 · APK」
     * 在别的版本里会显示成没选中，筛选也失效。所以筛选与高亮一律用这个键。
     * 同格式撞车补的「#2」也去掉——它只说明这一版里有几个同名文件，跨版本不稳。
     */
    public static String labelKey(Asset a) {
        if (a == null) return "";
        String v = a.variant == null ? "" : a.variant.trim();
        v = v.replaceAll("\\s*#\\d+$", "");
        if (v.isEmpty()) v = "标准";
        return v + "|" + (a.ext == null ? "" : a.ext);
    }

    /** 界面上「标签 · 格式」 */
    public static String variantText(Asset a) {
        if (a == null) return "";
        String ext = a.ext == null ? "" : a.ext.toUpperCase(Locale.US);
        return (a.variant == null || a.variant.isEmpty()) ? ext : a.variant + " · " + ext;
    }

    /** 在文件列表里找和这个指纹对应的那一个 */
    public static Asset byVkey(List<Asset> assets, String vkey) {
        if (vkey == null || vkey.isEmpty() || assets == null) return null;
        for (Asset a : assets) if (vkey.equals(a.vkey)) return a;
        return null;
    }

    // ------------------------------------------------------------ 架构识别与排序

    /**
     * 从文件名里认出 CPU 架构。
     * 认不出来就返回空串——这很常见，说明发布者没在文件名里标 ABI。
     */
    public static String abiOf(String fileName) {
        if (fileName == null) return "";
        String k = fileName.toLowerCase(Locale.US).replace('_', '-').replace(' ', '-');
        if (k.contains("arm64-v8a") || k.contains("arm64") || k.contains("aarch64")
                || k.contains("v8a") || k.contains("-armv8")) {
            return "arm64-v8a";
        }
        if (k.contains("armeabi-v7a") || k.contains("armeabi") || k.contains("armv7")
                || k.contains("v7a")) {
            return "armeabi-v7a";
        }
        if (k.contains("x86-64") || k.contains("amd64") || k.contains("x64")) {
            return "x86_64";
        }
        if (k.contains("x86") || k.contains("i386") || k.contains("i686")) {
            return "x86";
        }
        if (k.contains("universal") || k.contains("-all.") || k.contains("allabi")) {
            return "universal";
        }
        return "";
    }

    /**
     * 按架构偏好收窄安装包。
     * 只在「APK / APKS」这类安装包格式下生效：文案、校验文件、sig 一律不显示，
     * 列表上就剩一个真正能装的包。该架构一个都没有时原样返回，不让列表空掉。
     */
    public static List<Asset> filterByAbi(List<Asset> picks, String abiFilter) {
        if (picks == null) return new ArrayList<>();
        List<Asset> keep = new ArrayList<>();
        if (abiFilter == null || abiFilter.isEmpty() || "全部".equals(abiFilter)) return picks;
        boolean hasMatch = false;
        for (Asset a : picks) {
            if (!isApk(a)) continue;
            if (abiFilter.equals(a.abi)) {
                keep.add(a);
                hasMatch = true;
            }
        }
        for (Asset a : picks) {
            if (!isApk(a)) keep.add(a);      // 非安装包永远保留
        }
        return hasMatch ? keep : picks;
    }

    /** 是不是可以直接装的包 */
    public static boolean isApk(Asset a) {
        return a != null && ("apk".equals(a.ext) || "apks".equals(a.ext));
    }

    /** 给界面看的架构名 */
    public static String abiLabel(String abi) {
        if (abi == null || abi.isEmpty()) return "";
        if ("universal".equals(abi)) return "通用包";
        return abi;
    }

    /**
     * 把一个版本里的附件按「本机优先」排好序：
     * arm64-v8a &gt; 通用包 &gt; armeabi-v7a &gt; 其它，同时避开本机跑不了的架构。
     * 返回的列表已经过滤掉不匹配当前格式的文件；第一个就是推荐下载的那个。
     */
    public static List<Asset> pickOrder(List<Asset> assets, java.util.Collection<String> exts) {
        return pickOrder(assets, exts, android.os.Build.SUPPORTED_ABIS);
    }

    public static List<Asset> pickOrder(List<Asset> assets, java.util.Collection<String> exts,
                                        String[] deviceAbis) {
        List<Asset> out = new ArrayList<>();
        if (assets == null) return out;
        for (Asset a : assets) if (match(a.name, exts)) out.add(a);
        final String[] abis = deviceAbis == null ? new String[0] : deviceAbis;
        Collections.sort(out, (a, b) -> {
            int sa = score(a, abis);
            int sb = score(b, abis);
            if (sa != sb) return sb - sa;
            int c = a.name.compareToIgnoreCase(b.name);
            if (c != 0) return c;
            return Long.compare(b.size, a.size);
        });
        return out;
    }

    private static int score(Asset a, String[] deviceAbis) {
        String ext = a.ext == null ? "" : a.ext;
        if (!ext.equals("apk") && !ext.equals("apks")) return 0;   // 非安装包不参与架构加权

        String abi = a.abi == null ? "" : a.abi;
        int base;
        if ("arm64-v8a".equals(abi)) base = 90;
        else if ("universal".equals(abi)) base = 80;
        else if ("armeabi-v7a".equals(abi)) base = 60;
        else if (abi.isEmpty()) base = 50;          // 没标架构，按通用处理
        else if ("x86_64".equals(abi)) base = 30;
        else if ("x86".equals(abi)) base = 20;
        else base = 40;

        int best = -1;
        for (int i = 0; i < deviceAbis.length && i < 4; i++) {
            if (compatible(deviceAbis[i], abi)) {
                best = i;
                break;
            }
        }
        if (best >= 0) {
            base += (4 - best) * 10;      // 本机首选架构再加权
        } else if (!abi.isEmpty() && !"universal".equals(abi) && deviceAbis.length > 0) {
            base -= 70;                    // 本机装不上的架构往后压
        }
        return base;
    }

    /** 设备架构能不能跑这个包 */
    private static boolean compatible(String deviceAbi, String abi) {
        if (abi == null || abi.isEmpty()) return false;
        String d = deviceAbi == null ? "" : deviceAbi.toLowerCase(Locale.US);
        if (d.equals("arm64-v8a")) {
            return abi.equals("arm64-v8a") || abi.equals("armeabi-v7a");
        }
        if (d.startsWith("armeabi")) return abi.equals("armeabi-v7a");
        if (d.equals("x86_64")) return abi.equals("x86_64") || abi.equals("x86");
        if (d.equals("x86")) return abi.equals("x86");
        return d.equals(abi);
    }

    // ------------------------------------------------------------ 网络

    /** 后台线程执行，只取第一页（100 个）：日常查更新够用，也不浪费接口额度 */
    public static void fetch(final String repo, final Callback cb) {
        new Thread(() -> {
            try {
                List<Release> list = request(repo, 1);
                post(() -> cb.onOk(list));
            } catch (ApiException e) {
                post(() -> cb.onError(e.getMessage()));
            } catch (Exception e) {
                post(() -> cb.onError("网络不可达，检查一下连接"));
            }
        }, "release-fetch").start();
    }

    /**
     * 后台线程翻完所有页，把整个仓库的版本都取回来。
     * 只在用户真的打开版本列表时调用——平时查更新只走 fetch()，省着点用 60 次/小时的额度。
     */
    public static void fetchAll(final String repo, final Callback cb) {
        new Thread(() -> {
            try {
                List<Release> all = new ArrayList<>();
                java.util.Set<String> seen = new java.util.HashSet<>();
                for (int page = 1; page <= MAX_PAGES; page++) {
                    List<Release> part = request(repo, page);
                    for (Release r : part) {
                        // 翻页期间有人发了新版，页边界会漂移，同一条可能出现两次
                        String k = r.tag == null ? "" : r.tag.toLowerCase(java.util.Locale.US);
                        if (!k.isEmpty() && !seen.add(k)) continue;
                        all.add(r);
                    }
                    if (part.size() < PER_PAGE) break;
                }
                // 每页内部有序，但跨页合并后必须整体再排一遍，
                // 否则新发的版本会挂在列表末尾。
                sortReleases(all);
                post(() -> cb.onOk(all));
            } catch (ApiException e) {
                post(() -> cb.onError(e.getMessage()));
            } catch (Exception e) {
                post(() -> cb.onError("网络不可达，检查一下连接"));
            }
        }, "release-fetch-all").start();
    }

    private static void post(Runnable r) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(r);
    }

    static class ApiException extends Exception {
        ApiException(String m) {
            super(m);
        }
    }

    private static List<Release> request(String repo, int page) throws Exception {
        String api = "https://api.github.com/repos/" + repo + "/releases?per_page="
                + PER_PAGE + "&page=" + page;
        HttpURLConnection c = (HttpURLConnection) new URL(api).openConnection();
        c.setConnectTimeout(12000);
        c.setReadTimeout(15000);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("User-Agent", "LightMirror/1.3 (Android)");
        int code = c.getResponseCode();
        if (code == 404) throw new ApiException("仓库不存在，或是一个私有仓库");
        if (code == 403 || code == 429) {
            throw new ApiException("GitHub 接口限流了（每小时 60 次），过一会儿再试");
        }
        if (code != 200) throw new ApiException("GitHub 返回 " + code + "，稍后再试");

        InputStream in = c.getInputStream();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();

        return parse(bos.toString("UTF-8"));
    }

    /** 把接口返回的 JSON 变成版本列表（抽出来是为了能脱网测解析与时间） */
    static List<Release> parse(String json) throws Exception {
        JSONArray arr = new JSONArray(json);
        List<Release> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            if (o.optBoolean("draft")) continue;
            Release r = new Release();
            r.tag = o.optString("tag_name");
            r.title = o.optString("name", r.tag);
            String pub = o.optString("published_at", "");
            r.date = pub.length() >= 10 ? pub.substring(0, 10) : "";
            r.published = epoch(pub);
            r.created = epoch(o.optString("created_at", ""));
            if (r.published == 0) r.published = r.created;
            r.prerelease = o.optBoolean("prerelease");
            JSONArray assets = o.optJSONArray("assets");
            if (assets != null) {
                for (int j = 0; j < assets.length(); j++) {
                    JSONObject a = assets.getJSONObject(j);
                    Asset as = new Asset();
                    as.name = a.optString("name");
                    as.url = a.optString("browser_download_url");
                    as.size = a.optLong("size");
                    as.ext = extOf(as.name);
                    as.abi = abiOf(as.name);
                    as.updated = epoch(a.optString("updated_at", ""));
                    if (as.updated == 0) as.updated = epoch(a.optString("created_at", ""));
                    if (as.name.isEmpty() || as.url.isEmpty()) continue;
                    r.assets.add(as);
                    // 标签右边显示的是「这个版本下所有文件里最新的那个时间」
                    if (as.updated > r.newest) r.newest = as.updated;
                }
            }
            if (r.newest == 0) r.newest = r.published;
            labelAll(r.assets);
            out.add(r);
        }
        // 接口默认按 created_at 排，但很多仓库（尤其批量发的）所有 release 的
        // created_at 完全一样，返回顺序毫无规律——必须自己按发布时间倒序。
        sortReleases(out);
        return out;
    }

    /** 新→旧：先比发布时间，撞了再比 tag 里的数字（r783 / r785），两端同一套规则 */
    static void sortReleases(List<Release> list) {
        Collections.sort(list, (a, b) -> {
            if (a.published != b.published) return Long.compare(b.published, a.published);
            return compareTagNums(b.tag, a.tag);
        });
    }

    /** 把 tag 里的数字段抠出来倒序比，比纯字符串靠谱（v10 不会再排在 v2 前面） */
    static int compareTagNums(String x, String y) {
        long[] a = nums(x);
        long[] b = nums(y);
        if (a == null || b == null) return 0;
        int n = Math.max(a.length, b.length);
        for (int i = 0; i < n; i++) {
            long va = i < a.length ? a[i] : Long.MIN_VALUE;   // 缺段视为更旧
            long vb = i < b.length ? b[i] : Long.MIN_VALUE;
            if (va != vb) return va > vb ? 1 : -1;
        }
        return 0;
    }

    private static long[] nums(String tag) {
        if (tag == null || tag.isEmpty()) return null;
        java.util.List<Long> out = new ArrayList<>();
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d+").matcher(tag);
            while (m.find()) out.add(Long.parseLong(m.group()));
        } catch (Exception e) {
            return null;
        }
        if (out.isEmpty()) return null;
        long[] r = new long[out.size()];
        for (int i = 0; i < r.length; i++) r[i] = out.get(i);
        return r;
    }

    /** 解析 GitHub 的 ISO8601 时间到毫秒，失败返回 0 */
    /** 北京时间（UTC+8），精确到分钟：同年省掉年份，跨年才写全 */
    public static String timeLabel(long ms) {
        if (ms <= 0) return "";
        SimpleDateFormat f = new SimpleDateFormat("MM-dd HH:mm", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        String txt = f.format(new java.util.Date(ms));
        SimpleDateFormat y = new SimpleDateFormat("yyyy", Locale.US);
        y.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        if (!y.format(new java.util.Date(ms)).equals(y.format(new java.util.Date()))) {
            txt = y.format(new java.util.Date(ms)) + "-" + txt;
        }
        return txt;
    }

    private static long epoch(String iso) {
        if (iso == null || iso.length() < 19) return 0;
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            return f.parse(iso.substring(0, 19)).getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    /** 从一段文本里认出 owner/repo */
    public static String parseRepo(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        s = s.replace("https://", "").replace("http://", "").replace("www.", "");
        if (s.startsWith("github.com/")) s = s.substring("github.com/".length());
        if (s.startsWith("api.github.com/repos/")) s = s.substring("api.github.com/repos/".length());
        // 去掉末尾的 .git、多余路径
        int q = s.indexOf('?');
        if (q > 0) s = s.substring(0, q);
        String[] seg = s.split("/");
        List<String> keep = new ArrayList<>();
        for (String p : seg) {
            if (p.isEmpty()) continue;
            keep.add(p);
            if (keep.size() == 2) break;
        }
        if (keep.size() < 2) return null;
        String owner = keep.get(0);
        String name = keep.get(1);
        if (name.endsWith(".git")) name = name.substring(0, name.length() - 4);
        if (!owner.matches("[A-Za-z0-9-_.]+") || !name.matches("[A-Za-z0-9-_.]+")) return null;
        return owner + "/" + name;
    }

    public static String sizeText(long bytes) {
        if (bytes <= 0) return "未知大小";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1048576) return String.format(Locale.US, "%.0f KB", bytes / 1024.0);
        if (bytes < 1073741824L) return String.format(Locale.US, "%.1f MB", bytes / 1048576.0);
        return String.format(Locale.US, "%.2f GB", bytes / 1073741824.0);
    }
}
