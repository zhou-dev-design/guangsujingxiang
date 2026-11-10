package com.lightmirror.dl;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.util.Base64;

import java.io.File;
import java.io.FileNotFoundException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 极简文件提供者：把下载下来的文件以 content:// 形式交给安装器或其他应用读取。
 * 只读、不导出、路径做了越级校验。
 *
 * <p>URI 里带的是<b>完整路径</b>（base64url），不再只放文件名。
 * 以前只放文件名、再固定到私有下载目录去拼，结果文件存在「系统下载 / 自定义路径」时
 * 根本找不到 → 安装器拿到的是空流 → 报「解析程序包时出现问题」；
 * 同名的旧文件还会被误当成新包交给安装器。现在按真实路径取，两个问题一起没了。
 */
public class FileBridge extends ContentProvider {

    public static final String AUTHORITY = "com.lightmirror.dl.files";

    private static final String SEG_TAG = "dl";

    public static Uri uriFor(File file) {
        String path = file == null ? "" : file.getAbsolutePath();
        String token = Base64.encodeToString(path.getBytes(StandardCharsets.UTF_8),
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        return new Uri.Builder()
                .scheme("content")
                .authority(AUTHORITY)
                .appendPath(SEG_TAG)
                .appendPath(token)
                .build();
    }

    /** 允许对外提供的根目录：所有存储卷、应用外部目录、应用内部目录、缓存、自定义保存位置 */
    private List<File> roots() {
        List<File> list = new ArrayList<>();
        Context ctx = getContext();
        if (ctx == null) return list;

        list.add(Environment.getExternalStorageDirectory());
        // /storage 下面是各个卷（内置存储、SD 卡），自定义保存位置可能落在卡上
        File[] vols = new File("/storage").listFiles();
        if (vols != null) {
            for (File v : vols) {
                if (v != null && v.isDirectory() && !list.contains(v)) list.add(v);
            }
        }

        File[] extDirs = ctx.getExternalFilesDirs(null);
        if (extDirs != null) {
            for (File d : extDirs) {
                if (d != null) list.add(d);
            }
        }

        list.add(ctx.getFilesDir());
        list.add(ctx.getCacheDir());

        // 用户自己填的保存路径（可能不在上面任何一根下面）
        try {
            String custom = ctx.getSharedPreferences("lightmirror", Context.MODE_PRIVATE)
                    .getString("saveDir", "");
            if (custom != null && !custom.isEmpty()) list.add(new File(custom));
        } catch (Exception ignored) {
        }
        return list;
    }

    private static String canon(File f) {
        try {
            return f.getCanonicalPath();
        } catch (Exception e) {
            return f.getAbsolutePath();
        }
    }

    private File resolve(Uri uri) {
        List<String> seg = uri.getPathSegments();
        if (seg == null || seg.size() != 2 || !SEG_TAG.equals(seg.get(0))) return null;
        String path;
        try {
            path = new String(Base64.decode(seg.get(1),
                    Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
        if (path.isEmpty()) return null;

        File f = new File(path);
        String target = canon(f);
        for (File root : roots()) {
            String base = canon(root);
            if (target.equals(base) || target.startsWith(base + File.separator)) {
                return f.isFile() ? f : null;
            }
        }
        return null;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = resolve(uri);
        if (f == null) throw new FileNotFoundException("文件不存在或不可共享：" + uri);
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        File f = resolve(uri);
        if (f == null) return null;
        return FileMime.of(f.getName());
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        File f = resolve(uri);
        if (f == null) return null;
        String[] cols = projection != null ? projection
                : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor cursor = new MatrixCursor(cols, 1);
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i] = f.getName();
            else if (OpenableColumns.SIZE.equals(cols[i])) row[i] = f.length();
            else row[i] = null;
        }
        cursor.addRow(row);
        return cursor;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
