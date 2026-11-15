# -*- coding: utf-8 -*-
"""EPUB 结构校验（标准库）：XML 合法性 / manifest 与文件一致 / spine 引用 / mimetype 位置。"""
import sys, zipfile, re
import xml.etree.ElementTree as ET
from pathlib import Path

def check(path):
    p = Path(path)
    print(f"=== {p.name}  {p.stat().st_size/1024/1024:.2f} MB ===")
    z = zipfile.ZipFile(p)
    names = z.namelist()
    infos = z.infolist()
    ok = True

    # mimetype
    if names[0] != "mimetype" or infos[0].compress_type != zipfile.ZIP_STORED:
        print("  ✗ mimetype 必须是第一个且不压缩"); ok = False
    else:
        print("  ✓ mimetype 正确（首个、未压缩）")
    if z.read("mimetype") != b"application/epub+zip":
        print("  ✗ mimetype 内容错误"); ok = False

    # container
    try:
        c = ET.fromstring(z.read("META-INF/container.xml"))
        root = c.find(".//{urn:oasis:names:tc:opendocument:xmlns:container}rootfile")
        opf_path = root.get("full-path")
        print("  ✓ container.xml →", opf_path)
    except Exception as e:
        print("  ✗ container.xml:", e); return

    opf = ET.fromstring(z.read(opf_path))
    ns = {"opf": "http://www.idpf.org/2007/opf", "dc": "http://purl.org/dc/elements/1.1/"}
    title = opf.find(".//dc:title", ns)
    creator = opf.find(".//dc:creator", ns)
    print(f"  · 书名: {title.text if title is not None else '?'}  作者: {creator.text if creator is not None else '?'}")

    base = str(Path(opf_path).parent).replace("\\", "/")
    base = "" if base == "." else base + "/"
    manifest = {}
    for it in opf.findall(".//opf:manifest/opf:item", ns):
        href = it.get("href")
        manifest[it.get("id")] = href
        full = base + href
        if full not in names:
            print(f"  ✗ manifest 缺文件: {href}"); ok = False

    spine = [i.get("idref") for i in opf.findall(".//opf:spine/opf:itemref", ns)]
    for s in spine:
        if s not in manifest:
            print(f"  ✗ spine 引用不存在: {s}"); ok = False
    print(f"  ✓ manifest {len(manifest)} 项 / spine {len(spine)} 项")

    # 目录（nav.xhtml / toc.ncx）的链接是否真的指向存在的文件
    import html as H
    for toc_name in ("nav.xhtml", "toc.ncx"):
        full_toc = base + toc_name
        if full_toc not in names:
            continue
        txt = z.read(full_toc).decode("utf-8")
        hrefs = re.findall(r'<a[^>]+href="([^"#]+)[^"]*"', txt) or re.findall(r'<content src="([^"#]+)', txt)
        bad = [h for h in hrefs if (base + H.unescape(h)) not in names]
        if bad:
            print(f"  ✗ {toc_name} 有 {len(bad)} 个失效链接，例如: {bad[:3]}")
            ok = False
        else:
            print(f"  ✓ {toc_name} 的 {len(hrefs)} 个链接全部有效")

    # 所有 xhtml XML 合法
    bad = 0
    for n in names:
        if n.endswith(".xhtml") or n.endswith(".opf") or n.endswith(".ncx"):
            try:
                ET.fromstring(z.read(n))
            except Exception as e:
                bad += 1
                print(f"  ✗ XML 不合法 {n}: {e}")
    if not bad:
        print(f"  ✓ 全部 XHTML/OPF/NCX 解析通过")
    else:
        ok = False

    # 正文统计
    chaps = [n for n in names if re.match(r".*/Text/chap\d+\.xhtml$", n)]
    imgs = [n for n in names if re.match(r".*/Images/.*\.(jpg|png|gif)$", n)]
    words = 0
    for n in chaps:
        body = z.read(n).decode("utf-8")
        words += len(re.sub(r"<[^>]+>", "", body.split("<body>")[-1]))
    print(f"  · 章节 {len(chaps)} / 图片 {len(imgs)} / 正文约 {words} 字")

    # 抽样标题
    for n in chaps[:3]:
        body = z.read(n).decode("utf-8")
        m = re.search(r"<h1[^>]*>(.*?)</h1>", body)
        print("  · 章节:", m.group(1) if m else "?")
    print("  结论:", "通过 ✓" if ok else "有问题 ✗")
    return ok

if __name__ == "__main__":
    args = sys.argv[1:] or [str(p) for p in Path("output").glob("*.epub")]
    for a in args:
        check(a)
