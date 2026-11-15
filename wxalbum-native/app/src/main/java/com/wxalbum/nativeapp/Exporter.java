package com.wxalbum.nativeapp;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.DocumentsContract;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/** 把生成好的 EPUB 导出到用户选定的目录或系统下载目录。 */
public final class Exporter {

    public static class Result {
        public String label = "";
        public Uri uri;
    }

    private Exporter() {
    }

    public static Result export(Context c, File src, String treeUri) throws Exception {
        if (src == null || !src.exists()) throw new Exception("文件不存在");
        if (treeUri != null && !treeUri.isEmpty()) {
            Result r = toTree(c, src, Uri.parse(treeUri));
            if (r != null) return r;
        }
        return toDownloads(c, src);
    }

    /** SAF 目录写入：同名文件会被覆盖。 */
    private static Result toTree(Context c, File src, Uri tree) throws Exception {
        ContentResolver cr = c.getContentResolver();
        Uri dir = DocumentsContract.buildDocumentUriUsingTree(tree,
                DocumentsContract.getTreeDocumentId(tree));
        Uri target = null;
        try {
            target = DocumentsContract.createDocument(cr, dir, "application/epub+zip", src.getName());
        } catch (Exception ignored) {
        }
        if (target == null) {
            // 已存在同名文件：找到它并覆盖
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree,
                    DocumentsContract.getTreeDocumentId(tree));
            android.database.Cursor cur = cr.query(children,
                    new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null);
            try {
                if (cur != null) {
                    while (cur.moveToNext()) {
                        if (src.getName().equals(cur.getString(1))) {
                            target = DocumentsContract.buildDocumentUriUsingTree(tree, cur.getString(0));
                            break;
                        }
                    }
                }
            } finally {
                if (cur != null) cur.close();
            }
        }
        if (target == null) return null;
        try (OutputStream os = cr.openOutputStream(target, "wt");
             InputStream is = new FileInputStream(src)) {
            if (os == null) return null;
            copy(is, os);
        }
        Result r = new Result();
        r.uri = target;
        r.label = dirLabel(c, tree) + "/" + src.getName();
        return r;
    }

    private static String dirLabel(Context c, Uri tree) {
        String s = tree.toString();
        int i = s.indexOf("tree/");
        if (i > 0) {
            String id = Uri.decode(s.substring(i + 5));
            int colon = id.indexOf(':');
            if (colon >= 0) id = id.substring(colon + 1);
            if (!id.isEmpty()) return id;
        }
        return "所选目录";
    }

    /** 系统「下载」目录（Q+ 走 MediaStore，旧版直接写公共目录）。 */
    private static Result toDownloads(Context c, File src) throws Exception {
        Result r = new Result();
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, src.getName());
            v.put(MediaStore.MediaColumns.MIME_TYPE, "application/epub+zip");
            v.put(MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/公众号EPUB");
            Uri uri = c.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new Exception("无法写入下载目录");
            try (OutputStream os = c.getContentResolver().openOutputStream(uri);
                 InputStream is = new FileInputStream(src)) {
                if (os == null) throw new Exception("无法写入下载目录");
                copy(is, os);
            }
            r.uri = uri;
            r.label = "下载/公众号EPUB/" + src.getName();
            return r;
        }
        File dir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), "公众号EPUB");
        if (!dir.exists() && !dir.mkdirs()) throw new Exception("无法创建下载目录");
        File dst = new File(dir, src.getName());
        try (OutputStream os = new FileOutputStream(dst);
             InputStream is = new FileInputStream(src)) {
            copy(is, os);
        }
        r.label = dst.getAbsolutePath();
        return r;
    }

    public static void copy(InputStream is, OutputStream os) throws Exception {
        byte[] buf = new byte[16384];
        int n;
        while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
        os.flush();
    }
}
