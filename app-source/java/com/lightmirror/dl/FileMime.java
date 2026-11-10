package com.lightmirror.dl;

import android.webkit.MimeTypeMap;

import java.util.Locale;

/** 按扩展名给出 MIME，APK 用安装包类型，未知类型退回 octet-stream。 */
public final class FileMime {

    private FileMime() {
    }

    public static String of(String fileName) {
        String n = fileName == null ? "" : fileName.toLowerCase(Locale.US);
        if (n.endsWith(".apk")) return "application/vnd.android.package-archive";
        if (n.endsWith(".apks") || n.endsWith(".xapk") || n.endsWith(".apkm")) return "application/zip";
        if (n.endsWith(".zip")) return "application/zip";
        if (n.endsWith(".7z")) return "application/x-7z-compressed";
        if (n.endsWith(".rar")) return "application/vnd.rar";
        if (n.endsWith(".tar.gz") || n.endsWith(".tgz")) return "application/gzip";
        if (n.endsWith(".gz")) return "application/gzip";
        if (n.endsWith(".exe") || n.endsWith(".msi")) return "application/octet-stream";
        if (n.endsWith(".dmg")) return "application/octet-stream";
        if (n.endsWith(".iso") || n.endsWith(".img")) return "application/octet-stream";
        if (n.endsWith(".deb") || n.endsWith(".rpm")) return "application/octet-stream";

        int dot = n.lastIndexOf('.');
        if (dot > 0 && dot < n.length() - 1) {
            String ext = n.substring(dot + 1);
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (mime != null) return mime;
        }
        return "application/octet-stream";
    }

    public static String label(String fileName) {
        String n = fileName == null ? "" : fileName.toLowerCase(Locale.US);
        if (n.endsWith(".apk")) return "可安装";
        if (n.endsWith(".zip") || n.endsWith(".7z") || n.endsWith(".rar") || n.endsWith(".gz")) return "压缩包";
        if (n.endsWith(".mp4") || n.endsWith(".mkv")) return "视频";
        if (n.endsWith(".pdf")) return "PDF";
        return "已保存";
    }
}
