# -*- coding: utf-8 -*-
"""静态校验：两个墨水屏主题和所有 *_e6 drawable 只能是纯黑 #FF000000 / 纯白 #FFFFFFFF。

墨水屏只有黑白两色，任何灰阶或半透明都会产生抖动残影。
用法：python _test/check_1bit.py
"""
import io
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ALLOWED = {"#ff000000", "#ffffffff"}
HEX = re.compile(r"#[0-9a-fA-F]{6,8}")
errors = []


def scan(path, text, label):
    for m in HEX.finditer(text):
        v = m.group(0).lower()
        if len(v) == 7:                       # #RRGGBB 视为不透明
            v = "#ff" + v[1:]
        if v not in ALLOWED:
            line = text.count("\n", 0, m.start()) + 1
            errors.append("%s:%d  %s (%s)" % (label, line, m.group(0), path.name))


styles = ROOT / "app/src/main/res/values/styles.xml"
s = io.open(styles, encoding="utf-8").read()
for name in ("AppTheme.Mono", "AppTheme.Eink6", "EinkDialog"):
    i = s.find('<style name="%s"' % name)
    if i < 0:
        errors.append("找不到主题 %s" % name)
        continue
    j = s.find("</style>", i)
    scan(styles, s[i:j], name)

d = ROOT / "app/src/main/res/drawable"
files = sorted(d.glob("*_e6.xml"))
if not files:
    errors.append("没有找到任何 _e6 drawable")
for f in files:
    scan(f, io.open(f, encoding="utf-8").read(), f.name)

# 半透明调用：除 dimButton 这个统一入口外，不允许任何直接 setAlpha
main = ROOT / "app/src/main/java/com/wxalbum/nativeapp/MainActivity.java"
ms = io.open(main, encoding="utf-8").read()
i = ms.find("private void dimButton(")
j = ms.find("\n    }", i) if i >= 0 else -1
outside = (ms[:i] + ms[j:]) if (i >= 0 and j > i) else ms
for m in re.finditer(r"\.setAlpha\(", outside):
    seg = outside[m.start():m.start() + 80].replace("\n", " ")
    errors.append("MainActivity.java 仍有直接 setAlpha: %s" % seg[:70])

print("扫描主题: AppTheme.Mono / AppTheme.Eink6 / EinkDialog")
print("扫描 1bit drawable: %d 个" % len(files))
if errors:
    print("\n发现 %d 处非黑白颜色/半透明：" % len(errors))
    for e in errors:
        print("  -", e)
    sys.exit(1)
print("OK：墨水屏路径上全部为纯黑 / 纯白，无灰阶、无半透明")
