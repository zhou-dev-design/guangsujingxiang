package com.wxalbum.app;

import android.webkit.JavascriptInterface;

public class Bridge {

    private final MainActivity act;

    Bridge(MainActivity act) {
        this.act = act;
    }

    @JavascriptInterface
    public boolean isAndroid() {
        return true;
    }

    @JavascriptInterface
    public String saveDir() {
        return act.saveDirLabel();
    }

    @JavascriptInterface
    public void chooseDir() {
        act.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                act.pickDir();
            }
        });
    }

    @JavascriptInterface
    public void saveEpub(final String path) {
        act.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                act.exportEpub(path);
            }
        });
    }

    @JavascriptInterface
    public boolean isEink() {
        return act.isEink();
    }

    @JavascriptInterface
    public void setEink(final boolean on) {
        act.setEink(on);
    }

    @JavascriptInterface
    public void toast(final String msg) {
        act.toast(msg);
    }

    @JavascriptInterface
    public String version() {
        return "1.1";
    }
}
