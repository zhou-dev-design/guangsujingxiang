package com.wxalbum.nativeapp;

import java.util.ArrayList;
import java.util.List;

/** 数据模型：合集、文章、正文块、章节。 */
public final class Model {

    public static class Album {
        public String title = "";
        public String author = "";
        public String desc = "";
        public String cover = "";
        public String albumId = "";
        public String biz = "";
        public String url = "";
        public int count;
    }

    public static class Article {
        public String msgid = "";
        public String itemidx = "1";
        public String title = "";
        public String url = "";
        public int pos;
        public long ctime;
        // 运行期状态
        public String status = "pending"; // pending / fetching / done / fail
        public int chars;
        public boolean current;

        public String key() {
            return msgid + "_" + itemidx;
        }
    }

    public static class Block {
        public static final int P = 0;
        public static final int IMG = 1;
        public final int type;
        public final String text;

        public Block(int type, String text) {
            this.type = type;
            this.text = text == null ? "" : text;
        }
    }

    public static class Chapter {
        public String title = "";
        public String raw = "";
        public String account = "";
        public long ctime;
        public final List<Block> blocks = new ArrayList<>();
    }

    public static String fmtChars(int n) {
        if (n <= 0) return "—";
        if (n >= 10000) return String.format(java.util.Locale.US, "%.1f 万字", n / 10000.0);
        return n + " 字";
    }

    public static String fmtSize(long n) {
        if (n <= 0) return "—";
        if (n < 1024) return n + " B";
        if (n < 1024 * 1024) return (n / 1024) + " KB";
        return String.format(java.util.Locale.US, "%.2f MB", n / 1024.0 / 1024.0);
    }

    private Model() {
    }
}
