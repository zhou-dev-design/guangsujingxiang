package com.wxalbum.app;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
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
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final String TAG = "wxalbum";
    private static final int PORT = 8765;
    private static final int REQ_DIR = 1001;
    private static final long BOOT_TIMEOUT_MS = 120000L;

    private WebView web;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private boolean eink = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("wxalbum", MODE_PRIVATE);
        eink = prefs.contains("eink") ? prefs.getBoolean("eink", false) : detectEink();
        clearBootLog();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(eink ? 0xFFFFFFFF : 0xFFF2F5F4);

        web = new WebView(this);
        WebSettings st = web.getSettings();
        st.setJavaScriptEnabled(true);
        st.setDomStorageEnabled(true);
        st.setDatabaseEnabled(true);
        st.setLoadWithOverviewMode(true);
        st.setUseWideViewPort(true);
        st.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        st.setTextZoom(100);
        st.setCacheMode(WebSettings.LOAD_NO_CACHE);
        web.setBackgroundColor(eink ? 0xFFFFFFFF : 0xFFF2F5F4);
        web.setOverScrollMode(WebView.OVER_SCROLL_NEVER);
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new Bridge(this), "Android");
        if (eink) {
            // 墨水屏：关掉惯性滚动与过度滚动，减少残影
            web.setVerticalScrollBarEnabled(true);
        }

        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        showLoading();

        try {
            if (!Python.isStarted()) {
                long t0 = SystemClock.elapsedRealtime();
                Python.start(new AndroidPlatform(getApplicationContext()));
                bootLog("Python.start ok, " + (SystemClock.elapsedRealtime() - t0) + "ms");
            }
        } catch (Throwable t) {
            bootLog("Python.start 失败:\n" + stack(t));
            showBootError();
            return;
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                bootPython();
            }
        }, "py-boot").start();

        new Thread(new Runnable() {
            @Override
            public void run() {
                waitAndLoad();
            }
        }, "wait-server").start();
    }

    // ---------------- 启动流程 ----------------

    private void showLoading() {
        String html = "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<style>body{background:#fff;color:#000;font-family:sans-serif;padding:28px 22px;"
                + "line-height:1.7}h3{font-weight:600;font-size:17px;margin:0 0 10px}"
                + "p{font-size:14px;color:#444}</style></head><body>"
                + "<h3>正在启动…</h3><p>首次启动需要解压 Python 运行时，可能要 10–60 秒，请稍候。</p>"
                + "<p style='color:#999;font-size:12px'>界面稍后自动载入</p></body></html>";
        web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
    }

    private void bootPython() {
        try {
            copyWebAssets();
            bootLog("assets 复制完成，开始启动 Python 服务");
            Python.getInstance().getModule("app_main").callAttr(
                    "start",
                    getFilesDir().getAbsolutePath(),
                    webDir().getAbsolutePath(),
                    PORT);
        } catch (Throwable t) {
            Log.e(TAG, "python boot failed", t);
            bootLog("Python 服务启动失败:\n" + stack(t));
            ui.post(new Runnable() {
                @Override
                public void run() {
                    showBootError();
                }
            });
        }
    }

    /** 在子线程轮询端口，就绪后再回到主线程加载页面（主线程做 socket 会抛 NetworkOnMainThread）。 */
    private void waitAndLoad() {
        long deadline = SystemClock.elapsedRealtime() + BOOT_TIMEOUT_MS;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (serverUp()) {
                bootLog("端口 " + PORT + " 就绪，加载界面");
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        web.loadUrl("http://127.0.0.1:" + PORT + "/?android=1");
                    }
                });
                return;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException ignored) {
            }
        }
        bootLog("等待端口 " + PORT + " 超时（" + (BOOT_TIMEOUT_MS / 1000) + " 秒）");
        ui.post(new Runnable() {
            @Override
            public void run() {
                showBootError();
            }
        });
    }

    private boolean serverUp() {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress("127.0.0.1", PORT), 400);
            return true;
        } catch (Throwable e) {
            return false;
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    // ---------------- 启动日志 ----------------

    private File bootLogFile() {
        return new File(getFilesDir(), "boot.log");
    }

    private void clearBootLog() {
        try {
            bootLogFile().delete();
        } catch (Exception ignored) {
        }
    }

    private synchronized void bootLog(String msg) {
        Log.i(TAG, msg);
        try {
            FileOutputStream out = new FileOutputStream(bootLogFile(), true);
            String ts = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
            out.write(("[" + ts + "] " + msg + "\n").getBytes("UTF-8"));
            out.close();
        } catch (Exception ignored) {
        }
    }

    private String readBootLog() {
        try {
            File f = bootLogFile();
            if (!f.exists()) return "（无日志）";
            FileInputStream in = new FileInputStream(f);
            byte[] buf = new byte[(int) Math.min(f.length(), 12000)];
            int n = in.read(buf);
            in.close();
            return new String(buf, 0, Math.max(n, 0), "UTF-8");
        } catch (Exception e) {
            return "（读取日志失败：" + e + "）";
        }
    }

    private String stack(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        String s = sw.toString();
        return s.length() > 4000 ? s.substring(0, 4000) : s;
    }

    private void showBootError() {
        String log = readBootLog().replace("&", "&amp;").replace("<", "&lt;");
        String html = "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<style>body{background:#fff;color:#000;font-family:sans-serif;padding:16px;line-height:1.6}"
                + "h3{font-weight:600;font-size:16px;margin:0 0 8px}"
                + "pre{white-space:pre-wrap;word-break:break-all;font-size:11px;background:#f4f4f4;"
                + "border:1px solid #000;padding:10px;margin-top:10px}"
                + "p{font-size:13px;color:#333}</style></head><body>"
                + "<h3>服务没有启动起来</h3>"
                + "<p>下面是启动日志，把这一页截图发给开发者即可定位问题。</p>"
                + "<pre>" + log + "</pre></body></html>";
        web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
    }

    // ---------------- 资源与路径 ----------------

    private File webDir() {
        return new File(getFilesDir(), "web");
    }

    private void copyWebAssets() {
        File dest = webDir();
        if (!dest.exists() && !dest.mkdirs()) {
            bootLog("创建 web 目录失败: " + dest);
            return;
        }
        try {
            copyAssetDir("web", dest);
            bootLog("web 资源复制完成: " + dest);
        } catch (IOException e) {
            bootLog("复制 assets 失败: " + e);
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

    // ---------------- 墨水屏 ----------------

    private boolean detectEink() {
        String dev = (Build.MANUFACTURER + " " + Build.BRAND + " " + Build.MODEL).toLowerCase(Locale.US);
        String[] keys = {"hisense", "海信", "onyx", "boox", "文石", "yotaphone", "dasung", "大上",
                "ireader", "掌阅", "eink", "e-ink", "inkpad", "inkbook", "moaan", "墨案"};
        for (String k : keys) {
            if (dev.contains(k)) return true;
        }
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method get = sp.getMethod("get", String.class, String.class);
            String[] props = {"ro.eink.mode", "ro.eink", "persist.sys.eink", "ro.hisense.einkmode",
                    "ro.vendor.eink", "ro.boot.eink"};
            for (String p : props) {
                Object v = get.invoke(null, p, "");
                if (v != null && v.toString().length() > 0 && !"0".equals(v.toString())) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    boolean isEink() {
        return eink;
    }

    void setEink(boolean v) {
        eink = v;
        prefs.edit().putBoolean("eink", v).apply();
    }

    // ---------------- JS 桥用 ----------------

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
                String label = saveDirLabel();
                toast("保存位置已更新：" + label);
                notifyDirChanged(label);
            } catch (Exception e) {
                toast("授权失败：" + e.getMessage());
            }
        }
    }

    private void notifyDirChanged(String name) {
        if (web == null) return;
        String safe = name == null ? "" : name.replace("\\", "\\\\").replace("'", "\\'");
        final String js = "window.__onDirChanged && window.__onDirChanged('" + safe + "')";
        web.post(new Runnable() {
            @Override
            public void run() {
                try {
                    web.evaluateJavascript(js, null);
                } catch (Throwable ignored) {
                }
            }
        });
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
