# -*- coding: utf-8 -*-
"""Android 入口：由 MainActivity 调用 start(home, web, port)。

把工作目录切到应用私有目录后，起本地 HTTP 服务，WebView 再连过来。
出错时把 traceback 写进 boot.log，方便在应用内直接看到原因。
"""
import os
import sys
import traceback


def _log(home, msg):
    try:
        path = os.path.join(home, "boot.log")
        with open(path, "a", encoding="utf-8") as f:
            f.write(msg.rstrip() + "\n")
    except Exception:
        pass


def start(home, web=None, port=8765):
    home = str(home)
    os.environ["WXALBUM_HOME"] = home
    _log(home, "python 入口启动，home=%s" % home)
    _log(home, "python 版本 %s" % sys.version.split()[0])

    try:
        import app
    except Exception:
        _log(home, "import app 失败:\n" + traceback.format_exc())
        raise

    try:
        app.reinit(home, str(web) if web else None)
        _log(home, "reinit 完成，WEB_DIR=%s" % app.WEB_DIR)
    except Exception:
        _log(home, "reinit 失败:\n" + traceback.format_exc())
        raise

    try:
        _log(home, "开始监听 127.0.0.1:%s" % port)
        app.serve(int(port), False)
    except Exception:
        _log(home, "serve 失败:\n" + traceback.format_exc())
        raise
