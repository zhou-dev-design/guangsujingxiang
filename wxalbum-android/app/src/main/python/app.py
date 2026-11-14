#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
公众号合集 → EPUB

把 https://mp.weixin.qq.com/mp/appmsgalbum?... 这类「合集」链接里的图文批量抓下来，
合成一本带封面和目录的 EPUB（腾讯文档/微信读书/Apple Books/Kindle 都能读）。

用法：
    python app.py            启动界面（默认自动打开浏览器 http://127.0.0.1:8765）
    python app.py --cli URL  命令行直接跑一遍

只依赖 Python 标准库；若安装了 Pillow，会生成封面图并压缩插图。
"""

import sys, os, re, io, json, ssl, gzip, time, shutil, html as H, zipfile, uuid, random
import socket, threading, hashlib, datetime, traceback, webbrowser, argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
import urllib.request, urllib.parse, urllib.error, http.cookiejar
from html.parser import HTMLParser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

try:
    from PIL import Image, ImageDraw, ImageFont
    HAS_PIL = True
except Exception:
    HAS_PIL = False

_HOME = os.environ.get("WXALBUM_HOME")
if _HOME:                                     # Android / 外部指定工作目录
    APP_DIR = Path(_HOME)
    WEB_DIR = APP_DIR / "web"
elif getattr(sys, "frozen", False):           # 打包成 exe 时
    APP_DIR = Path(sys.executable).resolve().parent
    WEB_DIR = Path(getattr(sys, "_MEIPASS", APP_DIR)) / "web"
else:
    APP_DIR = Path(__file__).resolve().parent
    WEB_DIR = APP_DIR / "web"
CACHE_DIR = APP_DIR / "cache"
OUT_DIR = APP_DIR / "output"
for d in (WEB_DIR, CACHE_DIR, OUT_DIR):
    try:
        d.mkdir(parents=True, exist_ok=True)
    except Exception:
        pass


def is_android():
    return bool(os.environ.get("WXALBUM_HOME"))

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
      "Chrome/126.0.0.0 Safari/537.36 MicroMessenger/7.0.20.1781(0x6700143B) NetType/WIFI "
      "MiniProgramEnv/Windows WindowsWechat/WMPF WindowsWechat(0x63090c33)")
ALBUM_API = "https://mp.weixin.qq.com/mp/appmsgalbum"
MAX_ARTICLES = 3000          # 安全上限，防止死循环
ZW = "\u200b\u200c\u200d\ufeff\u2060"

# 正文里的推广尾巴 / 导流话术
PROMO = re.compile(
    r"阅读原文|有声剧|蜻蜓FM|喜马拉雅|打赏|赞赏|求订阅|求月票|求收藏|求推荐|求转发|求点赞|"
    r"扫码|长按识别|点击上方|点击下方|点击蓝字|关注我们|设为星标|星标公众号|"
    r"喜欢此内容的人还喜欢|预览时标签不可点|继续滑动看下一个|轻触阅读原文|"
    r"浏览更多精彩|往期推荐|往期回顾|点个在看|分享给更多人|侵权|删除|投稿"
)

# 微信页面浮层 / 控件文案，会混进 js_content 里，必须整段丢弃
UI_NOISE = {
    "取消允许", "取消", "允许", "微信扫一扫", "微信扫一扫可打开此内容", "微信扫一扫可打开此内容，",
    "预览时标签不可点", "不喜欢", "关注该公众号", "知道了", "使用小程序", "×分析", "分析",
    "使用完整服务", "修改于", "继续滑动看下一个", "轻触阅读原文", "写留言", "留言",
    "赞", "在看", "分享", "视频", "小程序", "广告", "跳过", "了解公众号", "打开完整版",
}
UI_NOISE_PREFIX = ("微信扫一扫", "长按识别", "扫描二维码", "打开微信", "前往公众号", "关注「公众号")

# 段落里只要出现这些片段，整段丢掉（微信控件 / 平台导流）
UI_NOISE_SUBSTR = (
    "预览时标签不可点", "继续滑动看下一个", "轻触阅读原文", "微信扫一扫",
    "长按识别二维码", "扫码关注", "点击上方蓝字", "点击蓝字关注", "关注该公众号",
    "使用完整服务", "使用小程序", "喜欢此内容的人还喜欢", "往期推荐", "往期回顾",
    "点个在看", "分享给更多人", "打开完整版", "扫描二维码关注",
)

# 作者推广 / 求支持 / 群号（PS1、PS2、PS3 那一类）
PS_RE = re.compile(r"^\s*[Pp]\s*[.．]?\s*[Ss]\s*\d?\s*[:：]")
AUTHOR_RE = re.compile(r"^\s*作\s*者\s*[:：]\s*\S{1,24}\s*$")
# 文末的责任编辑 / 校对 / 审核 / 排版 短署名
CREDIT_RE = re.compile(
    r"^\s*(编辑|责编|责任编辑|校对|审核|排版|美编|视觉|监制|撰文|供稿|来源|记者)"
    r"\s*[:：]?\s*[\u4e00-\u9fa5A-Za-z0-9·]{1,14}\s*$")
PROMO_HINT = ("三连支持", "求点赞", "求分享", "求推荐", "求订阅", "求月票", "求收藏", "点赞、分享",
              "书友群", "QQ群", "qq群", "微信群", "加更", "留言嗨", "有奖", "打赏", "赞赏",
              "免费发布", "全本免费", "首发于", "转载请注明", "公众号后台", "点击关注",
              # 作者前言 / 群推广 / 公众号口号
              "构思群", "讨论群", "同人群", "交流群", "跳转观看", "建议先补齐",
              "每个人都有自己的精彩", "人物志，每个人", "的同人文", "的同人续集")


def is_ui_noise(t):
    s = re.sub(r"\s+", " ", t).strip()
    flat = s.replace(" ", "").replace("\u00a0", "")
    if not flat:
        return True
    if flat in UI_NOISE:
        return True
    if len(flat) <= 30 and any(flat.startswith(p) for p in UI_NOISE_PREFIX):
        return True
    return False


def is_promo_line(t):
    """作者推广段（PS1/PS2/PS3）、作者署名行、混进正文的平台控件文案。"""
    s = t.strip()
    if not s:
        return True
    if PS_RE.match(s) or AUTHOR_RE.match(s) or CREDIT_RE.match(s):
        return True
    if len(s) <= 120 and any(k in s for k in UI_NOISE_SUBSTR):
        return True
    if len(s) <= 160 and any(k in s for k in PROMO_HINT):
        return True
    return False


# ----------------------------------------------------------------------------
# 小工具
# ----------------------------------------------------------------------------
def clean(s):
    s = s or ""
    for ch in ZW:
        s = s.replace(ch, "")
    return re.sub(r"[ \t\u3000]+", " ", s).strip()


def esc(s):
    return H.escape(str(s), quote=True)


def safe_name(s, fallback="book"):
    s = re.sub(r'[\\/:*?"<>|\r\n\t]+', "_", clean(s)).strip(" ._")
    return (s or fallback)[:80]


def now_str():
    return datetime.datetime.now().strftime("%H:%M:%S")


def qs_get(url, key):
    q = urllib.parse.parse_qs(urllib.parse.urlparse(url).query)
    v = q.get(key)
    return v[0] if v else None


# ----------------------------------------------------------------------------
# 网络
# ----------------------------------------------------------------------------
class Fetcher:
    """带 cookie 复用、gzip、重试的极简下载器。"""

    def __init__(self):
        self.cj = http.cookiejar.CookieJar()
        try:
            self.ctx = ssl.create_default_context()
        except Exception:
            self.ctx = ssl._create_unverified_context()
        self.ctx_noverify = ssl.create_default_context()
        self.ctx_noverify.check_hostname = False
        self.ctx_noverify.verify_mode = ssl.CERT_NONE
        first_ctx = self.ctx_noverify if is_android() else self.ctx
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(self.cj),
            urllib.request.HTTPSHandler(context=first_ctx))
        self.opener_nv = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(self.cj),
            urllib.request.HTTPSHandler(context=self.ctx_noverify))
        self.opener.addheaders = [("User-Agent", UA), ("Accept-Language", "zh-CN,zh;q=0.9"),
                                  ("Accept-Encoding", "gzip, deflate")]

    def raw(self, url, referer=None, timeout=30):
        hdr = {"Referer": referer or "https://mp.weixin.qq.com/"}
        req = urllib.request.Request(url, headers=hdr)
        try:
            r = self.opener.open(req, timeout=timeout)
        except urllib.error.URLError as e:
            if isinstance(getattr(e, "reason", None), ssl.SSLError):
                r = self.opener_nv.open(req, timeout=timeout)
            else:
                raise
        with r:
            data = r.read()
            ctype = r.headers.get("Content-Type", "")
            if "gzip" in r.headers.get("Content-Encoding", ""):
                data = gzip.decompress(data)
            return data, ctype, r.status

    def text(self, url, referer=None, timeout=30):
        data, ctype, _ = self.raw(url, referer, timeout)
        m = re.search(r"charset=([\w-]+)", ctype, re.I)
        enc = m.group(1) if m else "utf-8"
        try:
            return data.decode(enc, "replace")
        except LookupError:
            return data.decode("utf-8", "replace")

    def retry_text(self, url, tries=3, referer=None, log=None, backoff=1.8):
        last = None
        for i in range(tries):
            try:
                return self.text(url, referer)
            except Exception as e:
                last = e
                if log and i < tries - 1:
                    log("warn", f"  ↻ 第 {i+1} 次失败（{e.__class__.__name__}: {e}），稍后重试")
                time.sleep(backoff ** i + random.random())
        raise last


# ----------------------------------------------------------------------------
# 链接解析
# ----------------------------------------------------------------------------
def parse_input(raw):
    """从用户粘贴的任意文本里找出公众号链接，判断是合集还是单篇。"""
    txt = H.unescape(raw or "").strip()
    if not txt:
        raise ValueError("请先粘贴合集链接")
    urls = re.findall(r"https?://[^\s<>\"'）)】\]]+", txt)
    if not urls:
        m = re.search(r"(mp\.weixin\.qq\.com/[^\s<>\"'）)】\]]+)", txt)
        if m:
            urls = ["https://" + m.group(1)]
    if not urls:
        raise ValueError("没有识别到链接，请粘贴形如 https://mp.weixin.qq.com/mp/appmsgalbum?... 的地址")

    for u in urls:
        if "mp.weixin.qq.com" not in u:
            continue
        biz = qs_get(u, "__biz")
        album = qs_get(u, "album_id")
        if album and biz:
            return {"kind": "album", "biz": biz, "album_id": album, "url": u}
        path = urllib.parse.urlparse(u).path
        if path.startswith("/s/") or path.startswith("/s?") or "appmsg" in path or "mid=" in u:
            return {"kind": "article", "url": u}
    raise ValueError("链接里找不到 album_id / __biz，请确认这是公众号合集（合集/连载）页面的链接")


def sniff_album(html_text, page_url=""):
    """从一篇普通文章里反查它所属的合集。返回 (biz, album_id, 说明) 或 None。

    微信文章正文里会挂 appmsgalbum 链接（常被转义成 \x26amp; 形式），
    其中 from_msgid 等于本文 msgid 的那条就是"本文所在合集"，最可靠。
    """
    text = H.unescape(html_text or "").replace("\\x26amp;", "&")
    self_mid = None
    m = re.search(r"[?&]mid=(\d{6,})", page_url or "")
    if m:
        self_mid = m.group(1)
    if not self_mid:
        m = re.search(r'var\s+mid\s*=\s*["\']?(\d{6,})', text)
        if m:
            self_mid = m.group(1)

    groups = {}
    for m in re.finditer(r'album_id["\']?\s*[:=]\s*["\']?(\d{8,})', text):
        aid = m.group(1)
        seg = text[max(0, m.start() - 400): m.start() + 400]
        biz = re.search(r'__biz["\']?\s*[:=]\s*["\']?([A-Za-z0-9+/=]{8,})', seg)
        fm = re.search(r'from_msgid["\']?\s*[:=]\s*["\']?(\d{6,})', seg)
        g = groups.setdefault(aid, {"biz": "", "froms": set(), "n": 0})
        g["n"] += 1
        if biz:
            g["biz"] = biz.group(1)
        if fm:
            g["froms"].add(fm.group(1))

    if not groups:
        return None
    global_biz = ""
    mb = re.search(r'__biz["\']?\s*[:=]\s*["\']?([A-Za-z0-9+/=]{8,})', text)
    if mb:
        global_biz = mb.group(1)

    chosen, why = None, ""
    if self_mid:
        for aid, g in groups.items():
            if self_mid in g["froms"]:
                chosen, why = aid, "正文挂载的合集"
                break
    if not chosen:
        aid, g = max(groups.items(), key=lambda kv: kv[1]["n"])
        chosen = aid
        why = "页面中的合集" if len(groups) == 1 else f"页面有 {len(groups)} 个合集，按出现次数取"

    biz = groups[chosen]["biz"] or global_biz
    if not biz:
        return None
    return (biz, chosen, why)


# ----------------------------------------------------------------------------
# 合集目录
# ----------------------------------------------------------------------------
def parse_album_head(html_text):
    meta = {"title": "", "author": "", "desc": "", "cover": "", "count": 0}
    m = re.search(r'class="album__novel_label-title[^"]*"[^>]*>\s*(.{1,80}?)</', html_text, re.S)
    if not m:
        m = re.search(r'<div class="album__title"[^>]*>\s*(.{1,80}?)</', html_text, re.S)
    if m:
        meta["title"] = clean(H.unescape(re.sub(r"<[^>]+>", "", m.group(1))))
    if not meta["title"]:
        m = re.search(r'<meta property="og:title" content="([^"]{1,80})"', html_text)
        if m:
            meta["title"] = clean(H.unescape(m.group(1)))

    m = re.search(r'class="album__novel_authorname[^"]*"[^>]*>(.{0,200}?)</div>', html_text, re.S)
    if not m:
        m = re.search(r'id="js_name"[^>]*>(.{1,40}?)</', html_text, re.S)
    if not m:
        m = re.search(r'var nickname\s*=\s*htmlDecode\("([^"]{1,40})"\)', html_text)
    if m:
        meta["author"] = clean(H.unescape(re.sub(r"<[^>]+>", "", m.group(1))))

    m = re.search(r'class="[^"]*js_album_desc_content[^"]*"[^>]*>(.*?)</div>\s*</div>\s*</div>', html_text, re.S)
    if m:
        meta["desc"] = clean(re.sub(r"<[^>]+>", " ", m.group(1)))[:300]

    m = (re.search(r'id="js_header_novel_cover"[^>]*src="([^"]+)"', html_text)
         or re.search(r'id="js_header_cover"[^>]*src="([^"]+)"', html_text))
    if m:
        meta["cover"] = H.unescape(m.group(1))

    m = re.search(r"(\d+)\s*篇内容", html_text)
    if m:
        meta["count"] = int(m.group(1))
    return meta


def parse_album_items(html_text):
    """解析首屏 <li> 列表。"""
    items = []
    for m in re.finditer(r"<li[^>]*js_album_item[^>]*>", html_text):
        tag = m.group(0)

        def attr(name):
            mm = re.search(r'%s="([^"]*)"' % name, tag)
            return H.unescape(mm.group(1)) if mm else ""

        link = attr("data-link")
        if not link:
            continue
        items.append({
            "msgid": attr("data-msgid"),
            "itemidx": attr("data-itemidx") or "1",
            "title": clean(attr("data-title")),
            "url": link.replace("http://", "https://").split("#")[0],
            "pos": int(attr("data-pos_num") or 0),
            "ctime": int(attr("data-create_time") or 0),
        })
    return items


def fetch_album(f, biz, album_id, log):
    url = f"{ALBUM_API}?__biz={urllib.parse.quote(biz)}&action=getalbum&album_id={album_id}&scene=126&sessionid="
    html_text = f.retry_text(url, tries=3, log=log)
    if "环境异常" in html_text or "该内容已被发布者删除" in html_text:
        raise RuntimeError("微信返回了验证/异常页面，请稍后重试，或换网络（手机热点）再试")
    meta = parse_album_head(html_text)
    items = parse_album_items(html_text)
    log("info", f"首屏取到 {len(items)} 篇，开始翻页…")

    def page(begin=None):
        q = {"__biz": biz, "action": "getalbum", "album_id": album_id, "count": "10",
             "f": "json", "scene": "126", "sessionid": "", "subscene": ""}
        if begin:
            q["begin_msgid"] = begin["msgid"]
            q["begin_itemidx"] = begin["itemidx"]
        txt = f.retry_text(f"{ALBUM_API}?{urllib.parse.urlencode(q)}", tries=3, log=log)
        return json.loads(txt).get("getalbum_resp") or {}

    def take(resp, seen):
        new = 0
        for a in resp.get("article_list") or []:
            key = (str(a.get("msgid", "")), str(a.get("itemidx", "1")))
            if key in seen or not key[0]:
                continue
            seen.add(key)
            new += 1
            items.append({
                "msgid": key[0], "itemidx": key[1],
                "title": clean(a.get("title") or ""),
                "url": (a.get("url") or "").replace("http://", "https://").split("#")[0],
                "pos": int(a.get("pos_num") or 0),
                "ctime": int(a.get("create_time") or 0),
            })
        return new

    seen = {(i["msgid"], i["itemidx"]) for i in items}
    # 首屏列表有时是 JS 渲染出来的（HTML 里没有 li），改用接口取第一批
    if not items:
        log("warn", "首屏没有列表，改用接口获取…")
        try:
            n = take(page(), seen)
            log("info", f"接口首批 {n} 篇")
        except Exception as e:
            log("warn", f"接口也失败：{e}")

    guard = 0
    while items and guard < 400:
        guard += 1
        last = items[-1]
        if not last["msgid"]:
            break
        try:
            resp = page(last)
        except Exception as e:
            log("warn", f"翻页失败，停止翻页：{e}")
            break
        new = take(resp, seen)
        log("info", f"  第 {guard} 页 +{new} 篇，累计 {len(items)} 篇")
        if str(resp.get("continue_flag")) != "1" or new == 0:
            break
        time.sleep(0.35 + random.random() * 0.3)
        if len(items) > MAX_ARTICLES:
            break

    # 排序：合集内序号升序；缺序号时按发布时间
    if all(i["pos"] > 0 for i in items):
        items.sort(key=lambda x: x["pos"])
    else:
        items.sort(key=lambda x: (x["ctime"] or 0))
    for n, it in enumerate(items, 1):
        it["no"] = n
        it["key"] = f'{it["msgid"]}_{it["itemidx"]}'
    if meta["count"] and meta["count"] != len(items):
        log("warn", f"页面显示 {meta['count']} 篇，实际抓到 {len(items)} 篇")
    return meta, items


# ----------------------------------------------------------------------------
# 正文提取
# ----------------------------------------------------------------------------
class ContentParser(HTMLParser):
    BLOCK = {"p", "section", "div", "br", "h1", "h2", "h3", "h4", "h5", "h6", "li",
             "blockquote", "tr", "figure", "figcaption", "article", "header", "footer"}
    SKIP = {"script", "style", "svg", "iframe", "video", "audio", "mpvoice",
            "mp-common-videosnap", "mp-common-mpaudio", "qqmusic", "mpprofile", "mp-style-type"}

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.on = False
        self.depth = 0
        self.skip = 0
        self.blocks = []
        self.buf = []

    def flush(self):
        t = "".join(self.buf)
        for ch in ZW:
            t = t.replace(ch, "")
        t = re.sub(r"\s+", " ", t).strip()
        if t and t not in ("\u00a0",):
            self.blocks.append(("p", t))
        self.buf = []

    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        if not self.on:
            if a.get("id") == "js_content":
                self.on = True
                self.depth = 1
            return
        if tag in self.SKIP:
            self.skip += 1
            return
        if self.skip:
            return
        if tag == "img":
            self.flush()
            src = (a.get("data-src") or a.get("src") or "").strip()
            try:
                w = int(a.get("data-w") or 0)
            except Exception:
                w = 0
            if src.startswith("http") and (w == 0 or w >= 80):
                self.blocks.append(("img", src))
            return
        if tag in ("h1", "h2", "h3", "h4"):
            self.flush()
        if tag in self.BLOCK:
            self.flush()
        self.depth += 1

    def handle_startendtag(self, tag, attrs):
        self.handle_starttag(tag, attrs)

    def handle_endtag(self, tag):
        if not self.on:
            return
        if tag in self.SKIP:
            if self.skip:
                self.skip -= 1
            return
        if self.skip:
            return
        if tag in self.BLOCK or tag in ("h1", "h2", "h3", "h4"):
            self.flush()
        self.depth -= 1
        if self.depth <= 0:
            self.on = False
            self.flush()

    def handle_data(self, d):
        if self.on and not self.skip:
            self.buf.append(d)


def strip_noise(blocks, title=""):
    """去掉微信浮层 UI 文案、作者推广段、开头引导语、结尾推广、与标题重复的首段。"""
    out = []
    for kind, val in blocks:
        if kind == "p":
            t = val.strip()
            if not t or is_ui_noise(t) or is_promo_line(t):
                continue
        out.append((kind, val))
    # 尾部推广（PS 段已在上一步剔除，这里再兜一层）
    for _ in range(6):
        if out and out[-1][0] == "p" and len(out[-1][1]) <= 160 and PROMO.search(out[-1][1]):
            out.pop()
        else:
            break
    # 头部引导语（最多 3 段）
    for _ in range(3):
        if out and out[0][0] == "p" and len(out[0][1]) <= 90 and PROMO.search(out[0][1]):
            out.pop(0)
        else:
            break
    # 第一段与标题重复
    norm = lambda x: re.sub(r"[\s《》「」『』·:：、，,。.\-—！!？?]+", "", x or "")
    while out and out[0][0] == "p" and norm(out[0][1]) and norm(out[0][1]) in norm(title):
        out.pop(0)
    return out


def parse_article_html(html_text):
    title = ""
    m = re.search(r'<h1[^>]*id="activity-name"[^>]*>(.*?)</h1>', html_text, re.S)
    if m:
        title = clean(re.sub(r"<[^>]+>", "", m.group(1)))
    if not title:
        m = re.search(r'<meta property="og:title" content="([^"]*)"', html_text)
        if m:
            title = clean(H.unescape(m.group(1)))

    account = ""
    m = re.search(r'var nickname\s*=\s*htmlDecode\("([^"]*)"\)', html_text)
    if m:
        account = clean(H.unescape(m.group(1)))
    if not account:
        m = re.search(r'id="js_name"[^>]*>\s*([^<]+)', html_text)
        if m:
            account = clean(m.group(1))

    pub = ""
    m = re.search(r'id="publish_time"[^>]*>\s*([0-9]{4}-[0-9]{2}-[0-9]{2})', html_text)
    if m:
        pub = m.group(1)
    else:
        m = re.search(r'var ct\s*=\s*"(\d{9,11})"', html_text)
        if m:
            try:
                pub = datetime.datetime.fromtimestamp(int(m.group(1))).strftime("%Y-%m-%d")
            except Exception:
                pub = ""

    p = ContentParser()
    try:
        p.feed(html_text)
    except Exception:
        pass
    return {"title": title, "account": account, "publish": pub, "blocks": p.blocks}


def article_chars(blocks):
    return sum(len(v) for k, v in blocks if k == "p")


# 章节标题：正文首行是否是章节名
CHAP_HEAD_RE = re.compile(r"^第\s*[0-9零一二三四五六七八九十百千两]+\s*[章回节篇卷]")
# 标题里任意位置出现「第N章」即视为已带编号（如「《xxx》第五章 收徒」），不再补序号
CHAP_ANY_RE = re.compile(r"第\s*[0-9零一二三四五六七八九十百千两]+\s*[章回节篇卷]")


def pick_title(article_title, blocks, mode="auto"):
    """决定章节标题。返回 (标题, 需要从正文里去掉的块下标 或 None)。

    mode: auto  = 正文首行像章节名就用它，否则用文章标题
          first = 一律用正文第一行
          article = 一律用文章标题
    """
    idx, first = None, ""
    for i, (k, v) in enumerate(blocks[:8]):
        if k == "p" and v.strip():
            idx, first = i, v.strip()
            break
    article_title = clean(article_title)
    if not first:
        return article_title, None
    if mode == "article":
        return article_title, None
    if mode == "first":
        return first[:60], idx
    # auto：首行是“第N章 …”，或文章标题只是书名+流水号（如 xxx0001）
    if CHAP_HEAD_RE.match(first) and len(first) <= 40:
        return first, idx
    if re.search(r"\d{3,}\s*$", article_title) and len(first) <= 40 and not first.endswith("。"):
        return first, idx
    return article_title, None


def drop_cross_titles(chapters, log=None):
    """删掉文末重复贴出的章节标题（公众号文章常在结尾再放一遍标题/系列目录）。"""
    norm = lambda x: re.sub(r"[\s《》「」『』·:：、，,。.\-—！!？?()（）【】]+", "", x or "")
    raws = {norm(c.get("raw") or "") for c in chapters}
    raws = {r for r in raws if len(r) >= 6}
    if not raws:
        return 0
    dropped = 0
    for ch in chapters:
        # 末尾：重复贴出的标题 / 往期导航
        for _ in range(3):
            if not ch["blocks"] or ch["blocks"][-1][0] != "p":
                break
            v = ch["blocks"][-1][1]
            nv = norm(v)
            if len(v) > 80 or len(nv) < 6:
                break
            hit = nv in raws or any(r in nv for r in raws) or any(nv in r for r in raws if len(nv) >= 8)
            if not hit:
                break
            ch["blocks"].pop()
            dropped += 1
        # 开头：顶部"上一篇"跳转卡片留下的标题
        for _ in range(2):
            if not ch["blocks"] or ch["blocks"][0][0] != "p":
                break
            v = ch["blocks"][0][1]
            nv = norm(v)
            if len(v) > 80 or len(nv) < 6:
                break
            if nv not in raws and not any(nv in r for r in raws if len(nv) >= 8):
                break
            ch["blocks"].pop(0)
            dropped += 1
    if log and dropped:
        log("info", f"已剔除 {dropped} 处文末重复的标题段")
    return dropped


def drop_tail_signature(chapters, names, log=None):
    """删掉文末又写一遍的书名/合集名签名（公众号常用做法）。"""
    ns = {re.sub(r"\s+", "", n) for n in names if n}
    ns = {n for n in ns if len(n) >= 3}
    if not ns:
        return 0
    dropped = 0
    for ch in chapters:
        for _ in range(2):
            if not ch["blocks"] or ch["blocks"][-1][0] != "p":
                break
            if re.sub(r"\s+", "", ch["blocks"][-1][1]) in ns:
                ch["blocks"].pop()
                dropped += 1
            else:
                break
    if log and dropped:
        log("info", f"已剔除 {dropped} 处书名签名段")
    return dropped


def drop_template_images(chapters, log=None):
    """删掉每章开头重复出现的模板头图（同一张图出现在大量章节开头）。"""
    if len(chapters) < 3:
        return 0
    cnt = {}
    for ch in chapters:
        seen = set()
        for k, v in ch["blocks"][:4]:
            if k == "img" and v not in seen:
                seen.add(v)
                cnt[v] = cnt.get(v, 0) + 1
    threshold = max(3, int(len(chapters) * 0.3))
    rep = {u for u, c in cnt.items() if c >= threshold}
    if not rep:
        return 0
    dropped = 0
    for ch in chapters:
        newb, head = [], True
        for k, v in ch["blocks"]:
            if head and k == "img" and v in rep:
                dropped += 1
                continue
            if k == "p":
                head = False
            newb.append((k, v))
        ch["blocks"] = newb
    if log and dropped:
        log("info", f"已剔除 {dropped} 处重复的模板头图")
    return dropped


def is_blocked(html_text):
    if len(html_text) < 3000:
        return True
    for kw in ("环境异常", "请在微信客户端打开", "该内容已被发布者删除", "此内容因违规无法查看",
               "参数错误", "访问过于频繁", "verify_code"):
        if kw in html_text and "js_content" not in html_text:
            return True
    return False


def fetch_article(f, art, cache_path, use_cache, log):
    if use_cache and cache_path.exists():
        try:
            obj = json.loads(cache_path.read_text(encoding="utf-8"))
            if obj.get("blocks"):
                obj["blocks"] = strip_noise(obj["blocks"], obj.get("title"))
                return obj, True
        except Exception:
            pass
    html_text = f.retry_text(art["url"], tries=3, log=log)
    if is_blocked(html_text):
        raise RuntimeError("页面被拦截或文章不可见（可能需要验证）")
    obj = parse_article_html(html_text)
    obj["blocks"] = strip_noise(obj["blocks"], obj["title"])
    if not obj["blocks"]:
        raise RuntimeError("没解析到正文（可能是视频/图片型文章）")
    obj["url"] = art["url"]
    obj["msgid"] = art["msgid"]
    try:
        cache_path.parent.mkdir(parents=True, exist_ok=True)
        cache_path.write_text(json.dumps(obj, ensure_ascii=False), encoding="utf-8")
    except Exception:
        pass
    return obj, False


# ----------------------------------------------------------------------------
# 封面
# ----------------------------------------------------------------------------
FONT_CANDIDATES = [r"C:\Windows\Fonts\msyhbd.ttc", r"C:\Windows\Fonts\msyh.ttc",
                   r"C:\Windows\Fonts\simhei.ttf", r"C:\Windows\Fonts\simsun.ttc",
                   "/System/Library/Fonts/PingFang.ttc", "/usr/share/fonts/opentype/noto/NotoSerifCJK-Bold.ttc"]


def load_font(size, bold=False):
    if not HAS_PIL:
        return None
    cands = ([r"C:\Windows\Fonts\msyhbd.ttc"] if bold else []) + FONT_CANDIDATES
    for p in cands:
        if os.path.exists(p):
            try:
                return ImageFont.truetype(p, size)
            except Exception:
                continue
    try:
        return ImageFont.load_default()
    except Exception:
        return None


def make_cover(title, author, count, out_path):
    if not HAS_PIL:
        return False
    W, Hh = 900, 1350
    img = Image.new("RGB", (W, Hh), (23, 40, 44))
    d = ImageDraw.Draw(img)
    # 竖向渐变
    top, bot = (18, 46, 50), (8, 22, 28)
    for y in range(Hh):
        t = y / Hh
        d.line([(0, y), (W, y)], fill=(int(top[0] + (bot[0] - top[0]) * t),
                                       int(top[1] + (bot[1] - top[1]) * t),
                                       int(top[2] + (bot[2] - top[2]) * t)))
    # 装饰
    d.rectangle([46, 46, W - 46, Hh - 46], outline=(198, 166, 100), width=3)
    d.rectangle([60, 60, W - 60, Hh - 60], outline=(198, 166, 100, 90), width=1)
    d.text((W // 2, 118), "微信 · 公众号合集", font=load_font(30), fill=(200, 176, 120), anchor="ma")

    # 标题自动折行
    title = (title or "未命名合集").strip()
    size = 90
    lines = []
    while size >= 44:
        f = load_font(size, bold=True)
        maxw = W - 200
        lines, cur = [], ""
        for ch in title:
            if ch == "\n":
                lines.append(cur); cur = ""; continue
            if d.textlength(cur + ch, font=f) > maxw and cur:
                lines.append(cur); cur = ch
            else:
                cur += ch
        if cur:
            lines.append(cur)
        if len(lines) <= 4:
            break
        size -= 6
    f = load_font(size, bold=True)
    total_h = len(lines) * int(size * 1.42)
    y = (Hh - total_h) // 2 - 40
    for line in lines:
        d.text((W // 2, y), line, font=f, fill=(245, 242, 233), anchor="ma")
        y += int(size * 1.42)

    d.line([(W // 2 - 90, y + 40), (W // 2 + 90, y + 40)], fill=(198, 166, 100), width=2)
    d.text((W // 2, y + 66), author or "微信公众号", font=load_font(38), fill=(206, 214, 210), anchor="ma")
    d.text((W // 2, Hh - 140), f"共 {count} 篇", font=load_font(30), fill=(150, 162, 160), anchor="ma")
    try:
        img.save(out_path, "JPEG", quality=90)
    except Exception:
        try:
            img.save(str(out_path))
        except Exception:
            return False
    return True


# ----------------------------------------------------------------------------
# EPUB
# ----------------------------------------------------------------------------
CSS = """@charset "utf-8";
html,body{margin:0;padding:0;}
body{font-family:"Songti SC","Noto Serif CJK SC","Source Han Serif SC",serif;
     line-height:1.85;color:#1d1d1f;padding:0.6em 1em;}
h1{font-size:1.45em;line-height:1.5;text-align:center;margin:1.1em 0 0.4em;font-weight:600;}
h2{font-size:1.12em;margin:1.5em 0 0.7em;font-weight:600;}
p{margin:0 0 0.85em;text-indent:2em;text-align:justify;word-wrap:break-word;}
.meta{color:#8a8f94;font-size:0.82em;text-align:center;text-indent:0;margin:0 0 2.2em;
      letter-spacing:0.04em;}
.fig{margin:1.2em 0;text-align:center;text-indent:0;}
.fig img{max-width:100%;height:auto;}
.cover-page{text-align:center;padding:0;margin:0;}
.cover-page img{max-width:100%;height:auto;}
.title-page{text-align:center;padding-top:22%;}
.title-page h1{font-size:2em;}
.title-page .by{color:#666;font-size:1.05em;margin-top:1.2em;}
"""


def chapter_xhtml(title, blocks, imgmap):
    body = []
    for kind, val in blocks:
        if kind == "img":
            local = imgmap.get(val)
            if local:
                body.append(f'<div class="fig"><img src="../Images/{local}" alt=""/></div>')
        elif kind == "h":
            body.append(f"<h2>{esc(val)}</h2>")
        else:
            body.append(f"<p>{esc(val)}</p>")
    return f"""<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="zh-CN" lang="zh-CN">
<head><meta charset="utf-8"/><title>{esc(title)}</title>
<link rel="stylesheet" type="text/css" href="../Styles/style.css"/></head>
<body>
<h1 class="chapter-title">{esc(title)}</h1>
{chr(10).join(body)}
</body>
</html>"""


class ImageStore:
    """下载 + 压缩插图，按 URL 去重，并落盘缓存（重复生成同一本书时秒出）。"""

    def __init__(self, f, cache_dir, log):
        self.f = f
        self.cache_dir = cache_dir
        self.log = log
        try:
            self.cache_dir.mkdir(parents=True, exist_ok=True)
        except Exception:
            self.cache_dir = APP_DIR / "cache" / "_img"
            self.cache_dir.mkdir(parents=True, exist_ok=True)
        self.map = {}
        self.items = []          # (local_name, bytes)
        self.total = 0
        self.failed = 0

    def add(self, url):
        if not url or url in self.map:
            return self.map.get(url)
        if self.total > 120 * 1024 * 1024:
            return None
        key = hashlib.sha1(url.encode("utf-8")).hexdigest()[:20]
        payload, ext = None, "jpg"
        hit = list(self.cache_dir.glob(key + ".*"))
        if hit:
            try:
                payload = hit[0].read_bytes()
                ext = hit[0].suffix.lstrip(".") or "jpg"
            except Exception:
                payload = None
        if payload is None:
            try:
                data, ctype, _ = self.f.raw(url, referer="https://mp.weixin.qq.com/", timeout=40)
            except Exception:
                self.failed += 1
                return None
            if not data or len(data) < 512:
                self.failed += 1
                return None
            payload = data
            if HAS_PIL:
                try:
                    im = Image.open(io.BytesIO(data))
                    if im.mode in ("P", "RGBA", "LA"):
                        rgba = im.convert("RGBA")
                        bg = Image.new("RGB", rgba.size, (255, 255, 255))
                        bg.paste(rgba, mask=rgba.split()[-1])
                        im = bg
                    elif im.mode != "RGB":
                        im = im.convert("RGB")
                    if im.width > 1000:
                        im = im.resize((1000, max(1, int(im.height * 1000 / im.width))), Image.LANCZOS)
                    buf = io.BytesIO()
                    im.save(buf, "JPEG", quality=84, optimize=True)
                    payload = buf.getvalue()
                    ext = "jpg"
                except Exception:
                    payload = data
                    ext = "png" if "png" in (ctype or "") else "jpg"
            try:
                (self.cache_dir / f"{key}.{ext}").write_bytes(payload)
            except Exception:
                pass
        name = f"img{len(self.items)+1:04d}.{ext}"
        self.items.append((name, payload))
        self.map[url] = name
        self.total += len(payload)
        return name


def build_epub(album, chapters, opts, out_dir, log):
    """chapters: [{'title','blocks','no','account','publish'}]"""
    title = opts.get("title") or album.get("title") or "公众号合集"
    author = opts.get("author") or album.get("author") or ""
    total = len(chapters)
    log("info", f"开始打包 EPUB：{title}（{total} 篇）")
    drop_template_images(chapters, log)
    drop_cross_titles(chapters, log)
    drop_tail_signature(chapters, [album.get("title"), title], log)

    images = (ImageStore(opts["fetcher"], CACHE_DIR / str(album.get("album_id") or "single") / "img", log)
              if opts.get("images") else None)
    imgmap_all = {}
    if images:
        uniq = []
        for ch in chapters:
            for k, v in ch["blocks"]:
                if k == "img" and v not in uniq:
                    uniq.append(v)
        log("info", f"共 {len(uniq)} 张插图，开始下载…")
        for i, u in enumerate(uniq, 1):
            images.add(u)
            if i % 10 == 0:
                log("info", f"  图片 {i}/{len(uniq)}")
            if opts.get("cancel"):
                break
        imgmap_all = images.map
        log("info", f"图片完成：{len(images.items)} 张，失败 {images.failed}")

    # 封面：优先用合集自带的封面图（原书封面），拿不到再用本地生成的标题封面
    cover_bytes = None
    cov_url = (album.get("cover") or "").strip()
    if cov_url and opts.get("fetcher"):
        try:
            data, _ct, _st = opts["fetcher"].raw(cov_url, referer="https://mp.weixin.qq.com/", timeout=40)
            if data and len(data) > 2048:
                if HAS_PIL:
                    try:
                        im = Image.open(io.BytesIO(data))
                        if im.mode != "RGB":
                            im = im.convert("RGB")
                        buf = io.BytesIO()
                        im.save(buf, "JPEG", quality=90, optimize=True)
                        cover_bytes = buf.getvalue()
                    except Exception:
                        cover_bytes = data
                else:
                    cover_bytes = data
                log("info", "封面：使用合集原封面")
        except Exception:
            cover_bytes = None
    if not cover_bytes:
        try:
            tmp = OUT_DIR / f".cover_{uuid.uuid4().hex}.jpg"
            if make_cover(title, author, total, tmp):
                cover_bytes = tmp.read_bytes()
                tmp.unlink(missing_ok=True)
                log("info", "封面：已生成标题封面")
        except Exception:
            cover_bytes = None

    uid = "urn:uuid:" + str(uuid.uuid5(uuid.NAMESPACE_URL, f'wxalbum/{album.get("album_id")}/{title}'))
    now = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")

    manifest, spine, navli, ncx = [], [], [], []
    files = {}

    # 封面页
    if cover_bytes:
        files["Images/cover.jpg"] = cover_bytes
        manifest += ['<item id="cover-img" href="Images/cover.jpg" media-type="image/jpeg" properties="cover-image"/>',
                     '<item id="coverpage" href="Text/cover.xhtml" media-type="application/xhtml+xml"/>']
        spine.append('<itemref idref="coverpage" linear="yes"/>')
        files["Text/cover.xhtml"] = f"""<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="zh-CN" lang="zh-CN">
<head><meta charset="utf-8"/><title>封面</title>
<link rel="stylesheet" type="text/css" href="../Styles/style.css"/></head>
<body class="cover-page"><div class="cover-page"><img src="../Images/cover.jpg" alt="{esc(title)}"/></div></body>
</html>"""

    files["Text/titlepage.xhtml"] = f"""<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="zh-CN" lang="zh-CN">
<head><meta charset="utf-8"/><title>{esc(title)}</title>
<link rel="stylesheet" type="text/css" href="../Styles/style.css"/></head>
<body><div class="title-page"><h1>{esc(title)}</h1>
<p class="by">{esc(author)}</p></div></body></html>"""
    manifest.append('<item id="titlepage" href="Text/titlepage.xhtml" media-type="application/xhtml+xml"/>')
    spine.append('<itemref idref="titlepage"/>')

    used_imgs = set()
    for i, ch in enumerate(chapters, 1):
        cid = f"chap{i:04d}"
        href = f"Text/{cid}.xhtml"
        sub = {}
        for k, v in ch["blocks"]:
            if k == "img" and imgmap_all.get(v):
                sub[v] = imgmap_all[v]
                used_imgs.add(imgmap_all[v])
        files[href] = chapter_xhtml(ch["title"], ch["blocks"], sub)
        manifest.append(f'<item id="{cid}" href="{href}" media-type="application/xhtml+xml"/>')
        spine.append(f'<itemref idref="{cid}"/>')
        navli.append(f'<li><a href="{href}">{esc(ch["title"])}</a></li>')
        ncx.append(f'<navPoint id="np{i}" playOrder="{i}"><navLabel><text>{esc(ch["title"])}</text></navLabel>'
                   f'<content src="{href}"/></navPoint>')

    if images:
        for name, payload in images.items:
            if name not in used_imgs:
                continue
            mt = "image/png" if name.lower().endswith(".png") else "image/jpeg"
            manifest.append(f'<item id="i_{name.replace(".","_")}" href="Images/{name}" media-type="{mt}"/>')
            files[f"Images/{name}"] = payload

    files["Styles/style.css"] = CSS
    files["nav.xhtml"] = f"""<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="zh-CN" lang="zh-CN">
<head><meta charset="utf-8"/><title>目录</title>
<link rel="stylesheet" type="text/css" href="Styles/style.css"/></head>
<body><nav epub:type="toc" id="toc"><h1>目录</h1><ol>
{chr(10).join(navli)}
</ol></nav></body></html>"""
    manifest.append('<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>')

    files["toc.ncx"] = f"""<?xml version="1.0" encoding="utf-8"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
<head><meta name="dtb:uid" content="{uid}"/><meta name="dtb:depth" content="1"/>
<meta name="dtb:totalPageCount" content="0"/><meta name="dtb:maxPageNumber" content="0"/></head>
<docTitle><text>{esc(title)}</text></docTitle><navMap>
{chr(10).join(ncx)}
</navMap></ncx>"""
    manifest.append('<item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>')
    manifest.append('<item id="css" href="Styles/style.css" media-type="text/css"/>')

    desc_bits = [f"来源：微信公众号「{author or '未知'}」合集《{album.get('title') or title}》，共 {total} 篇。"]
    if album.get("desc"):
        desc_bits.append(album["desc"])
    if album.get("url"):
        desc_bits.append(album["url"])
    description = " ".join(desc_bits)

    files["content.opf"] = f"""<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid" xml:lang="zh-CN">
<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
<dc:identifier id="bookid">{uid}</dc:identifier>
<dc:title>{esc(title)}</dc:title>
<dc:creator>{esc(author)}</dc:creator>
<dc:publisher>{esc(author)}</dc:publisher>
<dc:language>zh-CN</dc:language>
<dc:date>{now[:10]}</dc:date>
<dc:description>{esc(description)}</dc:description>
<dc:source>{esc(album.get('url') or '')}</dc:source>
<meta name="cover" content="cover-img"/>
<meta property="dcterms:modified">{now}</meta>
</metadata>
<manifest>
{chr(10).join(manifest)}
</manifest>
<spine toc="ncx">
{chr(10).join(spine)}
</spine>
</package>"""

    out_dir.mkdir(parents=True, exist_ok=True)
    target = opts.get("target_path")
    if target:
        # 追踪更新：直接覆盖原文件，旧版先备份到 _history
        out_path = Path(target)
        out_path.parent.mkdir(parents=True, exist_ok=True)
        if out_path.exists():
            hist = out_path.parent / "_history"
            hist.mkdir(parents=True, exist_ok=True)
            ts = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
            try:
                shutil.copy2(out_path, hist / f"{out_path.stem}__{ts}.epub")
                backups = sorted(hist.glob(f"{out_path.stem}__*.epub"))
                for old_b in backups[:-3]:
                    old_b.unlink(missing_ok=True)
                log("info", f"旧版已备份到 _history（保留最近 3 份）")
            except Exception as e:
                log("warn", f"备份旧版失败：{e}")
    else:
        out_path = out_dir / f"{safe_name(title)}.epub"
        n = 2
        while out_path.exists():
            out_path = out_dir / f"{safe_name(title)} ({n}).epub"
            n += 1

    with zipfile.ZipFile(out_path, "w", zipfile.ZIP_DEFLATED) as z:
        zi = zipfile.ZipInfo("mimetype", date_time=(2020, 1, 1, 0, 0, 0))
        zi.compress_type = zipfile.ZIP_STORED
        z.writestr(zi, "application/epub+zip")
        z.writestr("META-INF/container.xml", """<?xml version="1.0" encoding="utf-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
<rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>""")
        for name, content in files.items():
            data = content.encode("utf-8") if isinstance(content, str) else content
            z.writestr("OEBPS/" + name, data)

    size = out_path.stat().st_size
    log("ok", f"已生成：{out_path.name}（{size/1024/1024:.1f} MB，{total} 篇）")
    return {"path": str(out_path), "name": out_path.name, "size": size,
            "chapters": total, "images": len(used_imgs),
            "chars": sum(article_chars(c["blocks"]) for c in chapters)}


# ----------------------------------------------------------------------------
# 连载追踪
# ----------------------------------------------------------------------------
WATCH_PATH = APP_DIR / "watch.json"
WATCH = {"items": []}
WATCH_LOCK = threading.Lock()
WSTATE = {"checking": False, "results": {}, "checked_at": ""}


def load_watch():
    try:
        if WATCH_PATH.exists():
            data = json.loads(WATCH_PATH.read_text(encoding="utf-8"))
            WATCH["items"] = data.get("items") or []
    except Exception:
        WATCH["items"] = []


def save_watch():
    try:
        WATCH_PATH.write_text(json.dumps(WATCH, ensure_ascii=False, indent=1), encoding="utf-8")
    except Exception as e:
        print(f"[warn] 保存追踪列表失败: {e}", flush=True)


def watch_items():
    with WATCH_LOCK:
        return [dict(x) for x in WATCH["items"]]


def watch_get(album_id):
    with WATCH_LOCK:
        for it in WATCH["items"]:
            if str(it.get("album_id")) == str(album_id):
                return dict(it)
    return None


def watch_upsert(entry):
    with WATCH_LOCK:
        for i, it in enumerate(WATCH["items"]):
            if str(it.get("album_id")) == str(entry.get("album_id")):
                merged = {**it, **entry}
                WATCH["items"][i] = merged
                break
        else:
            WATCH["items"].append(entry)
        WATCH["items"].sort(key=lambda x: x.get("last_build") or "", reverse=True)
        save_watch()


def watch_remove(album_id):
    with WATCH_LOCK:
        WATCH["items"] = [x for x in WATCH["items"] if str(x.get("album_id")) != str(album_id)]
        save_watch()


def watch_state_payload():
    items = []
    for it in watch_items():
        aid = str(it.get("album_id"))
        r = WSTATE["results"].get(aid) or {}
        items.append({
            "album_id": aid,
            "title": it.get("book_title") or it.get("album_title") or aid,
            "album_title": it.get("album_title") or "",
            "author": it.get("author") or "",
            "count": it.get("count") or 0,
            "epub": it.get("epub") or "",
            "cover": it.get("cover") or "",
            "images": bool(it.get("images")),
            "last_build": it.get("last_build") or "",
            "last_check": it.get("last_check") or "",
            "new": r.get("new"),
            "removed": r.get("removed"),
            "total_now": r.get("total"),
            "error": r.get("error") or "",
        })
    return {"items": items, "checking": WSTATE["checking"], "checked_at": WSTATE["checked_at"]}


def check_watch_one(item, f=None):
    """拉一次合集目录，和上次记录的章节集合比对。"""
    f = f or Fetcher()
    meta, items = fetch_album(f, item["biz"], item["album_id"], lambda *a: None)
    cur = {x["key"] for x in items}
    old = set(item.get("keys") or [])
    res = {"total": len(items), "new": len(cur - old), "removed": len(old - cur),
           "meta": meta, "items": items, "error": ""}
    return res


def do_check_watch(album_ids=None):
    if WSTATE["checking"]:
        return
    WSTATE["checking"] = True
    try:
        f = Fetcher()
        targets = [it for it in watch_items()
                   if not album_ids or str(it.get("album_id")) in {str(a) for a in album_ids}]
        log("info", f"开始检查 {len(targets)} 个追踪合集…")
        for it in targets:
            aid = str(it["album_id"])
            try:
                r = check_watch_one(it, f)
                WSTATE["results"][aid] = {"new": r["new"], "removed": r["removed"],
                                          "total": r["total"], "error": ""}
                watch_upsert({"album_id": aid, "last_check": datetime.datetime.now().strftime("%Y-%m-%d %H:%M")})
                if r["new"]:
                    log("ok", f"  《{it.get('book_title')}》有 {r['new']} 篇更新（现共 {r['total']} 篇）")
                elif r["removed"]:
                    log("warn", f"  《{it.get('book_title')}》有 {r['removed']} 篇被移除（现共 {r['total']} 篇）")
                else:
                    log("info", f"  《{it.get('book_title')}》已是最新（{r['total']} 篇）")
            except Exception as e:
                WSTATE["results"][aid] = {"new": None, "removed": None, "total": None, "error": str(e)}
                log("err", f"  《{it.get('book_title')}》检查失败：{e}")
            time.sleep(0.4)
        WSTATE["checked_at"] = datetime.datetime.now().strftime("%Y-%m-%d %H:%M")
    finally:
        WSTATE["checking"] = False
        set_phase(STATE["phase"])


def should_auto_check(minutes=30):
    ts = [it.get("last_check") for it in watch_items() if it.get("last_check")]
    if not ts:
        return True
    try:
        latest = datetime.datetime.strptime(max(ts), "%Y-%m-%d %H:%M")
        return (datetime.datetime.now() - latest).total_seconds() > minutes * 60
    except Exception:
        return True


def album_url(biz, album_id):
    return f"{ALBUM_API}?__biz={urllib.parse.quote(biz or '')}&action=getalbum&album_id={album_id}&scene=126&sessionid="


def do_update_watch(album_id):
    """增量更新：重新拉目录 → 正文只抓新增（旧的走缓存）→ 覆盖原 EPUB。"""
    item = watch_get(album_id)
    if not item:
        set_phase("error", "追踪列表里没有这个合集")
        return
    if STATE["phase"] in ("parsing", "fetching", "building"):
        set_phase("error", "有任务正在进行，请稍后再试")
        return
    log("info", f"开始更新《{item.get('book_title')}》…")
    do_parse(album_url(item.get("biz"), album_id))
    if STATE["phase"] != "ready":
        return
    opt = item.get("opts") or {}
    keys = {a["key"] for a in STATE["articles"]}
    do_build(keys, {
        "delay": 0.6,
        "images": bool(item.get("images")),
        "numbering": opt.get("numbering", True),
        "titlemode": opt.get("titlemode", "auto"),
        "concurrency": int(opt.get("concurrency") or 1),
        "cache": True,
        "title": item.get("book_title") or "",
        "author": item.get("author") or "",
        "target_path": item.get("epub") or "",
        "outdir": str(OUT_DIR),
    })


# ----------------------------------------------------------------------------
# 任务状态
# ----------------------------------------------------------------------------
LOCK = threading.Lock()
STATE = {
    "phase": "idle",       # idle | parsing | ready | fetching | building | done | error | cancelled
    "message": "",
    "album": None,
    "articles": [],
    "progress": {"total": 0, "done": 0, "ok": 0, "fail": 0, "current": "", "chars": 0},
    "result": None,
    "log": [],
    "listId": 0,
    "rev": 0,
}
CTRL = {"cancel": False}


def log(level, msg):
    with LOCK:
        STATE["log"].append({"t": now_str(), "lv": level, "m": msg})
        if len(STATE["log"]) > 500:
            del STATE["log"][:200]
        STATE["rev"] += 1
    print(f"[{now_str()}] {msg}", flush=True)


def set_phase(phase, msg=""):
    with LOCK:
        STATE["phase"] = phase
        if msg:
            STATE["message"] = msg
        STATE["rev"] += 1


def snapshot():
    with LOCK:
        return json.loads(json.dumps({
            "phase": STATE["phase"], "message": STATE["message"], "album": STATE["album"],
            "articles": STATE["articles"], "progress": STATE["progress"],
            "result": STATE["result"], "log": STATE["log"][-160:], "rev": STATE["rev"],
            "listId": STATE.get("listId", 0),
            "watch": watch_state_payload(),
        }, ensure_ascii=False))


def do_parse(raw_input, expand=True):
    CTRL["cancel"] = False
    with LOCK:
        STATE.update({"album": None, "articles": [], "result": None, "log": [],
                      "progress": {"total": 0, "done": 0, "ok": 0, "fail": 0, "current": "", "chars": 0}})
        STATE["rev"] += 1
    set_phase("parsing", "正在解析链接…")
    try:
        tgt = parse_input(raw_input)
        f = Fetcher()
        if tgt["kind"] == "article":
            log("info", "识别为单篇文章，正在抓取并反查所属合集…")
            html_text = f.retry_text(tgt["url"], log=log)
            obj = parse_article_html(html_text)
            found = sniff_album(html_text, tgt["url"]) if expand else None
            if found:
                biz, aid, why = found
                log("info", f"发现所属合集 album_id={aid}（{why}），开始加载整本…")
                meta, items = fetch_album(f, biz, aid, log)
                if meta.get("title"):
                    album = dict(meta, album_id=aid, biz=biz,
                                 url=f"{ALBUM_API}?__biz={urllib.parse.quote(biz)}&action=getalbum&album_id={aid}")
                    cur_mid = None
                    mm = re.search(r"[?&]mid=(\d{6,})", tgt["url"])
                    if mm:
                        cur_mid = mm.group(1)
                    if not cur_mid:                       # 短链 /s/xxxx 里没有 mid，从页面里挖
                        mm = re.search(r'var\s+mid\s*=\s*["\']?(\d{6,})', html_text)
                        if mm:
                            cur_mid = mm.group(1)
                    arts = []
                    for it in items:
                        d = dict(it, status="pending", chars=0)
                        if cur_mid and it["msgid"] == cur_mid:
                            d["current"] = True
                        arts.append(d)
                    log("ok", f"已扩展为合集《{album['title']}》，共 {len(arts)} 篇")
                else:
                    found = None
            if not found:
                log("info", "未找到所属合集，按单篇处理")
                album = {"title": obj["title"] or "公众号文章", "author": obj["account"],
                         "desc": "", "cover": "", "count": 1, "url": tgt["url"], "album_id": "single"}
                arts = [{"key": "a1", "msgid": "0", "itemidx": "1", "title": obj["title"],
                         "url": tgt["url"], "pos": 1, "no": 1, "ctime": 0,
                         "status": "pending", "chars": 0, "current": True}]
        else:
            log("info", f"识别为合集：album_id={tgt['album_id']}")
            meta, items = fetch_album(f, tgt["biz"], tgt["album_id"], log)
            album = dict(meta, url=tgt["url"], album_id=tgt["album_id"], biz=tgt["biz"])
            arts = [dict(it, status="pending", chars=0) for it in items]
        if not arts:
            raise RuntimeError("没有抓到任何文章，合集可能是空的")
        with LOCK:
            STATE["album"] = album
            STATE["articles"] = arts
            STATE["listId"] = STATE.get("listId", 0) + 1
            STATE["progress"]["total"] = len(arts)
            STATE["rev"] += 1
        set_phase("ready", f"共 {len(arts)} 篇，可以开始抓取")
        log("ok", f"解析完成：{album.get('title') or '未命名'} · {len(arts)} 篇")
    except Exception as e:
        log("err", f"解析失败：{e}")
        set_phase("error", str(e))


def do_build(selected_keys, opts):
    with LOCK:
        arts = [a for a in STATE["articles"] if a["key"] in selected_keys]
        album = dict(STATE["album"] or {})
        STATE["result"] = None
        STATE["progress"].update({"total": len(arts), "done": 0, "ok": 0, "fail": 0,
                                  "current": "", "chars": 0})
        STATE["rev"] += 1
    if not arts:
        set_phase("error", "没有选中任何文章")
        return

    total = len(arts)
    concurrency = int(opts.get("concurrency") or 1)
    concurrency = max(1, min(10, concurrency))
    if concurrency > 1:
        set_phase("fetching", f"开始抓取正文（{concurrency} 线程并发）…")
    else:
        set_phase("fetching", "开始抓取正文…")

    f = Fetcher()
    cache_root = CACHE_DIR / str(album.get("album_id") or "single")
    delay = max(0.0, float(opts.get("delay", 0.8)))
    use_cache = bool(opts.get("cache", True))
    titlemode = opts.get("titlemode", "auto")

    got = [None] * total        # 每篇抓取结果，按原顺序
    errs = [None] * total
    stop = threading.Event()
    stat = {"ok": 0, "fail": 0}

    def work(idx, art):
        if stop.is_set() or CTRL["cancel"]:
            return
        i = idx + 1
        with LOCK:
            for a in STATE["articles"]:
                if a["key"] == art["key"]:
                    a["status"] = "fetching"
            STATE["progress"]["current"] = art["title"]
            STATE["rev"] += 1
        cache_path = cache_root / f'{art["msgid"]}_{art["itemidx"]}.json'
        try:
            obj, from_cache = fetch_article(f, art, cache_path, use_cache, log)
            blocks = list(obj["blocks"])
            raw = clean(obj.get("title") or art["title"])
            got[idx] = {"blocks": blocks, "raw": raw,
                        "account": obj.get("account"), "publish": obj.get("publish"),
                        "art": art, "from_cache": from_cache}
            chars = article_chars(blocks)
            with LOCK:
                for a in STATE["articles"]:
                    if a["key"] == art["key"]:
                        a["status"] = "done"
                        a["chars"] = chars
                STATE["progress"]["ok"] += 1
                STATE["progress"]["chars"] += chars
                STATE["rev"] += 1
                stat["ok"] += 1
            log("ok", f"  [{i}/{total}] ✓ {chars} 字{'' if from_cache else ''}"
                      f"{'（缓存）' if from_cache else ''} {raw[:22]}")
        except Exception as e:
            errs[idx] = (art["title"], str(e))
            with LOCK:
                for a in STATE["articles"]:
                    if a["key"] == art["key"]:
                        a["status"] = "fail"
                        a["error"] = str(e)
                STATE["progress"]["fail"] += 1
                STATE["rev"] += 1
                stat["fail"] += 1
                fail_now, ok_now = stat["fail"], stat["ok"]
            log("err", f"  [{i}/{total}] ✗ {e}")
            if fail_now >= 5 and ok_now == 0:
                stop.set()
        finally:
            with LOCK:
                STATE["progress"]["done"] += 1
                STATE["rev"] += 1
        if delay > 0:
            if concurrency == 1:
                time.sleep(delay * (0.7 + random.random() * 0.7))
            else:
                time.sleep(random.random() * delay)

    try:
        if concurrency == 1:
            for idx, art in enumerate(arts):
                if CTRL["cancel"] or stop.is_set():
                    break
                work(idx, art)
        else:
            with ThreadPoolExecutor(max_workers=concurrency) as ex:
                futures = [ex.submit(work, i, a) for i, a in enumerate(arts)]
                for fut in as_completed(futures):
                    if CTRL["cancel"] or stop.is_set():
                        break
                    fut.exception()
    except Exception as e:
        log("err", f"抓取线程异常：{e}")

    if CTRL["cancel"]:
        log("warn", "已取消")
        set_phase("cancelled", f"已取消（完成 {stat['ok']} 篇）")
        return
    if stop.is_set():
        log("err", "连续失败过多，可能触发了频率限制，任务中止。已抓取的会缓存，加大间隔后重试即可。")
        set_phase("error", "连续失败过多，可能被限流；请把线程数调回 1、间隔调大后重试")
        return

    # 按原顺序组装章节（编号连续，跳过的失败篇不留空号）
    chapters, failures = [], []
    for idx in range(total):
        art = arts[idx]
        r = got[idx]
        if r is None:
            if errs[idx]:
                failures.append(errs[idx])
            continue
        blocks = r["blocks"]
        no = len(chapters) + 1
        ttl, drop = pick_title(r["raw"], blocks, titlemode)
        if drop is not None:
            blocks = [b for i, b in enumerate(blocks) if i != drop]
        if (opts.get("numbering", True) and not CHAP_ANY_RE.search(ttl)
                and not re.match(r"^\d+[\.、．]", ttl)):
            ttl = f"{no:0{max(2, len(str(total)))}d}. {ttl}"
        chapters.append({"title": ttl, "raw": r["raw"], "blocks": blocks, "no": no,
                         "account": r.get("account"), "publish": r.get("publish")})

    if not chapters:
        set_phase("error", "一篇都没抓到，请检查网络或稍后重试")
        return
    set_phase("building", "正在生成 EPUB…")
    try:
        opts2 = dict(opts)
        opts2["fetcher"] = f
        opts2["cancel"] = CTRL["cancel"]
        result = build_epub(album, chapters, opts2, Path(opts.get("outdir") or OUT_DIR), log)
        result["failures"] = failures[:30]
        with LOCK:
            STATE["result"] = result
            STATE["rev"] += 1
        # 自动登记为"连载追踪"，供以后检查更新
        title = opts.get("title") or album.get("title") or "公众号合集"
        author = opts.get("author") or album.get("author") or ""
        try:
            if (opts.get("watch_auto", True) and album.get("album_id")
                    and str(album.get("album_id")) not in ("single", "", "None")):
                watch_upsert({
                    "album_id": str(album.get("album_id")),
                    "biz": album.get("biz") or "",
                    "album_title": album.get("title") or "",
                    "book_title": title,
                    "author": author,
                    "cover": album.get("cover") or "",
                    "count": result["chapters"],
                    "keys": [f'{a["msgid"]}_{a["itemidx"]}' for a in arts],
                    "epub": result["path"],
                    "images": bool(opts.get("images")),
                    "opts": {"numbering": opts.get("numbering", True),
                             "titlemode": opts.get("titlemode", "auto"),
                             "concurrency": concurrency},
                    "last_build": datetime.datetime.now().strftime("%Y-%m-%d %H:%M"),
                })
                WSTATE["results"].pop(str(album.get("album_id")), None)
                log("info", "已加入连载追踪列表")
        except Exception as e:
            log("warn", f"登记追踪失败：{e}")
        msg = f"完成：{result['name']}"
        if failures:
            msg += f"（{len(failures)} 篇失败）"
        set_phase("done", msg)
    except Exception as e:
        log("err", "生成失败：" + str(e))
        log("err", traceback.format_exc()[-1200:])
        set_phase("error", f"生成 EPUB 失败：{e}")


# ----------------------------------------------------------------------------
# HTTP
# ----------------------------------------------------------------------------
class Handler(BaseHTTPRequestHandler):
    server_version = "WXAlbum/1.0"

    def log_message(self, fmt, *args):
        pass

    def _send(self, code, body, ctype="application/json; charset=utf-8", extra=None):
        if isinstance(body, (dict, list)):
            body = json.dumps(body, ensure_ascii=False).encode("utf-8")
        elif isinstance(body, str):
            body = body.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        try:
            self.wfile.write(body)
        except Exception:
            pass

    def do_GET(self):
        path = urllib.parse.urlparse(self.path).path
        if path in ("/", "/index.html"):
            p = WEB_DIR / "index.html"
            if not p.exists():
                return self._send(500, "缺少 web/index.html", "text/plain; charset=utf-8")
            return self._send(200, p.read_bytes(), "text/html; charset=utf-8")
        if path == "/api/state":
            return self._send(200, snapshot())
        if path == "/api/watch":
            return self._send(200, watch_state_payload())
        if path == "/api/thumb":
            q = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
            u = (q.get("u") or [""])[0]
            host = urllib.parse.urlparse(u).hostname or ""
            if not host.endswith("qpic.cn") and not host.endswith("qlogo.cn"):
                return self._send(403, {"error": "bad host"})
            try:
                data, ctype, _ = Fetcher().raw(u, referer="https://mp.weixin.qq.com/")
                return self._send(200, data, ctype or "image/jpeg")
            except Exception as e:
                return self._send(404, {"error": str(e)})
        if path == "/api/download":
            q = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
            name = os.path.basename((q.get("name") or [""])[0])
            fp = OUT_DIR / name
            if not name or not fp.exists():
                return self._send(404, {"error": "文件不存在"})
            data = fp.read_bytes()
            fn = urllib.parse.quote(name)
            return self._send(200, data, "application/epub+zip", {
                "Content-Disposition": f"attachment; filename=book.epub; filename*=UTF-8''{fn}"})
        return self._send(404, {"error": "not found"})

    def do_POST(self):
        path = urllib.parse.urlparse(self.path).path
        try:
            ln = int(self.headers.get("Content-Length") or 0)
            body = json.loads(self.rfile.read(ln).decode("utf-8") or "{}") if ln else {}
        except Exception:
            body = {}
        if path == "/api/parse":
            if STATE["phase"] in ("parsing", "fetching", "building"):
                return self._send(409, {"error": "任务进行中，请等待完成或先取消"})
            threading.Thread(target=do_parse,
                             args=(body.get("input", ""), body.get("expand", True)),
                             daemon=True).start()
            return self._send(200, {"ok": True})
        if path == "/api/build":
            if STATE["phase"] in ("parsing", "fetching", "building"):
                return self._send(409, {"error": "任务进行中"})
            if not STATE["articles"]:
                return self._send(400, {"error": "请先解析链接"})
            CTRL["cancel"] = False
            sel = set(body.get("selected") or [a["key"] for a in STATE["articles"]])
            threading.Thread(target=do_build, args=(sel, body.get("opts") or {}), daemon=True).start()
            return self._send(200, {"ok": True})
        if path == "/api/watch/check":
            if WSTATE["checking"]:
                return self._send(409, {"error": "正在检查中"})
            ids = body.get("album_ids")
            threading.Thread(target=do_check_watch, args=(ids,), daemon=True).start()
            return self._send(200, {"ok": True})
        if path == "/api/watch/update":
            aid = str(body.get("album_id") or "")
            if not aid:
                return self._send(400, {"error": "缺少 album_id"})
            threading.Thread(target=do_update_watch, args=(aid,), daemon=True).start()
            return self._send(200, {"ok": True})
        if path == "/api/watch/remove":
            aid = str(body.get("album_id") or "")
            if not aid:
                return self._send(400, {"error": "缺少 album_id"})
            watch_remove(aid)
            return self._send(200, {"ok": True})
        if path == "/api/watch/add":
            album = STATE.get("album") or {}
            if not album.get("album_id") or album.get("album_id") == "single":
                return self._send(400, {"error": "当前不是合集，无法追踪"})
            watch_upsert({
                "album_id": str(album["album_id"]),
                "biz": album.get("biz") or "",
                "album_title": album.get("title") or "",
                "book_title": body.get("title") or album.get("title") or "",
                "author": body.get("author") or album.get("author") or "",
                "cover": album.get("cover") or "",
                "count": len(STATE.get("articles") or []),
                "keys": [a["key"] for a in (STATE.get("articles") or [])],
                "epub": (STATE.get("result") or {}).get("path") or "",
                "images": bool((body.get("opts") or {}).get("images")),
                "opts": {"numbering": (body.get("opts") or {}).get("numbering", True),
                         "titlemode": (body.get("opts") or {}).get("titlemode", "auto")},
                "last_build": datetime.datetime.now().strftime("%Y-%m-%d %H:%M"),
            })
            return self._send(200, {"ok": True})
        if path == "/api/cancel":
            CTRL["cancel"] = True
            log("warn", "收到取消指令…")
            return self._send(200, {"ok": True})
        if path == "/api/open":
            target = OUT_DIR
            if body.get("path"):
                target = Path(body["path"]).parent
            try:
                if hasattr(os, "startfile"):
                    os.startfile(str(target))
                else:
                    import subprocess
                    subprocess.Popen(["xdg-open", str(target)] if sys.platform.startswith("linux")
                                     else ["open", str(target)])
                return self._send(200, {"ok": True})
            except Exception as e:
                return self._send(500, {"error": str(e)})
        if path == "/api/quit":
            self._send(200, {"ok": True})
            threading.Timer(0.4, lambda: os._exit(0)).start()
            return
        return self._send(404, {"error": "not found"})


def find_port(start=8765, tries=30):
    for p in range(start, start + tries):
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
            if s.connect_ex(("127.0.0.1", p)) != 0:
                return p
    return start


def reinit(home_dir, web_dir=None):
    """Android 启动时把工作目录切到应用私有目录。"""
    global APP_DIR, WEB_DIR, CACHE_DIR, OUT_DIR, WATCH_PATH
    APP_DIR = Path(home_dir)
    WEB_DIR = Path(web_dir) if web_dir else APP_DIR / "web"
    CACHE_DIR = APP_DIR / "cache"
    OUT_DIR = APP_DIR / "output"
    WATCH_PATH = APP_DIR / "watch.json"
    for d in (WEB_DIR, CACHE_DIR, OUT_DIR):
        try:
            d.mkdir(parents=True, exist_ok=True)
        except Exception:
            pass


def serve(port=None, open_browser=True):
    load_watch()
    if watch_items() and should_auto_check():
        threading.Timer(2.5, lambda: threading.Thread(target=do_check_watch, daemon=True).start()).start()
    port = port or find_port()
    srv = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    url = f"http://127.0.0.1:{port}/"
    print("=" * 60)
    print("  公众号合集 → EPUB     界面已启动")
    print(f"  {url}")
    print("  关闭窗口或按 Ctrl+C 退出")
    print("=" * 60)
    if open_browser:
        threading.Timer(0.9, lambda: webbrowser.open(url)).start()
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        print("\n已退出")


def run_cli(raw, limit=None, delay=0.8, images=True, outdir=None, title=None, author=None,
            expand=True):
    do_parse(raw, expand)
    if STATE["phase"] != "ready":
        print("解析失败：", STATE["message"])
        return 2
    arts = STATE["articles"][:limit] if limit else STATE["articles"]
    do_build({a["key"] for a in arts},
             {"delay": delay, "images": images, "numbering": True, "cache": True,
              "title": title or "", "author": author or "",
              "outdir": str(outdir or OUT_DIR)})
    print("结果：", STATE["phase"], STATE["message"])
    return 0 if STATE["phase"] == "done" else 1


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description="公众号合集 → EPUB")
    ap.add_argument("--cli", metavar="URL", help="命令行模式：直接抓取并生成")
    ap.add_argument("--limit", type=int, default=0, help="仅抓前 N 篇（测试用）")
    ap.add_argument("--delay", type=float, default=0.8, help="每篇间隔秒数")
    ap.add_argument("--no-images", action="store_true", help="不下载插图")
    ap.add_argument("--no-expand", action="store_true", help="文章链接不自动展开为整个合集")
    ap.add_argument("--out", default=None, help="输出目录")
    ap.add_argument("--title", default=None, help="自定义书名（同时决定文件名）")
    ap.add_argument("--author", default=None, help="自定义作者")
    ap.add_argument("--port", type=int, default=None)
    ap.add_argument("--no-browser", action="store_true")
    a = ap.parse_args()
    load_watch()
    if a.cli:
        sys.exit(run_cli(a.cli, a.limit, a.delay, not a.no_images, a.out, a.title, a.author,
                         not a.no_expand))
    serve(a.port, not a.no_browser)
