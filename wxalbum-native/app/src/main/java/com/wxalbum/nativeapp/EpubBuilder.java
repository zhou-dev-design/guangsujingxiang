package com.wxalbum.nativeapp;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** 手写 EPUB 3 打包：mimetype / container / opf / nav / ncx / 章节 / 图片。 */
public final class EpubBuilder {

    public static class Result {
        public File file;
        public int chapters;
        public int images;
        public int chars;
    }

    private EpubBuilder() {
    }

    public static Result build(Model.Album album, List<Model.Chapter> chapters,
                               boolean withImages, boolean bookinfo,
                               File out, WxApi.Log log) throws Exception {
        Result res = new Result();
        res.file = out;
        res.chapters = chapters.size();

        int totalChars = 0;
        for (Model.Chapter c : chapters) totalChars += Cleaner.chars(c.blocks);
        res.chars = totalChars;

        // ---- 图片 ----
        Map<String, byte[]> imgData = new LinkedHashMap<>();
        Map<String, String> imgName = new HashMap<>();
        Map<String, String> imgExt = new HashMap<>();
        if (withImages) {
            int n = 0;
            for (Model.Chapter c : chapters) {
                for (Model.Block b : c.blocks) {
                    if (b.type != Model.Block.IMG) continue;
                    if (imgData.containsKey(b.text)) continue;
                    if (n >= 300) break;
                    try {
                        WxApi.Raw raw = WxApi.raw(b.text, "https://mp.weixin.qq.com/");
                        if (raw.data.length < 2048) continue;
                        String ext = extOf(raw.type, b.text);
                        imgData.put(b.text, raw.data);
                        imgExt.put(b.text, ext);
                        imgName.put(b.text, String.format(Locale.US, "img%04d.%s", imgData.size(), ext));
                        n++;
                    } catch (Exception e) {
                        if (log != null) log.say("  插图跳过：" + e.getMessage());
                    }
                }
            }
            res.images = imgData.size();
        }

        // ---- 封面 ----
        byte[] cover = null;
        String coverExt = "jpg";
        if (album.cover != null && !album.cover.isEmpty()) {
            try {
                WxApi.Raw raw = WxApi.raw(album.cover, "https://mp.weixin.qq.com/");
                if (raw.data.length > 2048) {
                    cover = raw.data;
                    coverExt = extOf(raw.type, album.cover);
                }
            } catch (Exception ignored) {
            }
        }

        String title = album.title.isEmpty() ? "公众号合集" : album.title;
        String author = album.author == null ? "" : album.author;
        String bookId = album.albumId.isEmpty() ? "wxalbum-" + System.currentTimeMillis() : album.albumId;
        String now = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(new Date());

        Map<String, String> files = new LinkedHashMap<>();
        List<String> manifest = new ArrayList<>();
        List<String> spine = new ArrayList<>();
        StringBuilder nav = new StringBuilder();
        StringBuilder ncx = new StringBuilder();

        if (cover != null) {
            files.put("Text/cover.xhtml", xhtml("封面",
                    "<div class=\"cover-page\"><img src=\"../Images/cover." + coverExt
                            + "\" alt=\"封面\"/></div>", true));
            manifest.add("<item id=\"coverpage\" href=\"Text/cover.xhtml\" media-type=\"application/xhtml+xml\"/>");
            spine.add("<itemref idref=\"coverpage\"/>");
            manifest.add("<item id=\"cover-img\" href=\"Images/cover." + coverExt
                    + "\" media-type=\"" + mimeOf(coverExt) + "\" properties=\"cover-image\"/>");
            nav.append("<li><a href=\"Text/cover.xhtml\">封面</a></li>");
        }

        files.put("Text/titlepage.xhtml", xhtml("书名",
                "<div class=\"title-page\"><h1>" + esc(title) + "</h1>"
                        + (author.isEmpty() ? "" : "<p class=\"by\">" + esc(author) + "</p>") + "</div>", true));
        manifest.add("<item id=\"titlepage\" href=\"Text/titlepage.xhtml\" media-type=\"application/xhtml+xml\"/>");
        spine.add("<itemref idref=\"titlepage\"/>");
        nav.append("<li><a href=\"Text/titlepage.xhtml\">书名页</a></li>");

        int order = 0;
        if (bookinfo) {
            String first = pubOf(chapters, true);
            String last = pubOf(chapters, false);
            String charsText = totalChars >= 10000
                    ? String.format(Locale.US, "%.1f 万字", totalChars / 10000.0)
                    : totalChars + " 字";
            String link = "";
            if (!album.biz.isEmpty() && !album.albumId.isEmpty()) {
                link = "https://mp.weixin.qq.com/mp/appmsgalbum?__biz=" + album.biz
                        + "&action=getalbum&album_id=" + album.albumId;
            }
            StringBuilder body = new StringBuilder("<h1>书籍信息</h1>");
            body.append("<p class=\"no-indent\">书名：").append(esc(title)).append("</p>");
            body.append("<p class=\"no-indent\">作者：").append(esc(author.isEmpty() ? "—" : author)).append("</p>");
            body.append("<p class=\"no-indent\">字数：").append(esc(charsText)).append("</p>");
            body.append("<p class=\"no-indent\">章节数：").append(chapters.size()).append(" 章</p>");
            body.append("<p class=\"no-indent\">书籍 ID：").append(esc(album.albumId.isEmpty() ? "—" : album.albumId)).append("</p>");
            if (!link.isEmpty()) {
                body.append("<p class=\"no-indent\">合集链接：<a href=\"").append(esc(link))
                        .append("\">").append(esc(link)).append("</a></p>");
            }
            body.append("<p class=\"no-indent\">首次发布：").append(esc(first.isEmpty() ? "—" : first)).append("</p>");
            body.append("<p class=\"no-indent\">最近更新：").append(esc(last.isEmpty() ? "—" : last)).append("</p>");
            if (album.desc != null && !album.desc.trim().isEmpty()) {
                body.append("<p class=\"no-indent\">【简介】</p>");
                int n = 0;
                for (String line : album.desc.split("[\\r\\n]+")) {
                    String t = line.trim();
                    if (t.isEmpty()) continue;
                    body.append("<p class=\"desc\">").append(esc(t)).append("</p>");
                    if (++n >= 12) break;
                }
            }
            files.put("Text/bookinfo.xhtml", xhtml("书籍信息", "<div class=\"info-page\">" + body + "</div>", true));
            manifest.add("<item id=\"bookinfo\" href=\"Text/bookinfo.xhtml\" media-type=\"application/xhtml+xml\"/>");
            spine.add("<itemref idref=\"bookinfo\"/>");
            nav.append("<li><a href=\"Text/bookinfo.xhtml\">书籍信息</a></li>");
            ncx.append("<navPoint id=\"npinfo\" playOrder=\"1\"><navLabel><text>书籍信息</text></navLabel>")
                    .append("<content src=\"Text/bookinfo.xhtml\"/></navPoint>");
            order = 1;
        }

        for (int i = 0; i < chapters.size(); i++) {
            Model.Chapter c = chapters.get(i);
            String name = String.format(Locale.US, "chap%04d.xhtml", i + 1);
            StringBuilder body = new StringBuilder();
            body.append("<h1>").append(esc(c.title)).append("</h1>\n");
            for (Model.Block b : c.blocks) {
                if (b.type == Model.Block.IMG) {
                    String local = imgName.get(b.text);
                    if (local != null) {
                        body.append("<div class=\"fig\"><img src=\"../Images/").append(local)
                                .append("\" alt=\"\"/></div>\n");
                    }
                } else {
                    body.append("<p>").append(esc(b.text)).append("</p>\n");
                }
            }
            files.put("Text/" + name, xhtml(c.title, body.toString(), false));
            manifest.add("<item id=\"c" + (i + 1) + "\" href=\"Text/" + name
                    + "\" media-type=\"application/xhtml+xml\"/>");
            spine.add("<itemref idref=\"c" + (i + 1) + "\"/>");
            nav.append("<li><a href=\"Text/").append(name).append("\">")
                    .append(esc(c.title)).append("</a></li>");
            ncx.append("<navPoint id=\"np").append(i + 1).append("\" playOrder=\"")
                    .append(order + i + 1).append("\"><navLabel><text>").append(esc(c.title))
                    .append("</text></navLabel><content src=\"Text/").append(name)
                    .append("\"/></navPoint>");
        }

        for (Map.Entry<String, byte[]> e : imgData.entrySet()) {
            String local = imgName.get(e.getKey());
            String ext = imgExt.get(e.getKey());
            manifest.add("<item id=\"i" + local.hashCode() + "\" href=\"Images/" + local
                    + "\" media-type=\"" + mimeOf(ext) + "\"/>");
        }

        manifest.add("<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>");
        manifest.add("<item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>");
        manifest.add("<item id=\"css\" href=\"Styles/style.css\" media-type=\"text/css\"/>");
        files.put("Styles/style.css", CSS);
        files.put("nav.xhtml", NAV_HEAD + nav + NAV_TAIL);
        files.put("toc.ncx", NCX_HEAD + ncx + NCX_TAIL);
        files.put("content.opf", opf(title, author, bookId, now, album, manifest, spine));

        // ---- 写盘 ----
        File parent = out.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (ZipOutputStream z = new ZipOutputStream(
                new BufferedOutputStream(new FileOutputStream(out)))) {
            byte[] mt = "application/epub+zip".getBytes("UTF-8");
            ZipEntry ze = new ZipEntry("mimetype");
            ze.setMethod(ZipEntry.STORED);
            ze.setSize(mt.length);
            ze.setCompressedSize(mt.length);
            CRC32 crc = new CRC32();
            crc.update(mt);
            ze.setCrc(crc.getValue());
            z.putNextEntry(ze);
            z.write(mt);
            z.closeEntry();

            put(z, "META-INF/container.xml", CONTAINER.getBytes("UTF-8"));
            for (Map.Entry<String, String> e : files.entrySet()) {
                put(z, "OEBPS/" + e.getKey(), e.getValue().getBytes("UTF-8"));
            }
            for (Map.Entry<String, byte[]> e : imgData.entrySet()) {
                put(z, "OEBPS/Images/" + imgName.get(e.getKey()), e.getValue());
            }
            if (cover != null) put(z, "OEBPS/Images/cover." + coverExt, cover);
        }
        return res;
    }

    private static void put(ZipOutputStream z, String name, byte[] data) throws IOException {
        z.putNextEntry(new ZipEntry(name));
        z.write(data);
        z.closeEntry();
    }

    private static String pubOf(List<Model.Chapter> chapters, boolean min) {
        String best = "";
        for (Model.Chapter c : chapters) {
            if (c.ctime <= 0) continue;
            String s = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
                    .format(new Date(c.ctime * 1000L));
            if (best.isEmpty() || (min ? s.compareTo(best) < 0 : s.compareTo(best) > 0)) best = s;
        }
        return best;
    }

    private static String xhtml(String title, String body, boolean fullPage) {
        String cls = fullPage ? "" : "";
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<!DOCTYPE html>\n"
                + "<html xmlns=\"http://www.w3.org/1999/xhtml\" xml:lang=\"zh-CN\" lang=\"zh-CN\">\n"
                + "<head><meta charset=\"utf-8\"/><title>" + esc(title) + "</title>\n"
                + "<link rel=\"stylesheet\" type=\"text/css\" href=\"../Styles/style.css\"/></head>\n"
                + "<body" + cls + ">\n" + body + "\n</body></html>";
    }

    private static String opf(String title, String author, String bookId, String now,
                              Model.Album album, List<String> manifest, List<String> spine) {
        StringBuilder m = new StringBuilder();
        for (String s : manifest) m.append(s).append('\n');
        StringBuilder sp = new StringBuilder();
        for (String s : spine) sp.append(s).append('\n');
        String desc = album.desc == null ? "" : album.desc.replace('\n', ' ');
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\" "
                + "unique-identifier=\"bookid\" xml:lang=\"zh-CN\">\n"
                + "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">\n"
                + "<dc:identifier id=\"bookid\">" + esc(bookId) + "</dc:identifier>\n"
                + "<dc:title>" + esc(title) + "</dc:title>\n"
                + "<dc:creator>" + esc(author) + "</dc:creator>\n"
                + "<dc:publisher>" + esc(author) + "</dc:publisher>\n"
                + "<dc:language>zh-CN</dc:language>\n"
                + "<dc:date>" + now.substring(0, 10) + "</dc:date>\n"
                + "<dc:description>" + esc(desc) + "</dc:description>\n"
                + "<dc:source>" + esc(album.url) + "</dc:source>\n"
                + "<meta property=\"dcterms:modified\">" + now + "</meta>\n"
                + "</metadata>\n<manifest>\n" + m + "</manifest>\n"
                + "<spine toc=\"ncx\">\n" + sp + "</spine>\n</package>";
    }

    private static String mimeOf(String ext) {
        switch (ext) {
            case "png":
                return "image/png";
            case "gif":
                return "image/gif";
            case "webp":
                return "image/webp";
            case "svg":
                return "image/svg+xml";
            default:
                return "image/jpeg";
        }
    }

    private static String extOf(String type, String url) {
        String t = type == null ? "" : type.toLowerCase(Locale.US);
        if (t.contains("png")) return "png";
        if (t.contains("gif")) return "gif";
        if (t.contains("webp")) return "webp";
        if (t.contains("jpeg") || t.contains("jpg")) return "jpg";
        String u = url.toLowerCase(Locale.US);
        if (u.contains("wx_fmt=png")) return "png";
        if (u.contains("wx_fmt=gif")) return "gif";
        if (u.contains("wx_fmt=webp")) return "webp";
        return "jpg";
    }

    public static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                case '\'': sb.append("&apos;"); break;
                default:
                    if (c < 0x20 && c != '\n' && c != '\t') sb.append(' ');
                    else sb.append(c);
            }
        }
        return sb.toString();
    }

    private static final String CONTAINER = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
            + "<container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">\n"
            + "<rootfiles><rootfile full-path=\"OEBPS/content.opf\" "
            + "media-type=\"application/oebps-package+xml\"/></rootfiles></container>";

    private static final String NAV_HEAD = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
            + "<!DOCTYPE html>\n<html xmlns=\"http://www.w3.org/1999/xhtml\" "
            + "xmlns:epub=\"http://www.idpf.org/2007/ops\" xml:lang=\"zh-CN\" lang=\"zh-CN\">\n"
            + "<head><meta charset=\"utf-8\"/><title>目录</title>\n"
            + "<link rel=\"stylesheet\" type=\"text/css\" href=\"Styles/style.css\"/></head>\n"
            + "<body><nav epub:type=\"toc\" id=\"toc\"><h1>目录</h1><ol>\n";
    private static final String NAV_TAIL = "</ol></nav></body></html>";

    private static final String NCX_HEAD = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
            + "<ncx xmlns=\"http://www.daisy.org/z3986/2005/ncx/\" version=\"2005-1\" xml:lang=\"zh-CN\">\n"
            + "<head><meta name=\"dtb:uid\" content=\"wxalbum\"/></head>\n"
            + "<docTitle><text>目录</text></docTitle><navMap>\n";
    private static final String NCX_TAIL = "</navMap></ncx>";

    private static final String CSS = "html,body{margin:0;padding:0}\n"
            + "body{font-family:serif;line-height:1.85;color:#1a1a1a;padding:1.2em 1.4em}\n"
            + "h1{font-size:1.24em;line-height:1.5;margin:0 0 1em;font-weight:600}\n"
            + "p{text-indent:2em;margin:0.55em 0;font-size:1em}\n"
            + ".fig{text-align:center;margin:1em 0}\n.fig img{max-width:100%;height:auto}\n"
            + ".cover-page{margin:0;padding:0;text-align:center}\n"
            + ".cover-page img{max-width:100%;max-height:100%}\n"
            + ".title-page{padding-top:22%;text-align:center}\n"
            + ".title-page h1{font-size:1.7em;margin:0 0 0.8em}\n"
            + ".title-page .by{text-indent:0;color:#666;font-size:1.02em}\n"
            + ".info-page{padding-top:1.2em}\n"
            + ".info-page h1{font-size:1.32em;text-align:left;margin:0 0 1em}\n"
            + ".no-indent{text-indent:0;text-align:left;margin:0.42em 0;line-height:1.95;word-break:break-all}\n"
            + ".desc{text-indent:2em;margin:0.3em 0;line-height:1.9;color:#333}\n"
            + ".info-page a{word-break:break-all;font-size:0.85em;color:#0b6a63}\n"
            + "nav ol{list-style:none;padding-left:0.4em}\n"
            + "nav li{margin:0.42em 0}\n";
}
