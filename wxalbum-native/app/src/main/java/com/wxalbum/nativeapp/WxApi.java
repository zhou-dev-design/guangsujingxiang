package com.wxalbum.nativeapp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/** 微信页面抓取与解析（纯 Java，无第三方库）。 */
public final class WxApi {

    public static final String ALBUM_API = "https://mp.weixin.qq.com/mp/appmsgalbum";
    private static final String UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Version/4.0 Chrome/116.0.0.0 Mobile Safari/537.36 "
                    + "MicroMessenger/8.0.47.2560(0x28002F30) WeChat/arm64";

    public interface Log {
        void say(String msg);
    }

    public static class Raw {
        public final byte[] data;
        public final String type;

        Raw(byte[] d, String t) {
            data = d;
            type = t == null ? "" : t;
        }
    }

    private WxApi() {
    }

    /* ---------------- HTTP ---------------- */

    public static String get(String url) throws IOException {
        return get(url, null);
    }

    public static String get(String url, String referer) throws IOException {
        return new String(raw(url, referer).data, "UTF-8");
    }

    public static Raw raw(String url, String referer) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return once(url, referer);
            } catch (IOException e) {
                last = e;
                try {
                    Thread.sleep(500L * (attempt + 1));
                } catch (InterruptedException ignored) {
                }
            }
        }
        throw last == null ? new IOException("请求失败") : last;
    }

    private static Raw once(String url, String referer) throws IOException {
        String cur = url;
        for (int hop = 0; hop < 6; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(cur).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept", "text/html,application/json,*/*");
            c.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
            c.setRequestProperty("Accept-Encoding", "gzip");
            if (referer != null && !referer.isEmpty()) c.setRequestProperty("Referer", referer);

            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null || loc.isEmpty()) throw new IOException("重定向缺少地址");
                cur = loc.startsWith("http") ? loc : new URL(new URL(cur), loc).toString();
                continue;
            }
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in == null) throw new IOException("HTTP " + code);
            if ("gzip".equalsIgnoreCase(c.getContentEncoding())) in = new GZIPInputStream(in);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            c.disconnect();
            if (code >= 400) throw new IOException("HTTP " + code);
            return new Raw(bos.toByteArray(), c.getContentType());
        }
        throw new IOException("重定向次数过多");
    }

    /* ---------------- 合集 ---------------- */

    public static String param(String url, String key) {
        try {
            Matcher m = Pattern.compile("[?&]" + Pattern.quote(key) + "=([^&#]*)").matcher(url);
            if (m.find()) return URLDecoder.decode(m.group(1), "UTF-8");
        } catch (Exception ignored) {
        }
        return "";
    }

    public static Model.Album parseAlbumPage(String html, String url) {
        Model.Album a = new Model.Album();
        a.url = url;
        a.biz = param(url, "__biz");
        a.albumId = param(url, "album_id");

        Matcher m = Pattern.compile("class=\"album__novel_label-title[^\"]*\"[^>]*>\\s*(.{1,120}?)<")
                .matcher(html);
        if (m.find()) a.title = Cleaner.stripTags(m.group(1));
        if (a.title.isEmpty()) {
            Matcher o = Pattern.compile("<meta[^>]+property=\"og:title\"[^>]+content=\"([^\"]+)\"")
                    .matcher(html);
            if (o.find()) a.title = Cleaner.unescape(o.group(1)).trim();
        }
        if (a.title.isEmpty()) {
            Matcher o = Pattern.compile("<title>([^<]{1,120})</title>").matcher(html);
            if (o.find()) a.title = Cleaner.unescape(o.group(1)).trim();
        }

        m = Pattern.compile("class=\"[^\"]*album__novel_authorname[^\"]*\"[^>]*>\\s*([^<]{1,60})<")
                .matcher(html);
        if (m.find()) a.author = Cleaner.unescape(m.group(1)).trim();
        if (a.author.isEmpty()) {
            m = Pattern.compile("id=\"js_name\"[^>]*>\\s*([^<]{1,60})<").matcher(html);
            if (m.find()) a.author = Cleaner.unescape(m.group(1)).trim();
        }

        m = Pattern.compile("id=\"js_header_novel_cover\"[^>]*src=\"([^\"]+)\"").matcher(html);
        if (m.find()) a.cover = Cleaner.unescape(m.group(1));

        m = Pattern.compile("class=\"[^\"]*js_album_desc_content[^\"]*\"[^>]*>\\s*([^<]{1,600})<")
                .matcher(html);
        if (m.find()) {
            Set<String> seen = new HashSet<>();
            StringBuilder sb = new StringBuilder();
            for (String seg : Cleaner.unescape(m.group(1)).split("[\\r\\n]+")) {
                String t = seg.trim();
                if (t.isEmpty() || !seen.add(t)) continue;
                if (sb.length() > 0) sb.append('\n');
                sb.append(t);
            }
            a.desc = sb.toString();
        }

        m = Pattern.compile("(\\d+)\\s*篇内容").matcher(html);
        if (m.find()) {
            try {
                a.count = Integer.parseInt(m.group(1));
            } catch (Exception ignored) {
            }
        }
        return a;
    }

    public static class AlbumResult {
        public Model.Album album;
        public List<Model.Article> articles = new ArrayList<>();
    }

    /** 先抓合集页拿信息，再走 getalbum 接口翻页拿完整目录（接口才有发布时间）。 */
    public static AlbumResult fetchAlbum(String biz, String albumId, Log log) throws Exception {
        String pageUrl = ALBUM_API + "?__biz=" + URLEncoder.encode(biz, "UTF-8")
                + "&action=getalbum&album_id=" + albumId + "&scene=126&sessionid=";
        String html = get(pageUrl);
        Model.Album album = parseAlbumPage(html, pageUrl);
        album.biz = biz;
        album.albumId = albumId;
        if (album.title.isEmpty()) album.title = "未命名合集";

        List<Model.Article> list = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String lastMsgid = "";
        String lastIdx = "";
        for (int page = 0; page < 200; page++) {
            String api = ALBUM_API + "?__biz=" + URLEncoder.encode(biz, "UTF-8")
                    + "&action=getalbum&album_id=" + albumId
                    + "&count=10&f=json&scene=126&sessionid=&subscene=";
            if (!lastMsgid.isEmpty()) {
                api += "&begin_msgid=" + lastMsgid + "&begin_itemidx=" + lastIdx;
            }
            JSONObject resp = new JSONObject(get(api)).optJSONObject("getalbum_resp");
            if (resp == null) break;
            JSONArray arr = resp.optJSONArray("article_list");
            int added = 0;
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    String mid = o.optString("msgid", "");
                    String idx = o.optString("itemidx", "1");
                    String key = mid + "_" + idx;
                    if (mid.isEmpty() || !seen.add(key)) continue;
                    Model.Article a = new Model.Article();
                    a.msgid = mid;
                    a.itemidx = idx;
                    a.title = Cleaner.unescape(o.optString("title", "")).trim();
                    a.url = o.optString("url", "").replace("http://", "https://");
                    int hash = a.url.indexOf('#');
                    if (hash > 0) a.url = a.url.substring(0, hash);
                    try {
                        a.pos = Integer.parseInt(o.optString("pos_num", "0"));
                    } catch (Exception ignored) {
                    }
                    a.ctime = o.optLong("create_time", 0L);
                    list.add(a);
                    added++;
                    lastMsgid = mid;
                    lastIdx = idx;
                }
            }
            if (log != null && added > 0) log.say("目录第 " + (page + 1) + " 页 +" + added + "，共 " + list.size() + " 篇");
            if (added == 0) break;
            if (!"1".equals(resp.optString("continue_flag", ""))) break;
            if (list.size() > 2000) break;
            Thread.sleep(350);
        }
        album.count = list.size();
        // 按合集内序号排序；没有序号时保持接口顺序
        list.sort((x, y) -> {
            if (x.pos != y.pos) return Integer.compare(x.pos, y.pos);
            return Long.compare(x.ctime, y.ctime);
        });
        album.url = pageUrl;
        AlbumResult r = new AlbumResult();
        r.album = album;
        r.articles = list;
        return r;
    }

    /* ---------------- 文章 ---------------- */

    public static Model.Chapter parseArticle(String html) {
        Model.Chapter ch = new Model.Chapter();
        Matcher m = Pattern.compile("id=\"activity-name\"[^>]*>(.{1,200}?)</h1>", Pattern.DOTALL)
                .matcher(html);
        if (m.find()) ch.title = Cleaner.stripTags(m.group(1));
        if (ch.title.isEmpty()) {
            m = Pattern.compile("<meta[^>]+property=\"og:title\"[^>]+content=\"([^\"]+)\"").matcher(html);
            if (m.find()) ch.title = Cleaner.unescape(m.group(1)).trim();
        }
        m = Pattern.compile("id=\"js_name\"[^>]*>(.{1,80}?)<").matcher(html);
        if (m.find()) ch.account = Cleaner.stripTags(m.group(1));
        m = Pattern.compile("var\\s+ct\\s*=\\s*\"(\\d{9,11})\"").matcher(html);
        if (m.find()) {
            try {
                ch.ctime = Long.parseLong(m.group(1));
            } catch (Exception ignored) {
            }
        }
        ch.raw = ch.title;

        String seg = contentRegion(html);
        if (seg == null) return ch;

        Pattern tag = Pattern.compile("<(p|img)\\b([^>]*)>", Pattern.CASE_INSENSITIVE);
        Matcher tm = tag.matcher(seg);
        while (tm.find()) {
            String name = tm.group(1).toLowerCase();
            if ("img".equals(name)) {
                Matcher src = Pattern.compile("(?:data-src|src)\\s*=\\s*\"([^\"]+)\"").matcher(tm.group(2));
                if (src.find()) {
                    String u = Cleaner.unescape(src.group(1));
                    if (u.startsWith("//")) u = "https:" + u;
                    if (u.startsWith("http")) ch.blocks.add(new Model.Block(Model.Block.IMG, u));
                }
                continue;
            }
            int close = seg.indexOf("</p>", tm.end());
            if (close < 0) break;
            String text = Cleaner.stripTags(seg.substring(tm.end(), close));
            if (!text.isEmpty()) ch.blocks.add(new Model.Block(Model.Block.P, text));
            tm.region(close + 4, seg.length());
        }

        int textChars = Cleaner.chars(ch.blocks);
        if (textChars < 120) {
            // 有些排版不用 <p>，退化为整段文本 + <br> 分行
            ch.blocks.clear();
            String plain = Cleaner.stripTags(seg);
            for (String line : plain.split("\\n+")) {
                String t = line.trim();
                if (t.length() > 0) ch.blocks.add(new Model.Block(Model.Block.P, t));
            }
        }
        return ch;
    }

    private static String contentRegion(String html) {
        int ci = html.indexOf("id=\"js_content\"");
        if (ci < 0) return null;
        int start = html.indexOf('>', ci);
        if (start < 0) return null;
        start += 1;
        int depth = 1;
        int i = start;
        int end = -1;
        while (i < html.length() && depth > 0) {
            int open = html.indexOf("<div", i);
            int close = html.indexOf("</div", i);
            if (close < 0) break;
            if (open >= 0 && open < close) {
                depth++;
                i = open + 4;
            } else {
                depth--;
                i = close + 6;
                if (depth == 0) end = close;
            }
        }
        if (end < 0) end = Math.min(html.length(), start + 400000);
        return html.substring(start, end);
    }

    /* ---------------- 反查合集 ---------------- */

    /** 从文章 HTML 里找出所属合集；返回 {biz, albumId}，找不到返回 null。 */
    public static String[] sniffAlbum(String html, String pageUrl) {
        String text = html.replace("\\x26amp;", "&").replace("&amp;", "&");
        String mid = pageUrl == null ? "" : param(pageUrl, "mid");
        if (mid.isEmpty()) {
            Matcher m = Pattern.compile("var\\s+mid\\s*=\\s*[\"']?(\\d{6,})").matcher(text);
            if (m.find()) mid = m.group(1);
        }
        Map<String, int[]> slot = new HashMap<>();
        Map<String, String> bizOf = new HashMap<>();
        Matcher m = Pattern.compile("album_id[\"']?\\s*[:=]\\s*[\"']?(\\d{8,})").matcher(text);
        while (m.find()) {
            String aid = m.group(1);
            int from = Math.max(0, m.start() - 500);
            int to = Math.min(text.length(), m.end() + 500);
            String seg = text.substring(from, to);
            int[] v = slot.computeIfAbsent(aid, k -> new int[]{0, 0});
            v[0]++;
            if (!mid.isEmpty()) {
                Matcher f = Pattern.compile("from_msgid[\"']?\\s*[:=]\\s*[\"']?(\\d{6,})").matcher(seg);
                while (f.find()) {
                    if (f.group(1).equals(mid)) v[1] = 1;
                }
            }
            Matcher b = Pattern.compile("__biz[\"']?\\s*[:=]\\s*[\"']?([A-Za-z0-9+/=]{8,})").matcher(seg);
            if (b.find()) bizOf.put(aid, b.group(1));
        }
        if (slot.isEmpty()) return null;
        String chosen = null;
        for (Map.Entry<String, int[]> e : slot.entrySet()) {
            if (e.getValue()[1] == 1) {
                chosen = e.getKey();
                break;
            }
        }
        if (chosen == null) {
            int best = -1;
            for (Map.Entry<String, int[]> e : slot.entrySet()) {
                if (e.getValue()[0] > best) {
                    best = e.getValue()[0];
                    chosen = e.getKey();
                }
            }
        }
        String biz = bizOf.get(chosen);
        if (biz == null) {
            Matcher b = Pattern.compile("__biz[\"']?\\s*[:=]\\s*[\"']?([A-Za-z0-9+/=]{8,})").matcher(text);
            if (b.find()) biz = b.group(1);
        }
        if (biz == null) return null;
        return new String[]{biz, chosen};
    }
}
