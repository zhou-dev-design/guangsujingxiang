# -*- coding: utf-8 -*-
"""Android 入口：由 MainActivity 调用 start(home, web, port)。

把工作目录切到应用私有目录后，起本地 HTTP 服务，WebView 再连过来。
"""
import os
import traceback


def start(home, web=None, port=8765):
    os.environ["WXALBUM_HOME"] = str(home)
    import app
    try:
        app.reinit(str(home), str(web) if web else None)
    except Exception:
        traceback.print_exc()
    try:
        app.serve(int(port), False)
    except Exception:
        traceback.print_exc()
