package com.wxalbum.nativeapp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 正文清洗：微信浮层文案、作者推广、PS 段落、重复标题、模板头图。 */
public final class Cleaner {

    private static final Set<String> UI_NOISE = new HashSet<>();
    private static final String[] UI_PREFIX = {
            "微信扫一扫", "长按识别", "扫描二维码", "打开微信", "前往公众号", "关注「公众号"
    };
    private static final String[] UI_SUBSTR = {
            "预览时标签不可点", "继续滑动看下一个", "轻触阅读原文", "微信扫一扫",
            "长按识别二维码", "扫码关注", "点击上方蓝字", "点击蓝字关注", "关注该公众号",
            "使用完整服务", "使用小程序", "喜欢此内容的人还喜欢", "往期推荐", "往期回顾",
            "点个在看", "分享给更多人", "打开完整版", "扫描二维码关注"
    };
    private static final String[] PROMO = {
            "三连支持", "求点赞", "求分享", "求推荐", "求订阅", "求月票", "求收藏", "点赞、分享",
            "书友群", "QQ群", "qq群", "微信群", "加更", "留言嗨", "有奖", "打赏", "赞赏",
            "免费发布", "全本免费", "首发于", "转载请注明", "公众号后台", "点击关注",
            "构思群", "讨论群", "同人群", "交流群", "跳转观看", "建议先补齐",
            "每个人都有自己的精彩", "人物志，每个人", "的同人文", "的同人续集"
    };

    private static final Pattern PS_RE =
            Pattern.compile("^\\s*[Pp]\\s*[.．]?\\s*[Ss]\\s*\\d?\\s*[:：]");
    private static final Pattern AUTHOR_RE =
            Pattern.compile("^\\s*作\\s*者\\s*[:：]\\s*\\S{1,24}\\s*$");
    private static final Pattern CREDIT_RE =
            Pattern.compile("^\\s*(编辑|责编|责任编辑|校对|审核|排版|美编|视觉|监制|撰文|供稿|来源|记者)"
                    + "\\s*[:：]?\\s*[\\u4e00-\\u9fa5A-Za-z0-9·]{1,14}\\s*$");
    public static final Pattern CHAP_ANY = Pattern.compile(
            "第\\s*[0-9零一二三四五六七八九十百千两]+\\s*[章回节篇卷]");

    static {
        String[] base = {
                "取消允许", "取消", "允许", "微信扫一扫", "微信扫一扫可打开此内容", "微信扫一扫可打开此内容，",
                "预览时标签不可点", "不喜欢", "关注该公众号", "知道了", "使用小程序", "分析",
                "使用完整服务", "修改于", "继续滑动看下一个", "轻触阅读原文", "写留言", "留言",
                "赞", "在看", "分享", "视频", "小程序", "广告", "跳过", "了解公众号", "打开完整版"
        };
        for (String s : base) UI_NOISE.add(s);
    }

    private Cleaner() {
    }

    public static String norm(String s) {
        if (s == null) return "";
        return s.replace('\u00a0', ' ').replaceAll("\\s+", "").trim();
    }

    public static boolean isUiNoise(String t) {
        String flat = norm(t);
        if (flat.isEmpty()) return true;
        if (UI_NOISE.contains(flat)) return true;
        if (flat.length() <= 30) {
            for (String p : UI_PREFIX) if (flat.startsWith(p)) return true;
        }
        return false;
    }

    public static boolean isPromo(String t) {
        String s = t.trim();
        if (s.isEmpty()) return true;
        if (PS_RE.matcher(s).find()) return true;
        if (AUTHOR_RE.matcher(s).find()) return true;
        if (CREDIT_RE.matcher(s).find()) return true;
        if (s.length() <= 120) {
            for (String k : UI_SUBSTR) if (s.contains(k)) return true;
        }
        if (s.length() <= 160) {
            for (String k : PROMO) if (s.contains(k)) return true;
        }
        return false;
    }

    /** 去掉明显的浮层文案与推广段；顺带合并连续空段。 */
    public static void strip(List<Model.Block> blocks) {
        List<Model.Block> out = new ArrayList<>(blocks.size());
        for (Model.Block b : blocks) {
            if (b.type == Model.Block.P) {
                String t = b.text.trim();
                if (t.isEmpty()) continue;
                if (isUiNoise(t) || isPromo(t)) continue;
                out.add(new Model.Block(Model.Block.P, t));
            } else {
                out.add(b);
            }
        }
        blocks.clear();
        blocks.addAll(out);
    }

    private static int firstPIndex(List<Model.Block> blocks) {
        for (int i = 0; i < blocks.size(); i++) {
            if (blocks.get(i).type == Model.Block.P && !blocks.get(i).text.trim().isEmpty()) return i;
        }
        return -1;
    }

    /**
     * 决定章节标题。返回 {标题, 需要从正文删掉的下标}，下标 -1 表示不删。
     */
    public static String[] pickTitle(String raw, List<Model.Block> blocks, String mode) {
        int idx = firstPIndex(blocks);
        String first = idx >= 0 ? blocks.get(idx).text.trim() : "";
        String title = "";
        int drop = -1;

        boolean wantFirst = "first".equals(mode) || "auto".equals(mode);
        if (wantFirst && !first.isEmpty() && first.length() <= 42 && CHAP_ANY.matcher(first).find()) {
            title = first;
            drop = idx;
        }
        if (title.isEmpty() && "first".equals(mode) && !first.isEmpty() && first.length() <= 60) {
            title = first;
            drop = idx;
        }
        if (title.isEmpty() && "auto".equals(mode) && looksSerial(raw)
                && !first.isEmpty() && first.length() <= 60) {
            title = first;
            drop = idx;
        }
        if (title.isEmpty()) title = raw == null ? "" : raw.trim();
        if (title.isEmpty()) title = "未命名";
        return new String[]{title, String.valueOf(drop)};
    }

    /** 文章标题只是「书名+流水号」（如 …这件事0024）时，标题改用正文首行。 */
    private static boolean looksSerial(String raw) {
        if (raw == null) return false;
        return raw.matches(".*\\d{3,}$");
    }

    public static String numbering(String title, int no, int total) {
        if (CHAP_ANY.matcher(title).find()) return title;
        if (title.matches("^\\d+[.、．].*")) return title;
        int width = Math.max(2, String.valueOf(total).length());
        return String.format(Locale.US, "%0" + width + "d. %s", no, title);
    }

    /** 同一张图出现在 30% 以上章节里 → 视为模板头图，剔除。 */
    public static int dropTemplateImages(List<Model.Chapter> chapters) {
        Map<String, Integer> freq = new HashMap<>();
        for (Model.Chapter c : chapters) {
            Set<String> seen = new HashSet<>();
            for (Model.Block b : c.blocks) {
                if (b.type == Model.Block.IMG && seen.add(b.text)) {
                    freq.put(b.text, freq.getOrDefault(b.text, 0) + 1);
                }
            }
        }
        if (chapters.size() < 3) return 0;
        Set<String> drop = new HashSet<>();
        for (Map.Entry<String, Integer> e : freq.entrySet()) {
            if (e.getValue() >= Math.max(3, (int) (chapters.size() * 0.3))) drop.add(e.getKey());
        }
        if (drop.isEmpty()) return 0;
        int n = 0;
        for (Model.Chapter c : chapters) {
            List<Model.Block> keep = new ArrayList<>();
            for (Model.Block b : c.blocks) {
                if (b.type == Model.Block.IMG && drop.contains(b.text)) {
                    n++;
                    continue;
                }
                keep.add(b);
            }
            c.blocks.clear();
            c.blocks.addAll(keep);
        }
        return n;
    }

    /** 首尾重复贴出的其他章节标题（公众号常见导航残留）。 */
    public static int dropCrossTitles(List<Model.Chapter> chapters) {
        Set<String> raws = new HashSet<>();
        for (Model.Chapter c : chapters) {
            String n = norm(c.raw);
            if (n.length() >= 6) raws.add(n);
        }
        if (raws.isEmpty()) return 0;
        int dropped = 0;
        for (Model.Chapter c : chapters) {
            for (int k = 0; k < 3; k++) {
                int last = c.blocks.size() - 1;
                if (last < 0 || c.blocks.get(last).type != Model.Block.P) break;
                String v = c.blocks.get(last).text;
                String nv = norm(v);
                if (v.length() > 80 || nv.length() < 6) break;
                if (!hit(nv, raws)) break;
                c.blocks.remove(last);
                dropped++;
            }
            for (int k = 0; k < 2; k++) {
                if (c.blocks.isEmpty() || c.blocks.get(0).type != Model.Block.P) break;
                String v = c.blocks.get(0).text;
                String nv = norm(v);
                if (v.length() > 80 || nv.length() < 6) break;
                if (!hit(nv, raws)) break;
                c.blocks.remove(0);
                dropped++;
            }
        }
        return dropped;
    }

    private static boolean hit(String nv, Set<String> raws) {
        if (raws.contains(nv)) return true;
        for (String r : raws) {
            if (nv.contains(r)) return true;
            if (nv.length() >= 8 && r.contains(nv)) return true;
        }
        return false;
    }

    /** 文末重复的书名 / 合集名签名。 */
    public static int dropTailSignature(List<Model.Chapter> chapters, String... names) {
        Set<String> ns = new HashSet<>();
        for (String n : names) {
            if (n == null) continue;
            String v = norm(n);
            if (v.length() >= 3) ns.add(v);
        }
        if (ns.isEmpty()) return 0;
        int n = 0;
        for (Model.Chapter c : chapters) {
            for (int k = 0; k < 2; k++) {
                int last = c.blocks.size() - 1;
                if (last < 0 || c.blocks.get(last).type != Model.Block.P) break;
                if (!ns.contains(norm(c.blocks.get(last).text))) break;
                c.blocks.remove(last);
                n++;
            }
        }
        return n;
    }

    public static int chars(List<Model.Block> blocks) {
        int n = 0;
        for (Model.Block b : blocks) if (b.type == Model.Block.P) n += b.text.length();
        return n;
    }

    public static String stripTags(String html) {
        if (html == null) return "";
        String s = html.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ");
        s = s.replaceAll("(?i)<br\\s*/?>", "\n");
        // 块级标签留一个换行，行内标签直接去掉（否则中文会被插入多余空格）
        s = s.replaceAll("(?i)</(p|div|section|li|h[1-6]|blockquote)>", "\n");
        s = s.replaceAll("(?s)<[^>]+>", "");
        s = unescape(s);
        s = s.replace('\u00a0', ' ');
        s = s.replaceAll("[ \\t\\x0B\\f\\r]+", " ");
        s = s.replaceAll(" *\\n *", "\n");
        s = s.replaceAll("\\n{2,}", "\n");
        return s.trim();
    }

    public static String unescape(String s) {
        if (s.indexOf('&') < 0) return s;
        String r = s;
        r = r.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&apos;", "'").replace("&mdash;", "—").replace("&ldquo;", "“")
                .replace("&rdquo;", "”").replace("&hellip;", "…").replace("&middot;", "·");
        Matcher m = Pattern.compile("&#(x?)([0-9a-fA-F]{2,6});").matcher(r);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            try {
                int cp = Integer.parseInt(m.group(2), m.group(1).isEmpty() ? 10 : 16);
                m.appendReplacement(sb, Matcher.quoteReplacement(new String(Character.toChars(cp))));
            } catch (Exception e) {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
