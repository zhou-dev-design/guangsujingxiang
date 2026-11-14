package com.wxalbum.app;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.documentfile.provider.DocumentFile;

import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

public class MainActivity extends Activity {

    private static final String TAG = "wxalbum";
    private static final int PORT = 8765;
    private static final int REQ_DIR = 1001;

    private WebView web;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("wxalbum", MODE_PRIVATE);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFFF2F5F4);

        web = new WebView(this);
        WebSettings st = web.getSettings();
        st.setJavaScriptEnabled(true);
        st.setDomStorageEnabled(true);
        st.setDatabaseEnabled(true);
        st.setLoadWithOverviewMode(true);
        st.setUseWideViewPort(true);
        st.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        st.setTextZoom(100);
        web.setBackgroundColor(0xFFF2F5F4);
        web.setOverScrollMode(WebView.OVER_SCROLL_NEVER);
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new Bridge(this), "Android");

        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        copyWebAssets();

        new Thread(new Runnable() {
            @Override
            public void run() {
                startPython();
            }
        }, "py-server").start();

        waitForServer(0);
    }

    private File webDir() {
        return new File(getFilesDir(), "web");
    }

    private void copyWebAssets() {
        File dest = webDir();
        if (!dest.exists() && !dest.mkdirs()) {
            Log.e(TAG, "mkdir web failed");
            return;
        }
        try {
            copyAssetDir("web", dest);
        } catch (IOException e) {
            Log.e(TAG, "copy assets failed", e);
        }
    }

    private void copyAssetDir(String path, File dest) throws IOException {
        String[] list = getAssets().list(path);
        if (list == null) return;
        for (String name : list) {
            String child = path + "/" + name;
            String[] sub = getAssets().list(child);
            if (sub != null && sub.length > 0) {
                File d = new File(dest, name);
                if (!d.exists()) d.mkdirs();
                copyAssetDir(child, d);
            } else {
                File f = new File(dest, name);
                File parent = f.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                InputStream in = getAssets().open(child);
                OutputStream out = new FileOutputStream(f);
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                out.close();
                in.close();
            }
        }
    }

    private void startPython() {
        try {
            if (!Python.isStarted()) {
                Python.start(new AndroidPlatform(this));
            }
            Python.getInstance().getModule("app_main").callAttr(
                    "start",
                    getFilesDir().getAbsolutePath(),
                    webDir().getAbsolutePath(),
                    PORT);
        } catch (Throwable t) {
            Log.e(TAG, "python failed", t);
            ui.post(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(MainActivity.this, "Python 启动失败：" + t.getMessage(),
                            Toast.LENGTH_LONG).show();
                }
            });
        }
    }

    private boolean serverUp() {
        try {
            Socket s = new Socket();
            s.connect(new InetSocketAddress("127.0.0.1", PORT), 300);
            s.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void waitForServer(final int tries) {
        if (serverUp()) {
            web.loadUrl("http://127.0.0.1:" + PORT + "/?android=1");
            return;
        }
        if (tries > 100) {
            web.loadData("<html><body style='font-family:sans-serif;padding:24px'>"
                    + "<h3>服务启动超时</h3><p>请退出重进；若反复失败，把这一页截图发给开发者。</p>"
                    + "</body></html>", "text/html; charset=utf-8", "UTF-8");
            return;
        }
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                waitForServer(tries + 1);
            }
        }, 200);
    }

    void toast(final String msg) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    void pickDir() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ_DIR);
        } catch (Exception e) {
            toast("无法打开目录选择器：" + e.getMessage());
        }
    }

    String saveDirLabel() {
        String t = prefs.getString("tree", null);
        if (t == null) return "下载/公众号EPUB";
        try {
            DocumentFile d = DocumentFile.fromTreeUri(this, Uri.parse(t));
            if (d != null && d.getName() != null) return d.getName();
        } catch (Exception ignored) {
        }
        return "已选目录";
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_DIR && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri tree = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(tree,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                prefs.edit().putString("tree", tree.toString()).apply();
                toast("保存位置已更新：" + saveDirLabel());
            } catch (Exception e) {
                toast("授权失败：" + e.getMessage());
            }
        }
    }

    void exportEpub(String pyPath) {
        File src = new File(pyPath);
        if (!src.exists()) {
            toast("文件不存在：" + pyPath);
            return;
        }
        String name = src.getName();
        try {
            String tree = prefs.getString("tree", null);
            if (tree != null) {
                DocumentFile dir = DocumentFile.fromTreeUri(this, Uri.parse(tree));
                if (dir != null && dir.canWrite()) {
                    DocumentFile target = dir.findFile(name);
                    if (target != null) target.delete();
                    target = dir.createFile("application/epub+zip", name);
                    if (target == null) target = dir.createFile("application/octet-stream", name);
                    if (target == null) throw new IOException("无法在所选目录创建文件");
                    OutputStream out = getContentResolver().openOutputStream(target.getUri());
                    copy(new FileInputStream(src), out);
                    toast("已保存到「" + dir.getName() + "」：" + name);
                    return;
                }
            }
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            cv.put(MediaStore.MediaColumns.MIME_TYPE, "application/epub+zip");
            cv.put(MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/公众号EPUB");
            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) throw new IOException("无法写入下载目录");
            OutputStream out = getContentResolver().openOutputStream(uri);
            copy(new FileInputStream(src), out);
            toast("已保存到「下载/公众号EPUB」：" + name);
        } catch (Exception e) {
            Log.e(TAG, "export failed", e);
            toast("保存失败：" + e.getMessage());
        }
    }

    private void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        out.flush();
        out.close();
        in.close();
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
