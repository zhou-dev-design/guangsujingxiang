# -*- coding: utf-8 -*-
"""保存位置设置的回归测试。

覆盖：目录选择子脚本语法、/api/config 路径校验与持久化、
/api/pickdir 成功/取消/失败三种返回、自定义保存位置下的 /api/download。
测试期间把 CONFIG_PATH 指向临时文件，不碰真实配置。
"""
import json
import os
import sys
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import app


def req(port, path, body=None):
    url = "http://127.0.0.1:%d%s" % (port, path)
    if body is None:
        r = urllib.request.Request(url)
    else:
        r = urllib.request.Request(
            url, data=json.dumps(body).encode("utf-8"),
            headers={"Content-Type": "application/json"})
    try:
        resp = urllib.request.urlopen(r, timeout=10)
    except urllib.error.HTTPError as e:
        resp = e
    raw = resp.read()
    ctype = resp.headers.get("Content-Type") or ""
    data = json.loads(raw.decode("utf-8")) if "json" in ctype else raw
    return resp.status, data


class ConfigOutdirTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.orig_path = app.CONFIG_PATH
        cls.orig_cfg = dict(app.CONFIG)
        cls.orig_pick = app.pick_directory
        cls.orig_log = app.Handler.log_message
        app.Handler.log_message = lambda *a, **k: None
        cls.tmp = tempfile.TemporaryDirectory(prefix="wxalbum-cfg-")
        cls.root = Path(cls.tmp.name)
        app.CONFIG_PATH = cls.root / "config.json"
        cls.srv = ThreadingHTTPServer(("127.0.0.1", 0), app.Handler)
        cls.port = cls.srv.server_address[1]
        threading.Thread(target=cls.srv.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        cls.srv.shutdown()
        cls.srv.server_close()
        app.CONFIG_PATH = cls.orig_path
        app.CONFIG.clear()
        app.CONFIG.update(cls.orig_cfg)
        app.pick_directory = cls.orig_pick
        app.Handler.log_message = cls.orig_log
        cls.tmp.cleanup()

    def setUp(self):
        app.CONFIG.clear()
        app.CONFIG.update({"outdir": "", "concurrency": 0})
        app.pick_directory = type(self).orig_pick   # 每个用例都从真实实现开始

    def conf(self):
        return req(self.port, "/api/config")[1]

    def test_01_default(self):
        c = self.conf()
        self.assertFalse(c["configured"])
        self.assertEqual(c["outdir"], str(app.OUT_DIR))
        self.assertEqual(c["default_outdir"], str(app.OUT_DIR))

    def test_02_apply_creates_dir_and_takes_effect(self):
        target = self.root / "books"
        self.assertFalse(target.exists())
        status, r = req(self.port, "/api/config", {"outdir": str(target)})
        self.assertEqual(status, 200)
        self.assertTrue(r["config"]["configured"])
        self.assertEqual(r["config"]["outdir"], str(target))
        self.assertTrue(target.is_dir(), "不存在的目录应自动创建")
        self.assertEqual(app.output_dir(), target)

    def test_03_persists_after_reload(self):
        target = self.root / "persist"
        req(self.port, "/api/config", {"outdir": str(target)})
        saved = json.loads(app.CONFIG_PATH.read_text(encoding="utf-8"))
        self.assertEqual(saved["outdir"], str(target))
        app.CONFIG.clear()
        app.CONFIG.update({"outdir": "", "concurrency": 0})
        app.load_config()
        self.assertEqual(app.output_dir(), target)
        c = self.conf()
        self.assertTrue(c["configured"])
        self.assertEqual(c["outdir"], str(target))

    def test_04_reject_file_as_dir(self):
        f = self.root / "not_a_dir.txt"
        f.write_text("x", encoding="utf-8")
        target = self.root / "keep"
        req(self.port, "/api/config", {"outdir": str(target)})
        status, r = req(self.port, "/api/config", {"outdir": str(f)})
        self.assertEqual(status, 400)
        self.assertIn("error", r)
        self.assertEqual(app.output_dir(), target, "校验失败不能改动现有设置")

    def test_05_reset_to_default(self):
        req(self.port, "/api/config", {"outdir": str(self.root / "reset")})
        status, r = req(self.port, "/api/config", {"outdir": ""})
        self.assertEqual(status, 200)
        self.assertFalse(r["config"]["configured"])
        self.assertEqual(r["config"]["outdir"], str(app.OUT_DIR))

    def test_06_concurrency_keeps_outdir(self):
        target = self.root / "conc"
        req(self.port, "/api/config", {"outdir": str(target)})
        status, r = req(self.port, "/api/config", {"concurrency": 12})
        self.assertEqual(status, 200)
        self.assertEqual(r["config"]["concurrency"], 12)
        self.assertEqual(r["config"]["outdir"], str(target))

    def test_07_pickdir_success(self):
        target = self.root / "picked"
        target.mkdir()
        app.pick_directory = lambda: (str(target), "")
        status, r = req(self.port, "/api/pickdir", {})
        self.assertEqual(status, 200)
        self.assertTrue(r["ok"])
        self.assertEqual(r["path"], str(target))
        self.assertEqual(r["config"]["outdir"], str(target))
        self.assertEqual(app.output_dir(), target)

    def test_08_pickdir_cancel_does_not_change(self):
        target = self.root / "before"
        req(self.port, "/api/config", {"outdir": str(target)})
        app.pick_directory = lambda: ("", "")
        status, r = req(self.port, "/api/pickdir", {})
        self.assertEqual(status, 200)
        self.assertFalse(r["ok"])
        self.assertTrue(r["cancelled"])
        self.assertEqual(app.output_dir(), target, "取消后位置不应变化")

    def test_09_pickdir_error_reported(self):
        app.pick_directory = lambda: ("", "目录选择框启动失败：boom")
        status, r = req(self.port, "/api/pickdir", {})
        self.assertEqual(status, 200)
        self.assertFalse(r["ok"])
        self.assertIn("boom", r["error"])

    def test_10_download_from_custom_dir(self):
        target = self.root / "downloads"
        req(self.port, "/api/config", {"outdir": str(target)})
        (target / "book.epub").write_bytes(b"PK\x03\x04FAKE-EPUB")
        status, data = req(self.port, "/api/download?name=book.epub")
        self.assertEqual(status, 200)
        self.assertEqual(data, b"PK\x03\x04FAKE-EPUB")

    def test_11_dialog_script_is_valid_python(self):
        """回归：曾经的 try://n 让整个弹窗脚本 SyntaxError，位置永远改不了。"""
        seen = {}

        class R:
            returncode = 0
            stdout = "D:/Some/Where".encode("utf-8")
            stderr = b""

        def fake_run(cmd, **kw):
            seen["cmd"] = cmd
            return R()

        with mock.patch.object(app.subprocess, "run", fake_run):
            path, err = app.pick_directory()
        code = seen["cmd"][2]
        compile(code, "<pickdir_dialog>", "exec")   # 语法错误会直接抛
        self.assertIn("filedialog.askdirectory", code)
        self.assertNotIn("//n", code)
        self.assertEqual(path, "D:/Some/Where")
        self.assertEqual(err, "")

    def test_12_dialog_failure_becomes_error(self):
        class R:
            returncode = 1
            stdout = b""
            stderr = b"Traceback...\nSyntaxError: bad\n"

        with mock.patch.object(app.subprocess, "run", lambda *a, **k: R()):
            path, err = app.pick_directory()
        self.assertEqual(path, "")
        self.assertIn("SyntaxError: bad", err)

    def test_13_dialog_script_runs_in_real_subprocess(self):
        """把会阻塞的弹窗换成固定值，其余代码在真实解释器里跑，验证 stdout 能回传路径。"""
        import subprocess
        seen = {}

        class R:
            returncode = 0
            stdout = b""
            stderr = b""

        def fake_run(cmd, **kw):
            seen["cmd"] = cmd
            return R()

        with mock.patch.object(app.subprocess, "run", fake_run):
            app.pick_directory()
        lines = ["        p = 'D:/stub_path'" if "filedialog.askdirectory" in ln else ln
                 for ln in seen["cmd"][2].splitlines()]
        code = "\n".join(lines)
        # 可用 WXALBUM_TEST_PYTHON 指定装了 tkinter 的解释器做真实子进程验证
        exe = os.environ.get("WXALBUM_TEST_PYTHON") or sys.executable
        probe = subprocess.run([exe, "-c", "import tkinter"],
                               capture_output=True, timeout=20)
        if probe.returncode != 0:
            self.skipTest("该解释器未安装 tkinter（此时可用手填路径，弹窗会明确报错）")
        r = subprocess.run([exe, "-c", code], capture_output=True, timeout=40)
        self.assertEqual(r.returncode, 0, r.stderr.decode("utf-8", "ignore"))
        self.assertEqual(r.stdout.decode("utf-8", "ignore").strip(), "D:/stub_path")


if __name__ == "__main__":
    unittest.main(verbosity=2)
