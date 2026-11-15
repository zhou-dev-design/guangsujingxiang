"""端到端：用真实缓存章节调用 build_epub 的 target_path 分支。

成功 → 原位替换且不留旧版；自检失败 → 原文件字节不变、临时文件清空。
运行：python -m unittest discover -s _test -v
"""
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import app  # noqa: E402


def cached_chapters(n=3):
    """从本地缓存里取 n 篇现成章节，避免联网。"""
    items = []
    for p in sorted(app.CACHE_DIR.glob("*/*.json")):
        try:
            d = json.loads(p.read_text(encoding="utf-8"))
        except Exception:
            continue
        blocks = [tuple(b) for b in d.get("blocks", []) if isinstance(b, list) and len(b) == 2]
        if not blocks:
            continue
        items.append({"title": d.get("title") or "无题",
                      "account": d.get("account") or "",
                      "publish": d.get("publish") or "",
                      "blocks": [list(b) for b in blocks]})
        if len(items) >= n:
            break
    return items


def quiet(level, msg):
    pass


class BuildReplaceTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="wxalbum-build-")
        self.dir = Path(self.tmp.name)
        self.chaps = cached_chapters(3)
        if len(self.chaps) < 3:
            self.skipTest("本地章节缓存不足，跳过集成测试")
        self.album = {"title": "集成测试合集", "author": "测试作者",
                      "album_id": "test-safety", "cover": "", "desc": "", "url": ""}
        self.opts = {"images": False, "bookinfo": True, "numbering": True,
                     "title": "集成测试合集", "author": "测试作者",
                     "fetcher": None, "cancel": False}

    def tearDown(self):
        self.tmp.cleanup()

    def _fake_old_book(self):
        """拿一个真实 epub 当旧版本，内容无所谓，只要字节可比对。"""
        src = next((p for p in app.OUT_DIR.glob("*.epub") if p.is_file()), None)
        target = self.dir / "集成测试合集.epub"
        if src:
            target.write_bytes(src.read_bytes())
        else:
            target.write_bytes(b"OLD-EPUB-PLACEHOLDER-" + b"x" * 2048)
        return target

    def test_01_success_replaces_in_place_without_history(self):
        target = self._fake_old_book()
        before = target.read_bytes()
        opts = dict(self.opts, target_path=str(target))
        res = app.build_epub(self.album, self.chaps, opts, self.dir, quiet)
        self.assertEqual(res["path"], str(target), "必须原位替换，不能另起文件名")
        self.assertNotEqual(target.read_bytes(), before)
        app.verify_epub(target, len(self.chaps))
        self.assertFalse((self.dir / "_history").exists(), "不应再建 _history")
        self.assertEqual(list(self.dir.glob("*.part")), [])
        self.assertEqual(list(self.dir.glob("*.old")), [])
        self.assertEqual(
            sorted(p.name for p in self.dir.glob("*.epub")),
            ["集成测试合集.epub"], "不应多出新文件")

    def test_02_failed_check_keeps_old_version(self):
        target = self._fake_old_book()
        before = target.read_bytes()
        opts = dict(self.opts, target_path=str(target))
        original = app.verify_epub

        def fail(*a, **kw):
            raise RuntimeError("模拟：新版本生成出问题，旧版本已保留")

        app.verify_epub = fail
        try:
            with self.assertRaisesRegex(RuntimeError, "旧版本已保留"):
                app.build_epub(self.album, self.chaps, opts, self.dir, quiet)
        finally:
            app.verify_epub = original
        self.assertEqual(target.read_bytes(), before, "失败时旧文件必须字节级不变")
        self.assertEqual(list(self.dir.glob("*.part")), [], "临时文件必须清理")
        self.assertEqual(list(self.dir.glob("*.old")), [])
        self.assertFalse((self.dir / "_history").exists())


if __name__ == "__main__":
    unittest.main(verbosity=2)
