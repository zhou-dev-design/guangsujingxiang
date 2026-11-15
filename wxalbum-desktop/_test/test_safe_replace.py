"""桌面端安全替换回归测试：成功替换不留旧版，生成出问题则旧版原样留下。

运行：python -m unittest discover -s _test -v
"""
import io
import os
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import app  # noqa: E402


def make_epub(path, chapters=3, mimetype=True, opf=True):
    """造一个结构完整、能通过 verify_epub 的最小 EPUB。"""
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        if mimetype:
            zi = zipfile.ZipInfo("mimetype")
            zi.compress_type = zipfile.ZIP_STORED
            z.writestr(zi, "application/epub+zip")
        if opf:
            z.writestr("OEBPS/content.opf", "<package/>")
        for i in range(chapters):
            z.writestr(f"OEBPS/Text/chap{i + 1:04d}.xhtml", f"<html>第{i + 1}章</html>")
        z.writestr("OEBPS/Styles/style.css", b"x" * 4096)
    return path


class SafeReplaceTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="wxalbum-repl-")
        self.dir = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def test_01_verify_ok(self):
        p = make_epub(self.dir / "ok.epub", 3)
        app.verify_epub(p, 3)  # 不抛异常

    def test_02_first_build(self):
        stage = make_epub(self.dir / ".A.epub.part", 3)
        target = self.dir / "A.epub"
        app.safe_replace(stage, target)
        self.assertTrue(target.exists())
        self.assertFalse(stage.exists())
        self.assertFalse((self.dir / "A.epub.old").exists())

    def test_03_success_leaves_no_old_version(self):
        target = make_epub(self.dir / "A.epub", 3)
        before = target.read_bytes()
        stage = make_epub(self.dir / ".A.epub.part", 5)
        app.verify_epub(stage, 5)
        app.safe_replace(stage, target)
        self.assertNotEqual(target.read_bytes(), before)
        self.assertFalse((self.dir / "A.epub.old").exists(), "替换成功后不应留下旧版")
        self.assertFalse(stage.exists())
        with zipfile.ZipFile(target) as z:
            self.assertEqual(sum(1 for n in z.namelist() if n.startswith("OEBPS/Text/chap")), 5)

    def test_04_broken_zip_keeps_old(self):
        target = make_epub(self.dir / "A.epub", 3)
        before = target.read_bytes()
        full = target.read_bytes()
        broken = self.dir / ".A.epub.part"
        broken.write_bytes(full[: len(full) // 2])
        with self.assertRaises(RuntimeError) as ctx:
            app.verify_epub(broken, 5)
        self.assertIn("旧版本已保留", str(ctx.exception))
        self.assertEqual(target.read_bytes(), before, "旧文件必须字节级不变")

    def test_05_missing_mimetype(self):
        p = make_epub(self.dir / "noMime.epub", 2, mimetype=False)
        with self.assertRaisesRegex(RuntimeError, "缺 mimetype"):
            app.verify_epub(p, 2)

    def test_06_missing_opf(self):
        p = make_epub(self.dir / "noOpf.epub", 2, opf=False)
        with self.assertRaisesRegex(RuntimeError, "缺 content.opf"):
            app.verify_epub(p, 2)

    def test_07_too_few_chapters(self):
        p = make_epub(self.dir / "less.epub", 2)
        with self.assertRaisesRegex(RuntimeError, r"2/5"):
            app.verify_epub(p, 5)

    def test_08_tiny_file(self):
        p = self.dir / "tiny.epub"
        p.write_bytes(b"\x01\x02\x03")
        with self.assertRaisesRegex(RuntimeError, "文件异常"):
            app.verify_epub(p, 1)

    def test_09_crc_broken(self):
        p = self.dir / "crc.epub"
        with zipfile.ZipFile(p, "w", zipfile.ZIP_DEFLATED) as z:
            zi = zipfile.ZipInfo("mimetype")
            zi.compress_type = zipfile.ZIP_STORED
            z.writestr(zi, "application/epub+zip")
            z.writestr("OEBPS/content.opf", "<package/>")
            z.writestr("OEBPS/Text/chap0001.xhtml", "<html>第1章</html>")
            si = zipfile.ZipInfo("OEBPS/Styles/style.css")
            si.compress_type = zipfile.ZIP_STORED   # 明文存储，方便定点破坏数据
            z.writestr(si, b"B" * 4096)
        raw = bytearray(p.read_bytes())
        idx = raw.find(b"B" * 64)
        self.assertGreater(idx, 0)
        raw[idx + 10] ^= 0xFF     # 只改数据，不动 zip 结构 → 能打开但 CRC 不符
        p.write_bytes(raw)
        with self.assertRaisesRegex(RuntimeError, "CRC 错误"):
            app.verify_epub(p, 1)

    def test_10_recover_after_power_loss(self):
        target = make_epub(self.dir / "B.epub", 2)
        old = self.dir / "B.epub.old"
        target.replace(old)
        self.assertFalse(target.exists())
        app.OUT_DIR = self.dir
        app.CONFIG["outdir"] = str(self.dir)
        app.WATCH["items"] = []
        n = app.recover_orphans(lambda lv, msg: None)
        self.assertEqual(n, 1)
        self.assertTrue(target.exists(), "只剩 .old 时应还原旧版本")
        self.assertFalse(old.exists())

    def test_11_recover_cleans_leftovers(self):
        make_epub(self.dir / "C.epub", 1)
        (self.dir / "C.epub.old").write_bytes(b"stale")  # 成功替换后没删干净的残留
        (self.dir / ".C.epub.part").write_bytes(b"partial")
        app.OUT_DIR = self.dir
        app.CONFIG["outdir"] = str(self.dir)
        app.WATCH["items"] = []
        app.recover_orphans(lambda lv, msg: None)
        self.assertFalse((self.dir / "C.epub.old").exists())
        self.assertFalse((self.dir / ".C.epub.part").exists())

    def test_12_no_history_dir_created(self):
        """旧策略会建 _history；新策略不该产生任何历史副本目录。"""
        target = make_epub(self.dir / "D.epub", 2)
        stage = make_epub(self.dir / ".D.epub.part", 4)
        app.verify_epub(stage, 4)
        app.safe_replace(stage, target)
        self.assertFalse((self.dir / "_history").exists())
        self.assertEqual(list(self.dir.glob("*.epub")), [target])


if __name__ == "__main__":
    unittest.main(verbosity=2)
