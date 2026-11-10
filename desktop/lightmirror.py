#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
光速镜像 · 桌面版 (LightMirror Desktop)
--------------------------------------
GitHub 镜像下载 + 版本追踪，单文件、无第三方依赖（除 PySide6）。

特性
  · 逐镜像测速，只走最快链路；多文件并发、单文件多线程分段（最高各 50）
  · 失败自动换镜像（1–10 个），断点续传
  · 版本追踪：桌面端默认只关心 EXE / MSI / ZIP / RAR
  · 每个项目独立的下载目录 + 同名文件替换规则 + 备注名与排序
  · 测速 / 下载日志追加写入程序目录 lightmirror.log
  · 历史记录、镜像源管理、深色 / 浅色 / 黑白三套主题

运行：python lightmirror.py
"""

import json
import math
import os
import platform
import re
import shutil
import subprocess
import sys
import threading
import time
import traceback
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone
from functools import cmp_to_key
from pathlib import Path

APP_NAME = "光速镜像"
APP_NAME_EN = "LightMirror"
APP_VERSION = "2.4.3"
BUILD_NO = 9
UA = "LightMirror-Desktop/2.2 (+https://github.com)"
IS_WIN = os.name == "nt"
IS_MAC = sys.platform == "darwin"
BJ = timezone(timedelta(hours=8))

# --------------------------------------------------------------------------
# 路径 / 基础工具
# --------------------------------------------------------------------------


def app_dir() -> Path:
    if IS_WIN:
        base = os.environ.get("APPDATA") or str(Path.home() / "AppData" / "Roaming")
    elif IS_MAC:
        base = str(Path.home() / "Library" / "Application Support")
    else:
        base = os.environ.get("XDG_CONFIG_HOME") or str(Path.home() / ".config")
    p = Path(base) / "LightMirror"
    p.mkdir(parents=True, exist_ok=True)
    return p


def default_download_root() -> str:
    home = Path(os.environ.get("USERPROFILE") or Path.home())
    return str(home / "Downloads" / "LightMirror")


# --------------------------------------------------------------------------
# 运行日志：默认写在程序（EXE）同级目录，追加写入，跨次启动不清空
# --------------------------------------------------------------------------

LOG_NAME = "lightmirror.log"
_LOG_LOCK = threading.Lock()
_LOG_PATH = None


def program_dir() -> Path:
    """EXE 所在目录；源码运行时是脚本目录。"""
    if getattr(sys, "frozen", False):
        try:
            return Path(sys.executable).resolve().parent
        except Exception:
            pass
    try:
        return Path(__file__).resolve().parent
    except Exception:
        return Path.cwd()


def log_path() -> Path:
    """优先程序目录；目录不可写（如装在 Program Files）时回落到配置目录。"""
    global _LOG_PATH
    if _LOG_PATH is not None:
        return _LOG_PATH
    for cand in (program_dir() / LOG_NAME, app_dir() / LOG_NAME):
        try:
            cand.parent.mkdir(parents=True, exist_ok=True)
            with cand.open("a", encoding="utf-8"):
                pass
            _LOG_PATH = cand
            break
        except Exception:
            continue
    if _LOG_PATH is None:
        _LOG_PATH = app_dir() / LOG_NAME
    return _LOG_PATH


def log_line(tag: str, text: str) -> None:
    """一行一条，失败绝不打断主流程。"""
    path = log_path()
    ts = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    line = f"[{ts}] [{tag}] {text}\n"
    try:
        fresh = not path.exists() or path.stat().st_size == 0
        with _LOG_LOCK:
            with path.open("a", encoding="utf-8-sig" if fresh else "utf-8") as f:
                f.write(line)
    except Exception:
        pass


def short_path(p, limit=58) -> str:
    s = str(p)
    if len(s) <= limit:
        return s
    keep = max(12, (limit - 1) // 2)
    return s[:keep] + "…" + s[-keep:]


def now_ms() -> int:
    return int(time.time() * 1000)


def fmt_size(n) -> str:
    try:
        n = float(n or 0)
    except Exception:
        return "—"
    if n <= 0:
        return "0 B"
    units = ["B", "KB", "MB", "GB", "TB"]
    i = min(len(units) - 1, int(math.log(n, 1024)))
    v = n / (1024 ** i)
    if i == 0:
        return f"{int(v)} B"
    return f"{v:.1f} {units[i]}"


def fmt_speed(mb: float) -> str:
    if mb <= 0:
        return "0.0 MB/s"
    if mb < 1:
        return f"{mb * 1024:.0f} KB/s"
    return f"{mb:.1f} MB/s"


def fmt_duration(sec: float) -> str:
    if sec is None or sec < 0 or sec > 86400 * 9:
        return "--:--"
    sec = int(sec)
    h, m, s = sec // 3600, (sec % 3600) // 60, sec % 60
    return f"{h:02d}:{m:02d}:{s:02d}" if h else f"{m:02d}:{s:02d}"


def fmt_ts(ms) -> str:
    try:
        return datetime.fromtimestamp(int(ms) / 1000).strftime("%Y-%m-%d %H:%M")
    except Exception:
        return "—"


def fmt_ts_short(ms) -> str:
    try:
        return datetime.fromtimestamp(int(ms) / 1000).strftime("%m-%d %H:%M")
    except Exception:
        return "—"


def bj(iso: str):
    """GitHub 的 ISO 时间 → 北京时间 datetime"""
    if not iso:
        return None
    try:
        s = iso.replace("Z", "+00:00")
        dt = datetime.fromisoformat(s)
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=timezone.utc)
        return dt.astimezone(BJ)
    except Exception:
        return None


def bj_label(iso: str, with_year=True) -> str:
    dt = bj(iso)
    if not dt:
        return "—"
    return dt.strftime("%Y-%m-%d %H:%M" if with_year else "%m-%d %H:%M")


_BAD_CHARS = re.compile(r'[\\/:*?"<>|\x00-\x1f]')
_RESERVED = {"CON", "PRN", "AUX", "NUL"} | {f"COM{i}" for i in range(1, 10)} | {f"LPT{i}" for i in range(1, 10)}


def safe_name(name: str, fallback="download.bin") -> str:
    name = (name or "").strip().replace("\r", "").replace("\n", "")
    name = _BAD_CHARS.sub("_", name).strip(" .")
    if not name:
        name = fallback
    if name.split(".")[0].upper() in _RESERVED:
        name = "_" + name
    if len(name) > 160:
        stem, dot, ext = name.rpartition(".")
        if dot:
            name = stem[:150] + "." + ext[:8]
        else:
            name = name[:160]
    return name


def unique_path(path: Path) -> Path:
    if not path.exists():
        return path
    stem, suf = path.stem, path.suffix
    for i in range(1, 1000):
        p = path.with_name(f"{stem} ({i}){suf}")
        if not p.exists():
            return p
    return path.with_name(f"{stem}-{int(time.time())}{suf}")


def ext_of(name: str) -> str:
    n = (name or "").lower()
    for multi in (".tar.gz", ".tar.xz", ".tar.bz2", ".tar.zst"):
        if n.endswith(multi):
            return multi[1:]
    if "." not in n:
        return ""
    return n.rsplit(".", 1)[-1]


def open_path(path) -> None:
    p = str(path)
    try:
        if IS_WIN:
            os.startfile(p)  # type: ignore[attr-defined]
        elif IS_MAC:
            subprocess.Popen(["open", p])
        else:
            subprocess.Popen(["xdg-open", p])
    except Exception:
        pass


def reveal_path(path) -> None:
    p = Path(path)
    try:
        if IS_WIN:
            subprocess.Popen(["explorer", "/select,", str(p)])
        elif IS_MAC:
            subprocess.Popen(["open", "-R", str(p)])
        else:
            open_path(p.parent)
    except Exception:
        open_path(p.parent)


# --------------------------------------------------------------------------
# 配置 / 存储
# --------------------------------------------------------------------------

# 桌面端关心的格式：EXE / MSI / ZIP / RAR / PDF，7Z 留在可选里
BUILTIN_FORMATS = [("exe", "EXE"), ("msi", "MSI"), ("zip", "ZIP"),
                   ("rar", "RAR"), ("pdf", "PDF"), ("7z", "7Z")]
FORMAT_LABELS = [label for _, label in BUILTIN_FORMATS] + ["其他"]
DEFAULT_FORMATS = ["exe", "msi", "zip", "rar", "pdf"]
ALL_KNOWN_EXT = {
    "exe", "msi", "msix", "appx", "zip", "rar", "7z", "tar", "gz", "xz", "bz2",
    "zst", "dmg", "pkg", "deb", "rpm", "apk", "aab", "iso", "pdf", "docx",
    "xlsx", "pptx", "csv", "txt", "json", "xml", "yml", "yaml", "sh", "bat",
    "ps1", "jar", "whl", "py", "js", "css", "html", "png", "jpg", "jpeg",
    "svg", "mp4", "mkv", "mp3", "epub", "mobi", "torrent",
}

REPLACE_RULES = {
    "replace": "直接替换",
    "rename": "自动改名",
    "skip": "跳过不下载",
    "ask": "每次询问",
}

# ---- Windows 平台 / 架构识别 ----
# 桌面端只服务 Windows：ARM64、32 位、macOS/Linux 安装包一律不进列表。
WIN_EXTS = {"exe", "msi", "msix", "appx", "zip", "rar", "7z", "pdf"}
# 调试符号、校验和、签名这类附件不是安装包，默认不参与列表与标签计算
_NOISE_WORDS = {"symbols", "symbol", "debugsymbols", "dbg", "pdb",
                "checksums", "checksum", "shasums", "sha256sums", "md5sums"}
_NOISE_EXT = {"sig", "asc", "pdb", "sha1", "sha256", "sha512", "md5", "sha256sum"}
ARCH_ARM = re.compile(r"(arm64|aarch64|armv[5-8]|win[_-]?arm|windows[_-]?arm)")
ARCH_X64 = re.compile(r"(x64|x86[_-]64|amd64|win64|64[_-]?bit|_64(?![0-9]))")
ARCH_X86 = re.compile(r"((?<![0-9a-z])x86(?![0-9a-z])|i[3-6]86|win32|32[_-]?bit)")
NON_WIN_EXT = {"dmg", "pkg", "deb", "rpm", "apk", "aab", "ipa", "appimage", "snap"}
NON_WIN = re.compile(
    r"(macos|mac[_-]?os|osx|darwin|(?<![a-z])mac(?![a-z])|apple|linux|ubuntu|debian|"
    r"fedora|appimage|freebsd|android|ios[_-])")


def arch_of(name: str) -> str:
    """返回 x64 / arm / x86 / ''（未标注架构，视为通用）。"""
    n = (name or "").lower()
    if ARCH_ARM.search(n):
        return "arm"
    if ARCH_X64.search(n):
        return "x64"
    if ARCH_X86.search(n):
        return "x86"
    return ""


def platform_ok(name: str) -> bool:
    """Windows x64 能用的文件才返回 True。"""
    n = (name or "").lower()
    if ext_of(n) in NON_WIN_EXT:
        return False
    if NON_WIN.search(n):
        return False
    return arch_of(n) in ("", "x64")


_VER_TOKEN = re.compile(r"^v?\d+(?:\.\d+)+[a-z]?\d*$|^\d{4,}$")
# 2026.11.9 / v1.2.3 / 1.2.3b 之类的版本号整段抹掉，不参与「公共部分」判断
_VER_IN_NAME = re.compile(r"(?<![A-Za-z0-9])v?\d+(?:[._-]\d+)+(?:[a-z]\d*)?", re.I)
_BUILD_NO = re.compile(r"(?<![A-Za-z0-9])r\d{2,}(?![0-9])", re.I)
_YEAR = re.compile(r"(?<![0-9])\d{4,}(?![0-9])")
# 点、连字符、下划线、空格、括号都算分隔符（之前把点留在词里，导致拆不出公共部分）
_NAME_SPLIT = re.compile(r"[^A-Za-z0-9]+")


def _tokens(name: str):
    """拆词：CamelCase 与各种分隔符都要拆开，版本号先整段抹掉。"""
    stem = os.path.splitext(name or "")[0]
    if stem.lower().endswith(".tar"):
        stem = stem[:-4]
    stem = re.sub(r"(?i)x86[_-]64", "x64", stem)
    stem = _VER_IN_NAME.sub(" ", stem)
    stem = _BUILD_NO.sub(" ", stem)
    stem = _YEAR.sub(" ", stem)
    stem = re.sub(r"([a-z0-9])([A-Z])", r"\1 \2", stem)
    stem = re.sub(r"([A-Z]+)([A-Z][a-z])", r"\1 \2", stem)
    return [t for t in _NAME_SPLIT.split(stem) if t and not _VER_TOKEN.match(t)]


def name_fingerprint(name: str) -> str:
    """把版本号抹成 *，得到跨版本稳定的指纹，用来记住用户选的变体。"""
    stem = os.path.splitext(name or "")[0].lower()
    if stem.endswith(".tar"):
        stem = stem[:-4]
    stem = re.sub(r"\d+(?:\.\d+)+[a-z]?\d*", "*", stem)
    stem = re.sub(r"(?<!\d)\d{4,}(?!\d)", "*", stem)
    stem = re.sub(r"(?<=-)\d{3,}(?=-|$)", "*", stem)   # 342 这种构建号
    stem = re.sub(r"[\s_]+", "-", stem).strip("-")
    return f"{stem}.{ext_of(name)}"


_ABBR = {"msvc", "gnu", "sse", "avx", "arm", "x86", "x64", "cpu", "gpu", "sdk",
         "cli", "gui", "wpf", "uwp", "api", "ide", "dll"}
_ARCH_WORDS = {"x64", "amd64", "win64", "64bit", "x86", "win32", "32bit", "arm64", "aarch64"}
_ARCH_LABEL = {"x64": "x64", "amd64": "x64", "win64": "x64", "64bit": "x64",
               "x86": "32 位", "win32": "32 位", "32bit": "32 位",
               "arm64": "ARM64", "aarch64": "ARM64"}

# 差异词的中文说法：只翻译一眼能看懂的，品牌词保持原样
_WORD_ZH = {
    "setup": "安装版", "installer": "安装版", "install": "安装版",
    "portable": "便携版", "noinstall": "免安装",
    "unsigned": "未签名", "signed": "已签名", "universal": "通用",
    "full": "完整版", "minimal": "精简版", "lite": "轻量版",
    "debug": "调试版", "release": "正式版", "beta": "Beta",
    "user": "用户版", "machine": "全局版", "system": "全局版",
    "selfcontained": "自带运行时", "standalone": "独立版",
    "x86_64": "x86_64", "x86-64": "x86_64", "amd64": "x64",
    "docs": "文档", "doc": "文档", "manual": "手册", "offline": "离线",
}
_PHRASE_ZH = {
    "user setup": "用户安装",
    "user installer": "用户安装",
    "machine setup": "全局安装",
    "system setup": "全局安装",
    "self contained": "自带运行时",
    "framework dependent": "需运行时",
    "no install": "免安装",
    "arm64 v8a": "ARM64",
    "armeabi v7a": "ARMv7",
    "group policy object files": "组策略文件",
    "full offline bundle": "离线完整包",
    "offline bundle": "离线包",
    "bundle": "整合包",
    "full offline": "离线完整版",
    "group policy objects": "组策略",
}


def _is_cjk(ch: str) -> bool:
    return bool(ch) and "\u4e00" <= ch <= "\u9fff"


def _join_words(parts):
    """中文之间不加空格，中英之间留一个空格。"""
    out = ""
    for p in parts:
        if not p:
            continue
        if out and not (_is_cjk(out[-1]) and _is_cjk(p[0])):
            out += " "
        out += p
    return out


def _friendly(tokens):
    """差异词 → 人读的标签；先按整短语翻，再逐词翻。"""
    words = [t for t in tokens if t]
    if not words:
        return ""
    whole = " ".join(w.lower() for w in words)
    if whole in _PHRASE_ZH:
        return _PHRASE_ZH[whole]
    out = []
    i = 0
    while i < len(words):
        for span in (3, 2):
            if i + span <= len(words):
                key = " ".join(w.lower() for w in words[i:i + span])
                if key in _PHRASE_ZH:
                    out.append(_PHRASE_ZH[key])
                    i += span
                    break
        else:
            w = words[i]
            low = w.lower()
            if low in _ARCH_WORDS:
                out.append(_ARCH_LABEL[low])
            elif low in _WORD_ZH:
                out.append(_WORD_ZH[low])
            elif low in _ABBR:
                out.append(low.upper())
            elif len(low) <= 4 and low.isalpha():
                out.append(low.upper())
            else:
                out.append(w[:1].upper() + w[1:] if w.islower() else w)
            i += 1
    return _join_words(out)


def _norm_stem(name: str) -> str:
    """文件名 → 小写、CamelCase 用 - 拆开、各种分隔符统一成 -，便于逐字比对。"""
    stem = os.path.splitext(name or "")[0]
    if stem.lower().endswith(".tar"):
        stem = stem[:-4]
    stem = re.sub(r"([a-z0-9])([A-Z])", r"\1-\2", stem)
    stem = re.sub(r"([A-Z]+)([A-Z][a-z])", r"\1-\2", stem)
    stem = stem.lower()
    stem = re.sub(r"[^a-z0-9]+", "-", stem)
    return re.sub(r"-{2,}", "-", stem).strip("-")


def _common_prefix(strings):
    """所有名字共有的前缀，且必须停在词边界（- 之后）。"""
    ss = [s for s in strings if s]
    if len(ss) < 2:
        return ""
    p = os.path.commonprefix(ss)
    if p in ss:          # 某个名字整体是别人的前缀：它就是「标准包」
        return p if p.endswith("-") else p + "-"
    cut = p.rfind("-")
    return p[:cut + 1] if cut >= 0 else ""


def _common_suffix(strings, prefix=""):
    """所有名字共有的后缀（同样停在词边界），跳过已剥掉的前缀部分。"""
    ss = [s[len(prefix):] for s in strings if s]
    if len(ss) < 2 or any(not s for s in ss):
        return ""
    rev = [s[::-1] for s in ss]
    p = os.path.commonprefix(rev)
    # 后缀必须从 - 开始，保证不会切在词中间
    idx = p.find("-")
    if idx < 0:
        return ""
    return p[:idx + 1][::-1]


def _label_of(stem: str) -> str:
    """已经剥掉公共部分的词串 → 标签文本。"""
    stem = re.sub(r"x86[-_]64", "x86_64", stem)
    stem = _VER_IN_NAME.sub("-", stem)
    stem = _BUILD_NO.sub("-", stem)
    stem = _YEAR.sub("-", stem)
    toks = [t for t in stem.split("-") if t and not t.isdigit() and not _VER_TOKEN.match(t)]
    return _friendly(toks)


def variant_text(asset):
    """界面上展示用：「差异标签 · 格式」，标签为空时只给格式。"""
    if not asset:
        return "—"
    lab = (asset.get("variant") or "").strip()
    ext = ext_of(asset.get("name", "")).upper()
    return f"{lab} · {ext}" if lab else ext


def variant_labels(assets):
    """按格式分组，删掉「所有文件共有的部分」和版本号，只留差异词。

    做法是逐字比对而不是数词频：先把 CamelCase 拆成小写词串，剥掉公共前缀
    （通常是产品名）和公共后缀（版本号、架构、扩展名），剩下的就是
    「Setup / User Setup」「Portable / Setup」这种真正需要区分的部分。

    返回 {文件名: 标签}，标签不带格式（格式在界面上单独显示）。
    """
    items = [a for a in assets if a.get("name")]
    if not items:
        return {}
    if len(items) == 1:
        return {items[0]["name"]: "标准"}
    stems = {a["name"]: _norm_stem(a["name"]) for a in items}
    all_names = list(stems.values())
    g_prefix = _common_prefix(all_names)
    g_suffix = _common_suffix(all_names, g_prefix)

    groups = {}
    for a in items:
        groups.setdefault(ext_of(a["name"]), []).append(a)

    out = {}
    for ext, group in groups.items():
        names = [stems[a["name"]] for a in group]
        prefix, suffix = g_prefix, g_suffix
        if len(names) >= 2:
            p = _common_prefix(names)
            s = _common_suffix(names, p)
            # 组内公共部分更贴切时优先用组内的（同格式往往只差最后一段）
            if len(p) >= len(prefix):
                prefix, suffix = p, s
        for a in group:
            stem = stems[a["name"]]
            if prefix and stem.startswith(prefix):
                core = stem[len(prefix):]
            elif prefix.endswith("-") and stem == prefix[:-1]:
                core = ""
            else:
                core = stem
            if suffix and core.endswith(suffix):
                core = core[:-len(suffix)]
            core = core.strip("-")
            out[a["name"]] = _label_of(core) or "标准"

    # 同一格式内标签撞车时补序号，保证每个文件都能被指认
    seen = {}
    for a in items:
        name = a["name"]
        key = (out[name], ext_of(name))
        if key in seen:
            seen[key] += 1
            out[name] = f"{out[name]} #{seen[key]}"
        else:
            seen[key] = 1
    return out


DEFAULT_CONFIG = {
    "download_root": default_download_root(),
    "concurrency": 6,
    "threads": 16,
    "segments": True,
    "failover": True,
    "failover_max": 5,
    "resume": True,
    "formats": DEFAULT_FORMATS[:],
    "extra_formats": [],
    "fmt_v2": True,
    "replace_rule": "replace",
    "version_mode": 0,
    "watch_sort": "updated",
    "theme": "dark",
    "probe_kb": 256,
    "timeout": 8,
    "history_max": 200,
    "github_token": "",
    "proxy": "",
    "cache_ttl": 10,
    "last_repo": "",
    "window_geometry": "",
}

DEFAULT_MIRRORS = [
    {"name": "ghfast.top", "base": "https://ghfast.top/", "mode": "prefix"},
    {"name": "xget.xi-xu.me/gh", "base": "https://xget.xi-xu.me/gh/", "mode": "path"},
    {"name": "gh-proxy.com", "base": "https://gh-proxy.com/", "mode": "prefix"},
    {"name": "ghproxy.net", "base": "https://ghproxy.net/", "mode": "prefix"},
    {"name": "hub.gitmirror.com", "base": "https://hub.gitmirror.com/", "mode": "prefix"},
    {"name": "gh.llkk.cc", "base": "https://gh.llkk.cc/", "mode": "prefix"},
    {"name": "ghproxy.cn", "base": "https://ghproxy.cn/", "mode": "prefix"},
    {"name": "ghps.cc", "base": "https://ghps.cc/", "mode": "prefix"},
    {"name": "github.moeyy.xyz", "base": "https://github.moeyy.xyz/", "mode": "prefix"},
    {"name": "GitHub 直连", "base": "", "mode": "direct"},
]


class State:
    """配置 + 镜像 + 追踪 + 历史，统一落一个 JSON。"""

    def __init__(self):
        self.path = app_dir() / "state.json"
        self.lock = threading.RLock()
        self.config = dict(DEFAULT_CONFIG)
        self.mirrors = [dict(m, enabled=True, latency=0, speed=0.0, fail=0, checked=0)
                        for m in DEFAULT_MIRRORS]
        self.watch = []
        self.history = []
        self.load()

    # ---- io ----
    def load(self):
        try:
            raw = json.loads(self.path.read_text("utf-8"))
        except Exception:
            raw = {}
        cfg = raw.get("config") or {}
        for k, v in DEFAULT_CONFIG.items():
            if k in cfg and isinstance(cfg[k], type(v) or object):
                self.config[k] = cfg[k]
        for k in ("concurrency", "threads", "failover_max", "probe_kb", "timeout",
                  "history_max", "cache_ttl", "version_mode"):
            try:
                self.config[k] = int(self.config[k])
            except Exception:
                self.config[k] = DEFAULT_CONFIG[k]
        if isinstance(cfg.get("formats"), list) and cfg["formats"]:
            self.config["formats"] = [str(x).lower() for x in cfg["formats"]]
        # 2.3 起桌面端把 PDF 也算作安装包；老配置自动补上，不覆盖用户已去掉的格式
        if not cfg.get("fmt_v2"):
            for f in ("pdf",):
                if f not in self.config["formats"]:
                    self.config["formats"].append(f)
            self.config["fmt_v2"] = True
        if isinstance(cfg.get("extra_formats"), list):
            self.config["extra_formats"] = [str(x).lower().lstrip(".") for x in cfg["extra_formats"] if str(x).strip()]
        self.config["concurrency"] = max(1, min(50, self.config["concurrency"]))
        self.config["threads"] = max(1, min(50, self.config["threads"]))
        self.config["failover_max"] = max(1, min(10, self.config["failover_max"]))
        if self.config.get("watch_sort") not in ("updated", "note", "name"):
            self.config["watch_sort"] = "updated"
        if self.config.get("theme") not in ("dark", "light", "mono"):
            self.config["theme"] = "dark"

        mirrors = raw.get("mirrors")
        if isinstance(mirrors, list) and mirrors:
            self.mirrors = []
            for m in mirrors:
                if not isinstance(m, dict) or not m.get("name"):
                    continue
                self.mirrors.append({
                    "name": m.get("name", ""),
                    "base": m.get("base", ""),
                    "mode": m.get("mode", "prefix"),
                    "enabled": bool(m.get("enabled", True)),
                    "latency": int(m.get("latency") or 0),
                    "speed": float(m.get("speed") or 0),
                    "fail": int(m.get("fail") or 0),
                    "checked": int(m.get("checked") or 0),
                })
        if isinstance(raw.get("watch"), list):
            self.watch = [w for w in raw["watch"] if isinstance(w, dict) and w.get("repo")]
        if isinstance(raw.get("history"), list):
            self.history = [h for h in raw["history"] if isinstance(h, dict)]

    def save(self):
        with self.lock:
            data = {
                "version": 1,
                "config": self.config,
                "mirrors": self.mirrors,
                "watch": self.watch,
                "history": self.history[: self.config.get("history_max", 200)],
            }
            tmp = self.path.with_suffix(".tmp")
            try:
                tmp.write_text(json.dumps(data, ensure_ascii=False, indent=1), "utf-8")
                os.replace(tmp, self.path)
            except Exception:
                pass

    # ---- history ----
    def add_history(self, rec: dict):
        rec.setdefault("ts", now_ms())
        with self.lock:
            self.history.insert(0, rec)
            del self.history[self.config.get("history_max", 200):]

    def delete_history(self, ids, remove_files=False):
        with self.lock:
            keep, gone = [], []
            for h in self.history:
                if h.get("id") in ids:
                    gone.append(h)
                else:
                    keep.append(h)
            self.history = keep
        if remove_files:
            for h in gone:
                p = h.get("path")
                if p:
                    try:
                        Path(p).unlink(missing_ok=True)
                    except Exception:
                        pass
        self.save()

    # ---- watch ----
    def watch_entry(self, repo: str):
        for w in self.watch:
            if w.get("repo", "").lower() == repo.lower():
                return w
        return None

    def add_watch(self, repo: str):
        w = self.watch_entry(repo)
        if w:
            return w
        w = {"repo": repo, "tag": "", "name": "", "note": "", "dir": "", "rule": "", "variant": "",
             "variant_label": "", "variants": [], "last_vkey": "", "formats": [], "tags_open": False,
             "added": now_ms(), "latest": None, "checked": 0}
        self.watch.insert(0, w)
        self.save()
        return w

    def remove_watch(self, repo: str):
        self.watch = [w for w in self.watch if w.get("repo") != repo]
        self.save()

    def project_dir(self, repo: str) -> Path:
        w = self.watch_entry(repo) or {}
        d = (w.get("dir") or "").strip()
        if d:
            return Path(d)
        folder = repo.replace("/", "__")
        return Path(self.config["download_root"]) / safe_name(folder, "github")

    def project_rule(self, repo: str) -> str:
        w = self.watch_entry(repo) or {}
        rule = w.get("rule") or self.config.get("replace_rule", "replace")
        return rule if rule in REPLACE_RULES else "replace"

    def global_formats(self):
        """设置里勾的格式：桌面端的大范围。"""
        fmts = [str(x).lower() for x in (self.config.get("formats") or []) if str(x).strip()]
        return fmts or list(DEFAULT_FORMATS)

    def format_menu(self):
        """设置里可以勾选的全部格式：内置 + 手动加的 + 已启用的特殊格式。"""
        out = [(k, label) for k, label in BUILTIN_FORMATS]
        seen = {k for k, _ in out}
        extra = [str(x).lower().lstrip(".") for x in (self.config.get("extra_formats") or [])]
        for e in extra + [f for f in self.global_formats() if f not in seen]:
            if e and e not in seen:
                out.append((e, e.upper()))
                seen.add(e)
        return out

    def project_available_formats(self, repo: str):
        """这个项目最新版里真实存在的格式（按设置里的顺序）。

        选格式时只列这些，不存在的格式不出现——省得勾了也看不到文件。
        """
        w = self.watch_entry(repo) or {}
        latest = w.get("latest") or {}
        have = []
        for a in latest.get("assets") or []:
            name = a.get("name") or ""
            if not name or is_noise(name):
                continue
            e = ext_of(name)
            if e and e not in have:
                have.append(e)
        return [f for f in self.global_formats() if f in have]

    def project_formats(self, repo: str):
        """单个项目的小范围：只能从全局范围里再收窄，不会超出设置。"""
        g = self.global_formats()
        w = self.watch_entry(repo) or {}
        own = [str(f).lower() for f in (w.get("formats") or []) if str(f).strip()]
        if not own:
            return list(g)
        keep = [f for f in own if f in g]
        return keep or list(g)


# --------------------------------------------------------------------------
# URL 解析
# --------------------------------------------------------------------------


class Parsed:
    def __init__(self, kind, owner="", repo="", filename="", url="", tag=""):
        self.kind = kind          # file / repo / archive / raw / direct
        self.owner = owner
        self.repo = repo
        self.filename = filename
        self.url = url
        self.tag = tag

    @property
    def slug(self):
        return f"{self.owner}/{self.repo}" if self.owner else ""

    @property
    def label(self):
        return {
            "file": "Release 附件",
            "repo": "仓库地址",
            "archive": "源码包",
            "raw": "Raw 文件",
            "direct": "普通直链",
        }.get(self.kind, "链接")


def parse_github(text: str):
    s = (text or "").strip()
    if not s:
        return None
    if s.startswith("http://") and re.search(r"(^|//)(www\.)?(github\.com|raw\.githubusercontent\.com|codeload\.github\.com)/", s):
        s = "https://" + s[7:]
    # bare owner/repo
    if re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", s):
        o, r = s.split("/")
        return Parsed("repo", o, r, url=f"https://github.com/{o}/{r}")
    if not s.startswith(("https://", "http://")):
        return None
    try:
        u = urllib.parse.urlparse(s)
    except Exception:
        return None
    host = u.netloc.lower()
    parts = [p for p in u.path.split("/") if p]
    if host in ("github.com", "www.github.com"):
        if len(parts) >= 2:
            owner, repo = parts[0], parts[1].removesuffix(".git")
            if len(parts) >= 3 and parts[2] == "releases":
                if len(parts) >= 6 and parts[3] == "download":
                    return Parsed("file", owner, repo, urllib.parse.unquote(parts[5]),
                                  url=s, tag=parts[4])
                return Parsed("repo", owner, repo, url=f"https://github.com/{owner}/{repo}")
            if len(parts) >= 4 and parts[2] in ("archive", "releases"):
                fn = urllib.parse.unquote(parts[-1])
                return Parsed("archive", owner, repo, fn, url=s)
            if len(parts) >= 5 and parts[2] == "raw":
                return Parsed("raw", owner, repo, urllib.parse.unquote(parts[-1]), url=s)
            return Parsed("repo", owner, repo, url=f"https://github.com/{owner}/{repo}")
        return None
    if host == "raw.githubusercontent.com" and len(parts) >= 4:
        return Parsed("raw", parts[0], parts[1], parts[-1], url=s)
    if host == "codeload.github.com" and len(parts) >= 3:
        return Parsed("archive", parts[0], parts[1], parts[-1], url=s)
    if host == "objects.githubusercontent.com" or host.endswith("githubusercontent.com"):
        return Parsed("direct", filename=urllib.parse.unquote(parts[-1]) if parts else "", url=s)
    # any other direct link
    fn = urllib.parse.unquote(parts[-1]) if parts else ""
    return Parsed("direct", filename=fn, url=s)


def mirror_url(m: dict, gh_url: str) -> str:
    base = (m.get("base") or "").rstrip("/")
    if not base:
        return gh_url
    if m.get("mode") == "path":
        tail = gh_url
        for marker in ("https://github.com/", "http://github.com/"):
            if tail.startswith(marker):
                tail = tail[len(marker):]
                break
        return f"{base}/{tail.lstrip('/')}"
    return f"{base}/{gh_url}" if not base.endswith("/") else f"{base}{gh_url}"


def mirror_score(m: dict) -> float:
    sp = min(1.0, float(m.get("speed") or 0) / 10.0)
    lat = int(m.get("latency") or 0)
    ls = max(0.0, 1.0 - lat / 2000.0) if lat else 0.0
    score = 0.7 * sp + 0.3 * ls
    if m.get("fail", 0) >= 3 and not m.get("speed"):
        score -= 0.2
    return score


def ranked_mirrors(mirrors):
    enabled = [m for m in mirrors if m.get("enabled", True)]
    # 直连永远垫底，除非它是唯一选择
    normal = [m for m in enabled if m.get("base")]
    direct = [m for m in enabled if not m.get("base")]
    normal.sort(key=mirror_score, reverse=True)
    return normal + direct or enabled


# --------------------------------------------------------------------------
# 网络请求
# --------------------------------------------------------------------------


def make_opener(proxy: str = ""):
    if proxy:
        return urllib.request.build_opener(
            urllib.request.ProxyHandler({"http": proxy, "https": proxy}))
    return urllib.request.build_opener(urllib.request.ProxyHandler({}))


class HttpError(Exception):
    def __init__(self, msg, code=0):
        super().__init__(msg)
        self.code = code


def probe_mirror(m: dict, url: str, chunk_kb=256, timeout=8, proxy=""):
    """返回 (ok, latency_ms, speed_mb)。只测一小段，最多占用 3.5 秒。"""
    chunk = max(64, chunk_kb) * 1024
    target = mirror_url(m, url)
    req = urllib.request.Request(target, headers={
        "Range": f"bytes=0-{chunk - 1}",
        "User-Agent": UA,
        "Accept": "*/*",
        "Connection": "close",
    })
    t0 = time.time()
    try:
        with make_opener(proxy).open(req, timeout=timeout) as r:
            first = r.read(8192)
            ttfb = time.time() - t0
            got = len(first)
            cap = t0 + 3.5
            while got < chunk and time.time() < cap:
                b = r.read(min(32768, chunk - got))
                if not b:
                    break
                got += len(b)
        dt = max(0.001, time.time() - t0)
        if got <= 0:
            return False, 0, 0.0
        speed = got / dt / 1048576.0
        return True, int(ttfb * 1000), round(speed, 2)
    except Exception:
        return False, 0, 0.0


API_CACHE = {}
API_LOCK = threading.Lock()


def api_releases(repo: str, page=1, per_page=100, token="", proxy="", timeout=15):
    key = (repo.lower(), page, per_page)
    with API_LOCK:
        hit = API_CACHE.get(key)
    if hit and time.time() - hit[0] < 600:
        return hit[1]
    url = f"https://api.github.com/repos/{repo}/releases?per_page={per_page}&page={page}"
    headers = {"Accept": "application/vnd.github+json", "User-Agent": UA}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(url, headers=headers)
    try:
        with make_opener(proxy).open(req, timeout=timeout) as r:
            data = json.loads(r.read().decode("utf-8", "replace"))
            remain = r.headers.get("X-RateLimit-Remaining")
    except urllib.error.HTTPError as e:
        if e.code == 404:
            raise HttpError("项目不存在，或者这个仓库没有 Release", 404)
        if e.code == 403:
            raise HttpError("GitHub 接口额度用完了，等一小时或到设置里填一个 Token", 403)
        raise HttpError(f"GitHub 返回 {e.code}", e.code)
    except Exception as e:
        raise HttpError(f"网络错误：{e}", 0)
    with API_LOCK:
        API_CACHE[key] = (time.time(), data, remain)
    return data


def api_latest(repo: str, token="", proxy=""):
    # 多拿几条再本地排，避免「先建草稿、后发布」把最新版挤到后面
    return api_releases(repo, 1, 5, token, proxy)


_VER_NUMS = re.compile(r"\d+")


def tag_nums(tag: str):
    """v1.10.2 → (1, 10, 2)，让版本号按数字比大小，而不是按字符串。"""
    return tuple(int(x) for x in _VER_NUMS.findall(tag or ""))


def _ms_of(iso: str) -> int:
    dt = bj(iso or "")
    return int(dt.timestamp() * 1000) if dt else 0


def sort_raw_releases(raw):
    """GitHub 原始 release 列表按「发布时间新→旧」重排，草稿剔除。"""
    rows = [r for r in (raw or []) if isinstance(r, dict) and not r.get("draft")]
    return sorted(rows,
                  key=lambda r: (_ms_of(r.get("published_at") or r.get("created_at")),
                                 tag_nums(r.get("tag_name")), r.get("tag_name") or ""),
                  reverse=True)


def sort_releases(rels):
    """规范化后的版本列表：发布时间倒序，同一时间按版本号倒序，最后按 tag 稳定兜底。"""
    return sorted(rels,
                  key=lambda r: (_ms_of(r.get("published") or r.get("newest")),
                                 tag_nums(r.get("tag")), r.get("tag") or ""),
                  reverse=True)


def normalize_release(rel: dict) -> dict:
    assets = []
    for a in rel.get("assets") or []:
        if not isinstance(a, dict):
            continue
        assets.append({
            "name": a.get("name") or "",
            "size": int(a.get("size") or 0),
            "updated": a.get("updated_at") or a.get("created_at") or "",
            "url": a.get("browser_download_url") or "",
            "downloads": int(a.get("download_count") or 0),
        })
    assets.sort(key=lambda x: x["name"].lower())
    newest = max((a["updated"] for a in assets), default="") or rel.get("published_at") or ""
    _dt = bj(newest)
    newest_ms = int(_dt.timestamp() * 1000) if _dt else 0
    # 先挑出桌面端真正会显示的安装包，再据此算「删掉公共部分」后的变体标签；
    # SUMS、.sig、symbols 这类附件不参与，否则会把公共部分冲散
    pool = [a for a in assets
            if ext_of(a["name"]) in WIN_EXTS and platform_ok(a["name"]) and not is_noise(a["name"])]
    if not pool:
        pool = [a for a in assets if ext_of(a["name"]) in WIN_EXTS] or assets
    labels = variant_labels(pool)
    for a in assets:
        a["arch"] = arch_of(a["name"])
        a["vkey"] = name_fingerprint(a["name"])
        a["variant"] = labels.get(a["name"], "")
    return {
        "tag": rel.get("tag_name") or "",
        "name": rel.get("name") or rel.get("tag_name") or "",
        "body": (rel.get("body") or "")[:4000],
        "published": rel.get("published_at") or "",
        "prerelease": bool(rel.get("prerelease")),
        "draft": bool(rel.get("draft")),
        "html_url": rel.get("html_url") or "",
        "assets": assets,
        "newest": newest,
        "newest_ms": newest_ms,
        "size": sum(a["size"] for a in assets),
    }


def is_noise(name: str) -> bool:
    """调试符号、校验和、签名文件：不是安装包，列表和标签都不该带上它们。"""
    n = (name or "").lower()
    if ext_of(n) in _NOISE_EXT:
        return True
    toks = {t.lower() for t in _tokens(n)}
    if toks & _NOISE_WORDS:
        return True
    return bool(re.search(r"(^|[^a-z])(source ?code|sources?|src)([^a-z]|$)", n)) and \
        ext_of(n) in ("zip", "tar", "gz", "xz", "7z")


def filter_assets(assets, formats, include_other_arch=False):
    """按格式 + Windows x64 过滤。include_other_arch=True 时放开架构限制。"""
    known = {k for k, _ in BUILTIN_FORMATS}
    fmt = [str(f).lower() for f in (formats or [])]
    every = not fmt or "all" in fmt
    pool = []
    for a in assets:
        e = ext_of(a["name"])
        if every or e in fmt or ("other" in fmt and e not in known):
            pool.append(a)
    clean = [a for a in pool if not is_noise(a["name"])]
    pool = clean or pool          # 全是符号文件时保底，不让列表彻底空掉
    if include_other_arch:
        return pool
    out = [a for a in pool if platform_ok(a.get("name", ""))]
    # 整个版本只有 ARM / 32 位构建时，保底全部显示，免得什么都下不了
    return out if out else pool


def pick_variant(assets, vkey):
    if not vkey:
        return None
    for a in assets:
        if a.get("vkey") == vkey:
            return a
    return None


def asset_score(a):
    n = a["name"].lower()
    e = ext_of(n)
    order = ["exe", "msi", "msix", "zip", "7z", "rar"]
    s = (10 - order.index(e)) * 10 if e in order else 0
    if any(k in n for k in ("x64", "x86_64", "amd64", "win64", "64bit")):
        s += 30
    if any(k in n for k in ("arm64", "aarch64")):
        s -= 20
    if re.search(r"(^|[^0-9])x86([^0-9]|$)|win32|32bit", n):
        s -= 10
    if any(k in n for k in ("setup", "installer", "install")):
        s += 8
    if "msvc" in n:
        s += 6
    if "gnu" in n:
        s += 2
    if any(k in n for k in ("portable", "noinstall")):
        s += 4
    if "debug" in n or "symbols" in n:
        s -= 50
    return s


def preferred_asset(assets, formats):
    pool = filter_assets(assets, formats)
    if not pool:
        return None
    return sorted(pool, key=asset_score, reverse=True)[0]


def segment_count(total, threads):
    """按文件大小决定实际切几段：上限 50，且每段至少 256 KB。"""
    n = max(1, min(int(threads or 1), 50))
    total = int(total or 0)
    if not total:
        return 1
    n = min(n, max(1, total // (256 * 1024)))
    if total < 512 * 1024:
        n = 1
    return n


# --------------------------------------------------------------------------
# 下载任务
# --------------------------------------------------------------------------


class Task:
    def __init__(self, url, filename, target: Path, repo="", mirror="", size_hint=0):
        self.id = f"t{now_ms()}{int(time.time() * 1000) % 97}"
        self.url = url
        self.filename = filename
        self.target = Path(target)
        self.repo = repo
        self.size_hint = size_hint
        self.total = int(size_hint or 0)
        self.done = 0
        self.seg_done = []
        self.seg_ranges = []
        self.segments = 1
        self.status = "queued"       # queued/probing/running/paused/done/error/canceled/skipped
        self.error = ""
        self.mirror = mirror
        self.speed = 0.0
        self.started_at = 0.0
        self.finished_at = 0.0
        self.attempts = 0
        self.threads = []
        self.lock = threading.RLock()
        self.pause_evt = threading.Event()
        self.stop_evt = threading.Event()
        self.wake = threading.Event()
        self.skipped = False
        self.part = self.target.with_name(self.target.name + ".part")

    @property
    def progress(self):
        return min(1.0, self.done / self.total) if self.total else 0.0

    @property
    def elapsed(self):
        if not self.started_at:
            return 0
        end = self.finished_at or time.time()
        return end - self.started_at

    @property
    def eta(self):
        if self.speed <= 0.01 or not self.total:
            return None
        return max(0, (self.total - self.done) / (self.speed * 1048576))

    def snapshot(self):
        with self.lock:
            return {
                "id": self.id, "status": self.status, "done": self.done,
                "total": self.total, "speed": self.speed, "error": self.error,
                "mirror": self.mirror, "segments": self.segments,
            }


class DownloadManager:
    def __init__(self, state: State, on_change=None):
        self.state = state
        self.tasks = []
        self.lock = threading.RLock()
        self.on_change = on_change or (lambda: None)

    def create(self, url, filename, target: Path, repo="", size_hint=0):
        target = Path(target)
        try:
            target.parent.mkdir(parents=True, exist_ok=True)
        except Exception:
            pass
        t = Task(url, filename, target, repo=repo, size_hint=size_hint)
        with self.lock:
            self.tasks.insert(0, t)
        return t

    def running_count(self):
        with self.lock:
            return sum(1 for t in self.tasks if t.status in ("probing", "running"))

    def pump(self):
        with self.lock:
            slots = max(0, int(self.state.config["concurrency"]) - self.running_count())
            for t in self.tasks:
                if slots <= 0:
                    break
                if t.status == "queued" and not t.threads:
                    slots -= 1
                    t.status = "probing"
                    th = threading.Thread(target=self._run, args=(t,), daemon=True)
                    t.threads = [th]
                    th.start()

    def start(self, task):
        task.status = "queued"
        self.pump()

    def _run(self, t: Task):
        try:
            t.started_at = time.time()
            log_line("下载", f"开始 · {t.filename} · {t.url}")
            mirrors = ranked_mirrors(self.state.mirrors)
            if not mirrors:
                raise HttpError("没有启用的镜像源")
            size, supports_range, working = self._probe(t, mirrors)
            t.total = size or t.total
            if t.status == "canceled":
                return
            threads = int(self.state.config["threads"]) if self.state.config.get("segments") else 1
            if supports_range and t.total and threads > 1:
                self._download_segmented(t, working, threads)
            else:
                self._download_single(t, working)
            if t.stop_evt.is_set():
                return
            if t.pause_evt.is_set():
                t.status = "paused"
                t.speed = 0
                return
            part_size = t.part.stat().st_size if t.part.exists() else 0
            if t.total and part_size < t.total:
                raise HttpError("文件不完整，请重试")
            t.total = t.total or part_size
            if t.part.exists():
                os.replace(t.part, t.target)
            t.done = t.total
            t.status = "done"
            t.finished_at = time.time()
            t.speed = 0
            self.state.add_history({
                "id": t.id, "name": t.filename, "path": str(t.target),
                "size": t.total, "ts": now_ms(), "mirror": t.mirror,
                "repo": t.repo, "status": "done",
            })
            log_line("下载", f"完成 · {t.filename} · {fmt_size(t.total)} · "
                             f"用时 {fmt_duration(t.elapsed)} · {t.mirror} · {t.segments} 段")
            self.state.save()
            self.on_change()
        except Exception as e:
            if t.stop_evt.is_set():
                return
            t.status = "error"
            t.error = str(e) or "下载失败"
            t.finished_at = time.time()
            t.speed = 0
            try:
                if t.part.exists() and not self.state.config.get("resume"):
                    t.part.unlink(missing_ok=True)
            except Exception:
                pass
            self.state.add_history({
                "id": t.id, "name": t.filename, "path": str(t.target),
                "size": t.total, "ts": now_ms(), "mirror": t.mirror,
                "repo": t.repo, "status": "error", "error": t.error,
            })
            log_line("下载", f"失败 · {t.filename} · {t.error}")
            self.state.save()
            self.on_change()
        finally:
            t.threads = []
            self.pump()
            self.on_change()

    def _probe(self, t: Task, mirrors):
        last = None
        for m in mirrors:
            if t.stop_evt.is_set():
                return 0, False, None
            url = mirror_url(m, t.url)
            req = urllib.request.Request(url, headers={
                "Range": "bytes=0-0", "User-Agent": UA, "Accept": "*/*"})
            try:
                with make_opener(self.state.config["proxy"]).open(
                        req, timeout=int(self.state.config["timeout"])) as r:
                    cr = r.headers.get("Content-Range") or ""
                    cl = int(r.headers.get("Content-Length") or 0)
                    size = 0
                    if "/" in cr:
                        try:
                            size = int(cr.rsplit("/", 1)[1])
                        except Exception:
                            size = 0
                    if not size and r.status == 200 and cl:
                        size = cl
                    supports = "/" in cr and size > 0
                    t.mirror = m["name"]
                    return size, supports, m
            except Exception as e:
                last = e
                m["fail"] = int(m.get("fail") or 0) + 1
                continue
        raise HttpError(f"所有镜像都连不上（{last}）")

    def _mirror_cycle(self, start=0):
        pool = ranked_mirrors(self.state.mirrors)
        if not pool:
            return []
        return pool[start:] + pool[:start]

    def _download_segmented(self, t: Task, mirror, threads):
        n = segment_count(t.total, threads)
        with t.lock:
            reuse = (t.seg_ranges and len(t.seg_ranges) == n
                     and t.seg_ranges[-1][1] == t.total - 1 and len(t.seg_done) == n)
            if reuse:
                ranges = t.seg_ranges
            else:
                seg = t.total // n
                ranges = []
                for i in range(n):
                    a = i * seg
                    b = t.total - 1 if i == n - 1 else (i + 1) * seg - 1
                    ranges.append((a, b))
                t.seg_ranges = ranges
                t.seg_done = [0] * n
            t.segments = n
            t.done = sum(t.seg_done)
        # 预分配，避免边写边扩
        try:
            if not t.part.exists() or t.part.stat().st_size != t.total:
                with open(t.part, "wb") as f:
                    f.truncate(t.total)
        except Exception:
            pass
        t.status = "running"
        self._spawn_segments(t, ranges, n)
        self._monitor(t)
        failed = [i for i, d in enumerate(t.seg_done) if d < (ranges[i][1] - ranges[i][0] + 1)]
        if failed and not t.stop_evt.is_set() and not t.pause_evt.is_set():
            raise HttpError("部分分片下载失败")

    def _spawn_segments(self, t: Task, ranges, n):
        t.threads = []
        for i in range(n):
            th = threading.Thread(target=self._segment_worker, args=(t, i, ranges), daemon=True)
            t.threads.append(th)
            th.start()

    def _segment_worker(self, t: Task, idx, ranges):
        start, end = ranges[idx]
        pool = self._mirror_cycle(0)
        mpos = 0
        fails = 0
        max_fail = int(self.state.config.get("failover_max", 5)) if self.state.config.get("failover") else 1
        while not t.stop_evt.is_set():
            if t.pause_evt.is_set():
                return
            pos = start + t.seg_done[idx]
            if pos > end:
                return
            m = pool[mpos % len(pool)] if pool else None
            if m is None:
                raise HttpError("没有可用镜像")
            url = mirror_url(m, t.url)
            pos = start + t.seg_done[idx]
            try:
                req = urllib.request.Request(url, headers={
                    "Range": f"bytes={pos}-{end}", "User-Agent": UA, "Accept": "*/*"})
                with make_opener(self.state.config["proxy"]).open(
                        req, timeout=int(self.state.config["timeout"])) as r:
                    if r.status != 206:
                        raise HttpError(f"HTTP {r.status}")
                    with open(t.part, "r+b") as f:
                        f.seek(pos)
                        while pos <= end and not t.stop_evt.is_set():
                            if t.pause_evt.is_set():
                                return
                            chunk = r.read(min(65536, end - pos + 1))
                            if not chunk:
                                break
                            f.write(chunk)
                            pos += len(chunk)
                            with t.lock:
                                t.seg_done[idx] += len(chunk)
                                t.done = sum(t.seg_done)
                                t.mirror = m["name"]
                        f.flush()
                with t.lock:
                    t.attempts += 1
                m["fail"] = 0
                if pos > end:
                    return
                fails += 1
                if fails > max_fail:
                    raise HttpError("分片重试次数用完")
                mpos += 1
            except Exception:
                fails += 1
                m["fail"] = int(m.get("fail") or 0) + 1
                if t.stop_evt.is_set():
                    return
                if fails > max_fail:
                    raise HttpError("镜像都试过了，还是没连上")
                mpos += 1
                time.sleep(0.15)

    def _download_single(self, t: Task, mirror):
        t.status = "running"
        pool = self._mirror_cycle(0)
        pos = 0
        if t.part.exists() and self.state.config.get("resume"):
            pos = t.part.stat().st_size
        fails = 0
        max_fail = int(self.state.config.get("failover_max", 5)) if self.state.config.get("failover") else 1
        while pos < (t.total or 10 ** 15):
            if t.stop_evt.is_set():
                return
            while t.pause_evt.is_set() and not t.stop_evt.is_set():
                time.sleep(0.12)
            m = pool[fails % len(pool)] if pool else None
            if not m:
                raise HttpError("没有可用镜像")
            try:
                headers = {"User-Agent": UA, "Accept": "*/*"}
                if pos:
                    headers["Range"] = f"bytes={pos}-"
                req = urllib.request.Request(mirror_url(m, t.url), headers=headers)
                with make_opener(self.state.config["proxy"]).open(
                        req, timeout=int(self.state.config["timeout"])) as r:
                    if r.status == 200 and pos:
                        pos = 0
                    mode = "ab" if pos else "wb"
                    with open(t.part, mode) as f:
                        t.mirror = m["name"]
                        while True:
                            if t.stop_evt.is_set():
                                return
                            while t.pause_evt.is_set() and not t.stop_evt.is_set():
                                time.sleep(0.12)
                            chunk = r.read(131072)
                            if not chunk:
                                break
                            f.write(chunk)
                            pos += len(chunk)
                            with t.lock:
                                t.done = pos
                        f.flush()
                if t.total and pos < t.total:
                    fails += 1
                    if fails > max_fail:
                        raise HttpError("下载中断，重试次数用完")
                    continue
                return
            except Exception:
                fails += 1
                m["fail"] = int(m.get("fail") or 0) + 1
                if fails > max_fail:
                    raise HttpError("镜像都试过了，还是没连上")
                time.sleep(0.2)

    def _monitor(self, t: Task):
        last_done, last_t = t.done, time.time()
        while any(th.is_alive() for th in t.threads):
            time.sleep(0.35)
            now = time.time()
            dt = max(0.001, now - last_t)
            with t.lock:
                d = t.done
            inst = max(0.0, (d - last_done) / dt / 1048576.0)
            t.speed = inst if inst > 0.01 else t.speed * 0.55
            last_done, last_t = d, now
            if t.status == "paused":
                pass

    def pause(self, t: Task):
        if t.status in ("running", "probing"):
            t.pause_evt.set()
            t.status = "paused"
            t.speed = 0

    def resume(self, t: Task):
        if t.status == "paused":
            t.pause_evt.clear()
            t.started_at = time.time() - t.elapsed
            t.threads = []
            t.status = "queued"
            self.pump()

    def cancel(self, t: Task):
        t.stop_evt.set()
        t.pause_evt.clear()
        t.status = "canceled"
        t.finished_at = time.time()
        t.speed = 0
        try:
            if t.part.exists():
                t.part.unlink(missing_ok=True)
        except Exception:
            pass

    def clear_finished(self):
        with self.lock:
            self.tasks = [t for t in self.tasks if t.status in ("queued", "probing", "running", "paused")]


class SpeedTestRunner:
    """后台逐个测速，结果通过回调抛回 UI 线程。"""

    def __init__(self, state: State, on_result, on_done=None):
        self.state = state
        self.on_result = on_result
        self.on_done = on_done
        self.cancel_evt = threading.Event()
        self.threads = []

    def start(self, url: str):
        self.cancel_evt.set()
        self.cancel_evt = threading.Event()
        mir = [m for m in self.state.mirrors if m.get("enabled", True)]
        chunk = int(self.state.config.get("probe_kb", 256))
        timeout = int(self.state.config.get("timeout", 8))
        proxy = self.state.config.get("proxy", "")

        def run():
            ok_n = 0
            best = None
            log_line("测速", f"开始 · {url} · {len(mir)} 个镜像 · 分片 {chunk} KB · 超时 {timeout}s")
            for m in mir:
                if self.cancel_evt.is_set():
                    log_line("测速", f"已取消 · {url} · 已完成 {ok_n}/{len(mir)}")
                    return
                ok, lat, sp = probe_mirror(m, url, chunk, timeout, proxy)
                m["checked"] = now_ms()
                m["latency"] = lat if ok else 0
                m["speed"] = sp if ok else 0.0
                m["fail"] = 0 if ok else int(m.get("fail") or 0) + 1
                if ok:
                    ok_n += 1
                    if best is None or sp > best[1]:
                        best = (m["name"], sp)
                    log_line("测速", f"成功 · {m['name']} · {lat} ms · {sp:.2f} MB/s")
                else:
                    log_line("测速", f"失败 · {m['name']} · 连续失败 {m['fail']} 次")
                if not self.cancel_evt.is_set():
                    self.on_result(m, ok, lat, sp)
            self.state.save()
            tail = f" · 最快 {best[0]} {best[1]:.2f} MB/s" if best else " · 全部失败"
            log_line("测速", f"结束 · 成功 {ok_n}/{len(mir)}{tail}")
            if self.on_done and not self.cancel_evt.is_set():
                self.on_done()

        th = threading.Thread(target=run, daemon=True)
        self.threads = [th]
        th.start()

    def cancel(self):
        self.cancel_evt.set()
# --------------------------------------------------------------------------
# 主题 / 视觉基础
# --------------------------------------------------------------------------

from PySide6.QtCore import (Qt, QTimer, QRect, QRectF, QPoint, QPointF, QSize, QByteArray,
                            QEasingCurve, QPropertyAnimation, QVariantAnimation, Signal, QObject,
                            QCollator, QLocale)
from PySide6.QtGui import (QColor, QPainter, QPainterPath, QLinearGradient, QFont,
                           QPixmap, QIcon, QPen, QBrush, QFontMetrics, QCursor, QIntValidator)
from PySide6.QtSvg import QSvgRenderer
from PySide6.QtWidgets import (QApplication, QMainWindow, QWidget, QFrame, QLabel,
                               QPushButton, QLineEdit, QScrollArea, QVBoxLayout,
                               QHBoxLayout, QGridLayout, QStackedWidget, QSizePolicy,
                               QAbstractButton, QDialog, QFileDialog, QGraphicsDropShadowEffect,
                               QSpacerItem, QLayout, QComboBox, QCheckBox, QToolButton,
                               QPlainTextEdit, QMessageBox, QButtonGroup, QMenu)

PALETTES = {
    "dark": {
        "bg": "#0B0F14", "sidebar": "#0E141B", "surface": "#141B24", "surface2": "#1B242F",
        "line": "#14FFFFFF", "line2": "#24FFFFFF", "text": "#E8EEF6", "text2": "#8B9AAC",
        "text3": "#5C6B7C", "primary": "#2BE3A7", "primary2": "#0E8F6B",
        "on_primary": "#04140F", "primary_soft": "#1F2BE3A7", "accent": "#4F8CFF",
        "accent_soft": "#244F8CFF", "warn": "#FFB020", "danger": "#FF5C5C",
        "danger_soft": "#1FFF5C5C", "scroll": "#1FFFFFFF", "hover": "#0AFFFFFF",
    },
    "light": {
        "bg": "#F4F6FA", "sidebar": "#FFFFFF", "surface": "#FFFFFF", "surface2": "#EEF2F7",
        "line": "#DFE6EE", "line2": "#C9D4E0", "text": "#0F1A24", "text2": "#5A6B7C",
        "text3": "#93A1B0", "primary": "#07966A", "primary2": "#067A56",
        "on_primary": "#FFFFFF", "primary_soft": "#1407966A", "accent": "#2F6FE4",
        "accent_soft": "#142F6FE4", "warn": "#C77A00", "danger": "#D93B3B",
        "danger_soft": "#14D93B3B", "scroll": "#220F1A24", "hover": "#080F1A24",
    },
    "mono": {
        "bg": "#FFFFFF", "sidebar": "#FFFFFF", "surface": "#FFFFFF", "surface2": "#F2F2F2",
        "line": "#000000", "line2": "#000000", "text": "#000000", "text2": "#333333",
        "text3": "#666666", "primary": "#000000", "primary2": "#000000",
        "on_primary": "#FFFFFF", "primary_soft": "#10000000", "accent": "#000000",
        "accent_soft": "#10000000", "warn": "#000000", "danger": "#000000",
        "danger_soft": "#10000000", "scroll": "#44000000", "hover": "#08000000",
    },
}

P = dict(PALETTES["dark"])


def set_theme(name: str):
    P.clear()
    P.update(PALETTES.get(name, PALETTES["dark"]))


def rgba(hex_color: str, alpha: float):
    c = QColor(hex_color)
    return f"rgba({c.red()},{c.green()},{c.blue()},{max(0, min(255, int(round(alpha * 255))))})"


def with_alpha(color_hex: str, alpha: float) -> QColor:
    c = QColor(color_hex)
    c.setAlphaF(max(0.0, min(1.0, alpha)))
    return c


def build_qss():
    p = P
    hero_top = rgba(p["primary"], 0.06) if p["line"] != "#000000" else p["surface"]
    return f"""
    QWidget {{
        color: {p['text']};
        font-family: "Microsoft YaHei UI", "PingFang SC", "Noto Sans CJK SC", "Segoe UI", sans-serif;
        font-size: 13px;
    }}
    QMainWindow, QWidget#root, QWidget#pageBody {{ background: {p['bg']}; }}
    QWidget#sidebar {{ background: {p['sidebar']}; border-right: 1px solid {p['line']}; }}
    QScrollArea {{ background: transparent; border: none; }}
    QScrollArea > QWidget > QWidget {{ background: transparent; }}
    QFrame#card {{ background: {p['surface']}; border: 1px solid {p['line']}; border-radius: 18px; }}
    QFrame#card[tone="hero"] {{
        background: qlineargradient(x1:0, y1:0, x2:0.35, y2:1,
            stop:0 {rgba(p['primary'], 0.07)}, stop:0.55 {p['surface']}, stop:1 {p['surface']});
        border: 1px solid {rgba(p['primary'], 0.16)};
    }}
    QFrame#card[tone="empty"] {{ background: transparent; border: 1px dashed {p['line2']}; border-radius: 16px; }}
    QFrame#inner {{ background: {p['surface2']}; border: 1px solid {p['line']}; border-radius: 12px; }}
    QFrame#hline {{ background: {p['line']}; max-height: 1px; min-height: 1px; border: none; }}
    QPushButton {{ border: none; background: transparent; outline: none; }}
    QPushButton:focus {{ outline: none; }}
    QPushButton[variant="primary"] {{
        background: {p['primary']}; color: {p['on_primary']}; border-radius: 12px;
        padding: 0 20px; font-weight: 700; font-size: 14px; min-height: 44px;
    }}
    QPushButton[variant="primary"]:hover {{ background: {p['primary2'] if p['primary'] != '#000000' else '#000000'}; }}
    QPushButton[variant="primary"]:disabled {{ background: {p['surface2']}; color: {p['text3']}; }}
    QPushButton[variant="ghost"] {{
        background: {p['surface2']}; color: {p['text']}; border: 1px solid {p['line']};
        border-radius: 10px; padding: 0 14px; min-height: 34px; font-size: 12.5px;
    }}
    QPushButton[variant="ghost"]:hover {{ background: {p['hover']}; border-color: {p['line2']}; }}
    QPushButton[variant="ghost"]:disabled {{ color: {p['text3']}; }}
    QPushButton[variant="plain"] {{ color: {p['text2']}; padding: 0 8px; min-height: 30px; font-size: 12.5px; }}
    QPushButton[variant="plain"]:hover {{ color: {p['text']}; }}
    QPushButton[variant="danger"] {{
        background: {p['danger_soft']}; color: {p['danger']}; border: 1px solid {rgba(p['danger'], .3)};
        border-radius: 10px; padding: 0 14px; min-height: 34px; font-size: 12.5px;
    }}
    QPushButton[variant="danger"]:hover {{ background: {rgba(p['danger'], .22)}; }}
    QPushButton[nav="true"] {{
        text-align: left; padding-left: 14px; border-radius: 10px; min-height: 42px;
        color: {p['text2']}; font-size: 13px; border: none;
    }}
    QPushButton[nav="true"]:hover {{ background: {p['hover']}; color: {p['text']}; }}
    QPushButton[nav="true"]:checked {{
        background: {p['primary_soft']}; color: {p['primary']}; font-weight: 700;
    }}
    QPushButton[chip="true"] {{
        background: {p['surface2']}; color: {p['text2']}; border: 1px solid {p['line']};
        border-radius: 15px; padding: 0 13px; min-height: 29px; max-height: 29px; font-size: 11.5px;
    }}
    QPushButton[chip="true"]:hover {{ color: {p['text']}; border-color: {p['line2']}; }}
    QPushButton[chip="true"]:checked {{
        background: {p['primary_soft']}; color: {p['primary']};
        border-color: {rgba(p['primary'], .35)}; font-weight: 600;
    }}
    QLineEdit {{
        background: {p['surface2']}; border: 1px solid {p['line']}; border-radius: 10px;
        padding: 0 12px; min-height: 40px; font-size: 12.5px; color: {p['text']};
        selection-background-color: {p['primary']}; selection-color: {p['on_primary']};
    }}
    QLineEdit:focus {{ border-color: {rgba(p['primary'], .55)}; }}
    QLineEdit[mono="true"] {{ font-family: "Cascadia Mono", "Consolas", "SF Mono", "DejaVu Sans Mono", monospace; }}
    QComboBox {{
        background: {p['surface2']}; border: 1px solid {p['line']}; border-radius: 8px;
        padding: 4px 10px; min-height: 28px; color: {p['text']};
    }}
    QComboBox QAbstractItemView {{
        background: {p['surface']}; border: 1px solid {p['line2']}; border-radius: 8px;
        selection-background-color: {p['primary_soft']}; selection-color: {p['primary']}; outline: none;
    }}
    QMenu {{
        background: {p['surface']}; border: 1px solid {p['line2']}; border-radius: 10px;
        padding: 6px; color: {p['text']};
    }}
    QMenu::item {{
        padding: 7px 26px 7px 12px; border-radius: 7px; margin: 1px 2px;
    }}
    QMenu::item:selected {{ background: {p['primary_soft']}; color: {p['primary']}; }}
    QMenu::separator {{ height: 1px; background: {p['line']}; margin: 6px 8px; }}
    QMenu::indicator {{ width: 14px; height: 14px; left: 8px; }}
    QScrollBar:vertical {{ background: transparent; width: 9px; margin: 2px; }}
    QScrollBar::handle:vertical {{ background: {p['scroll']}; border-radius: 4px; min-height: 36px; }}
    QScrollBar::handle:vertical:hover {{ background: {p['line2']}; }}
    QScrollBar::add-line, QScrollBar::sub-line {{ height: 0; }}
    QScrollBar::add-page, QScrollBar::sub-page {{ background: transparent; }}
    QToolTip {{
        background: {p['surface2']}; color: {p['text']}; border: 1px solid {p['line2']};
        border-radius: 6px; padding: 4px 8px;
    }}
    QCheckBox {{ color: {p['text']}; spacing: 8px; }}
    QDialog {{ background: {p['bg']}; }}
    QMenu {{ background: {p['surface']}; border: 1px solid {p['line2']}; border-radius: 8px; padding: 6px; }}
    QMenu::item {{ padding: 7px 18px; border-radius: 6px; }}
    QMenu::item:selected {{ background: {p['primary_soft']}; color: {p['primary']}; }}
    """


# --------------------------------------------------------------------------
# 图标（内联 SVG，不依赖外部资源）
# --------------------------------------------------------------------------

ICON_PATHS = {
    "download": '<path d="M12 3.5v11.2m0 0-4-3.9m4 3.9 4-3.9"/><path d="M4.5 16.5v2.2a1.8 1.8 0 0 0 1.8 1.8h11.4a1.8 1.8 0 0 0 1.8-1.8v-2.2"/>',
    "versions": '<circle cx="12" cy="12" r="8.4"/><path d="M12 16.2V7.8m-3.4 3.4L12 7.8l3.4 3.4"/>',
    "history": '<circle cx="12" cy="12" r="8.4"/><path d="M12 7.6V12l3.1 1.9"/>',
    "mirror": '<circle cx="12" cy="12" r="8.6"/><path d="M3.5 12h17M12 3.4c2.3 2.4 3.5 5.3 3.5 8.6s-1.2 6.2-3.5 8.6c-2.3-2.4-3.5-5.3-3.5-8.6S9.7 5.8 12 3.4z"/>',
    "settings": '<path d="M4 7.2h9.4M18.6 7.2H20M4 16.8h3.4M12.6 16.8H20"/><circle cx="16" cy="7.2" r="2.1"/><circle cx="10" cy="16.8" r="2.1"/>',
    "paste": '<rect x="8" y="3" width="8" height="4" rx="1.4"/><path d="M16 5h2a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2h2"/>',
    "bolt": '<path d="M13 2.5 4.8 13.4H11l-.9 8.1L19.2 10H13z"/>',
    "folder": '<path d="M3.5 7.5A2 2 0 0 1 5.5 5.5h4l2 2.4h7a2 2 0 0 1 2 2v8.1a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2z"/>',
    "trash": '<path d="M4 7h16M9.5 7V5h5v2M6.5 7l.9 12.1A1.9 1.9 0 0 0 9.3 21h5.4a1.9 1.9 0 0 0 1.9-1.9L17.5 7"/>',
    "plus": '<path d="M12 5v14M5 12h14"/>',
    "refresh": '<path d="M20 12a8 8 0 1 1-2.4-5.7"/><path d="M20 4v5h-5"/>',
    "check": '<path d="M5 12.5 9.4 17 19 6.8"/>',
    "close": '<path d="M6 6l12 12M18 6 6 18"/>',
    "chevron": '<path d="M9.5 5.5 16 12l-6.5 6.5"/>',
    "chevron-up": '<path d="M5.5 15 12 8.5 18.5 15"/>',
    "chevron-down": '<path d="M5.5 9 12 15.5 18.5 9"/>',
    "pause": '<rect x="6.5" y="4.5" width="3.8" height="15" rx="1.2"/><rect x="13.7" y="4.5" width="3.8" height="15" rx="1.2"/>',
    "play": '<path d="M7.5 4.8v14.4L19.5 12z"/>',
    "arrow-right": '<path d="M4 12h15m0 0-5.5-5.5M19 12l-5.5 5.5"/>',
    "link": '<path d="M10.5 13.5a4 4 0 0 0 5.7 0l2.3-2.3a4 4 0 1 0-5.7-5.7l-1.3 1.3"/><path d="M13.5 10.5a4 4 0 0 0-5.7 0l-2.3 2.3a4 4 0 1 0 5.7 5.7l1.3-1.3"/>',
    "info": '<circle cx="12" cy="12" r="8.6"/><path d="M12 11.2v5.2M12 7.9h.01"/>',
    "alert": '<path d="M12 3.8 21 20H3z"/><path d="M12 9.5v4.6M12 17.3h.01"/>',
    "file": '<path d="M6 3.5h7l5 5v12H6z"/><path d="M13 3.5v5h5"/>',
    "speed": '<path d="M4.2 16.8a8.4 8.4 0 1 1 15.6 0"/><path d="m12 12.6 3.6-3.2"/><circle cx="12" cy="13.6" r="1.4"/>',
    "globe": '<circle cx="12" cy="12" r="8.6"/><path d="M3.5 12h17M12 3.4c2.3 2.4 3.5 5.3 3.5 8.6s-1.2 6.2-3.5 8.6c-2.3-2.4-3.5-5.3-3.5-8.6S9.7 5.8 12 3.4z"/>',
    "clock": '<circle cx="12" cy="12" r="8.4"/><path d="M12 7.6V12l3.1 1.9"/>',
    "external": '<path d="M14 5h5v5M19 5l-8 8"/><path d="M18 14v4.2a1.8 1.8 0 0 1-1.8 1.8H5.8A1.8 1.8 0 0 1 4 18.2V7.8A1.8 1.8 0 0 1 5.8 6H10"/>',
    "shield": '<path d="M12 3.4 19 6v6.2c0 4-2.9 7-7 8.4-4.1-1.4-7-4.4-7-8.4V6z"/><path d="m9 12 2.2 2.2L15.3 10"/>',
    "tag": '<path d="M20 13.2 13.3 20a1.8 1.8 0 0 1-2.6 0L4 13.3V4h9.3l6.7 6.7a1.8 1.8 0 0 1 0 2.5z"/><circle cx="8.4" cy="8.4" r="1.2"/>',
    "grid": '<rect x="4" y="4" width="6.5" height="6.5" rx="1.6"/><rect x="13.5" y="4" width="6.5" height="6.5" rx="1.6"/><rect x="4" y="13.5" width="6.5" height="6.5" rx="1.6"/><rect x="13.5" y="13.5" width="6.5" height="6.5" rx="1.6"/>',
    "eye": '<path d="M3 12s3.2-6 9-6 9 6 9 6-3.2 6-9 6-9-6-9-6z"/><circle cx="12" cy="12" r="2.4"/>',
    "wrench": '<path d="M14.5 6.2a4.2 4.2 0 0 0 5.3 5.3l-8.2 8.2a2.2 2.2 0 0 1-3.1 0l-2.2-2.2a2.2 2.2 0 0 1 0-3.1z"/>',
    "sparkle": '<path d="M12 3.5 13.7 9l5.5 1.7-5.5 1.7L12 18l-1.7-5.6L4.8 10.7 10.3 9z"/>',
}


def icon_pixmap(name: str, color: str, size=18, width=1.8) -> QPixmap:
    path = ICON_PATHS.get(name, ICON_PATHS["file"])
    fill_icon = name in ("pause", "play", "bolt")
    if fill_icon:
        svg = (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="{color}" '
               f'stroke="none">{path}</svg>')
    else:
        svg = (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" '
               f'stroke="{color}" stroke-width="{width}" stroke-linecap="round" '
               f'stroke-linejoin="round">{path}</svg>')
    renderer = QSvgRenderer(QByteArray(svg.encode("utf-8")))
    pm = QPixmap(size, size)
    pm.fill(Qt.transparent)
    pr = QPainter(pm)
    pr.setRenderHint(QPainter.Antialiasing)
    renderer.render(pr)
    pr.end()
    return pm


def icon(name: str, color: str, size=18, width=1.8) -> QIcon:
    return QIcon(icon_pixmap(name, color, size, width))


def label(text, size=13, color=None, bold=False, wrap=False, selectable=False) -> QLabel:
    lb = QLabel(text)
    lb.setWordWrap(wrap)
    lb.setTextInteractionFlags(Qt.TextSelectableByMouse if selectable else Qt.NoTextInteraction)
    weight = 700 if bold else 400
    lb.setStyleSheet(f"color:{color or P['text']}; font-size:{size}px; font-weight:{weight};"
                     " background:transparent;")
    return lb


def mono_label(text, size=12, color=None) -> QLabel:
    lb = label(text, size, color)
    lb.setStyleSheet(lb.styleSheet().replace(
        "font-weight", 'font-family:"Cascadia Mono","Consolas","SF Mono","DejaVu Sans Mono",monospace; font-weight'))
    return lb


def make_card(tone="", margins=(16, 16, 16, 16), spacing=10) -> QFrame:
    card = QFrame()
    card.setObjectName("card")
    if tone:
        card.setProperty("tone", tone)
    lay = QVBoxLayout(card)
    lay.setContentsMargins(*margins)
    lay.setSpacing(spacing)
    card.body = lay
    return card


def hline():
    f = QFrame()
    f.setObjectName("hline")
    f.setFixedHeight(1)
    return f


def spacer(w=0, h=0):
    return QSpacerItem(w, h, QSizePolicy.Fixed, QSizePolicy.Fixed)


def push(btn, variant=None, chip=False, nav=False):
    if variant:
        btn.setProperty("variant", variant)
    if chip:
        btn.setProperty("chip", True)
    if nav:
        btn.setProperty("nav", True)
    btn.setCursor(QCursor(Qt.PointingHandCursor))
    return btn


def button(text, variant="ghost", icon_name=None, height=None, cb=None):
    b = QPushButton(text)
    push(b, variant)
    if icon_name:
        b.setIcon(icon(icon_name, P["on_primary"] if variant == "primary" else P["text2"], 16))
        b.setIconSize(QSize(16, 16))
    if height:
        b.setFixedHeight(height)
    if cb:
        b.clicked.connect(cb)
    return b


def chip(text, checked=False, cb=None):
    c = QPushButton(text)
    c.setCheckable(True)
    c.setChecked(checked)
    push(c, chip=True)
    if cb:
        c.clicked.connect(cb)
    return c


class FlowLayout(QLayout):
    """自动换行的横向布局：标签多的时候不挤成一行，也不会被裁掉。"""

    def __init__(self, parent=None, margin=0, hspacing=6, vspacing=6):
        super().__init__(parent)
        self._items = []
        self._h = hspacing
        self._v = vspacing
        self.setContentsMargins(margin, margin, margin, margin)

    def addItem(self, item):
        self._items.append(item)

    def count(self):
        return len(self._items)

    def itemAt(self, index):
        return self._items[index] if 0 <= index < len(self._items) else None

    def takeAt(self, index):
        if 0 <= index < len(self._items):
            return self._items.pop(index)
        return None

    def expandingDirections(self):
        return Qt.Orientations(Qt.Orientation(0))

    def hasHeightForWidth(self):
        return True

    def heightForWidth(self, width):
        return self._layout_on(QRect(0, 0, width, 0), True)

    def setGeometry(self, rect):
        super().setGeometry(rect)
        self._layout_on(rect, False)

    def sizeHint(self):
        return self.minimumSize()

    def minimumSize(self):
        size = QSize()
        for item in self._items:
            size = size.expandedTo(item.minimumSize())
        m = self.contentsMargins()
        return size + QSize(m.left() + m.right(), m.top() + m.bottom())

    def _layout_on(self, rect, test_only):
        m = self.contentsMargins()
        left = rect.x() + m.left()
        right = rect.right() - m.right()
        x, y, line_h = left, rect.y() + m.top(), 0
        for item in self._items:
            hint = item.sizeHint()
            w, h = hint.width(), hint.height()
            if x + w > right and line_h > 0:
                x = left
                y += line_h + self._v
                line_h = 0
            if not test_only:
                item.setGeometry(QRect(QPoint(x, y), hint))
            x += w + self._h
            line_h = max(line_h, h)
        return y + line_h + m.bottom() - rect.y()


class VariantChip(QFrame):
    """「差异标签 + 格式」两段式选择块。

    用 QLabel 拼而不是 QPushButton，标签和格式各自有字号与颜色；
    标签太长时自动换行、缩字号，绝不把文字裁成「… · EX」。
    """

    clicked = Signal()

    def __init__(self, text, ext="", checked=False, parent=None, max_width=280):
        super().__init__(parent)
        self.setObjectName("vchip")
        self.setCursor(QCursor(Qt.PointingHandCursor))
        self.setSizePolicy(QSizePolicy.Maximum, QSizePolicy.Fixed)
        lay = QHBoxLayout(self)
        lay.setContentsMargins(10, 4, 10, 4)
        lay.setSpacing(5)
        self.label = QLabel(text or "自动")
        self.label.setWordWrap(True)
        self.label.setSizePolicy(QSizePolicy.Preferred, QSizePolicy.Minimum)
        lay.addWidget(self.label)
        self.ext = None
        if ext:
            dot = QLabel("·")
            dot.setStyleSheet(f"background:transparent;border:none;color:{P['text3']};font-size:10px;")
            dot.setAlignment(Qt.AlignTop | Qt.AlignHCenter)
            lay.addWidget(dot)
            self.ext = QLabel(ext)
            self.ext.setAlignment(Qt.AlignTop | Qt.AlignLeft)
            lay.addWidget(self.ext)
        self.set_checked(checked)
        self._fit(max_width)

    def _fit(self, max_width):
        size = 12.0 if len(self.label.text()) <= 16 else 11.0
        self.label.setStyleSheet(
            "background:transparent;border:none;font-weight:600;"
            f"font-size:{size}px;color:{P['primary'] if self._checked else P['text2']};")
        if self.ext:
            self.ext.setStyleSheet(
                "background:transparent;border:none;font-size:9.5px;font-weight:600;"
                f"color:{P['primary'] if self._checked else P['text3']};")
        # 先按单行量一遍：布局自己算出来的宽度才是准的，手算字体宽度会被
        # 中英文混排、字号继承坑到，最后把标签压成两行
        self.label.setWordWrap(False)
        lay = self.layout()
        lay.invalidate()
        lay.activate()
        single = lay.sizeHint().width() + 2
        if single <= max_width:
            self.setFixedWidth(single)
            self.setFixedHeight(30)
            return
        # 太长：限制宽度让它折行，高度跟着行数走
        extra = max(0, single - self.label.sizeHint().width())
        self.label.setWordWrap(True)
        self.setFixedWidth(max_width)
        self.label.setFixedWidth(max_width - extra - 2)
        self.setFixedHeight(max(30, self.label.heightForWidth(max_width - extra - 2) + 10))

    def set_checked(self, on):
        self._checked = bool(on)
        self.label.setStyleSheet(
            "background:transparent;border:none;font-weight:600;"
            f"font-size:{12 if len(self.label.text()) <= 16 else 11}px;"
            f"color:{P['primary'] if on else P['text2']};")
        if self.ext:
            self.ext.setStyleSheet(
                "background:transparent;border:none;font-size:9.5px;font-weight:600;"
                f"color:{P['primary'] if on else P['text3']};")
        bg = P["primary_soft"] if on else P["surface"]
        bd = rgba(P["primary"], 0.45) if on else P["line"]
        self.setStyleSheet(
            f"QFrame#vchip{{background:{bg};border:1px solid {bd};border-radius:9px;}}")

    def mousePressEvent(self, ev):
        if ev.button() == Qt.LeftButton:
            self.clicked.emit()
        super().mousePressEvent(ev)


def icon_button(icon_name, size=36, tip="", cb=None, color=None):
    b = QPushButton()
    b.setFixedSize(size, size)
    b.setIcon(icon(icon_name, color or P["text2"], 17))
    b.setIconSize(QSize(17, 17))
    b.setCursor(QCursor(Qt.PointingHandCursor))
    b.setStyleSheet(f"QPushButton{{background:{P['surface2']};border:1px solid {P['line']};border-radius:9px;}}"
                    f"QPushButton:hover{{border-color:{P['line2']};}}")
    if tip:
        b.setToolTip(tip)
    if cb:
        b.clicked.connect(cb)
    return b


# --------------------------------------------------------------------------
# 自绘小部件
# --------------------------------------------------------------------------


class Switch(QAbstractButton):
    def __init__(self, checked=False, on_change=None, parent=None):
        super().__init__(parent)
        self.setCheckable(True)
        self.setChecked(checked)
        self.setFixedSize(44, 24)
        self.setCursor(QCursor(Qt.PointingHandCursor))
        self._pos = 1.0 if checked else 0.0
        self._anim = QVariantAnimation(self)
        self._anim.setDuration(130)
        self._anim.setEasingCurve(QEasingCurve.OutCubic)
        self._anim.valueChanged.connect(self._on_anim)
        self.toggled.connect(self._toggle)
        if on_change:
            self.toggled.connect(on_change)

    def _on_anim(self, v):
        self._pos = float(v)
        self.update()

    def _toggle(self, on):
        self._anim.stop()
        self._anim.setStartValue(self._pos)
        self._anim.setEndValue(1.0 if on else 0.0)
        self._anim.start()

    def paintEvent(self, ev):
        pr = QPainter(self)
        pr.setRenderHint(QPainter.Antialiasing)
        r = QRectF(1, 2, self.width() - 2, self.height() - 4)
        track_off = QColor(P["surface2"])
        track_on = QColor(P["primary"])
        c = QColor(track_off)
        c.setRed(int(track_off.red() + (track_on.red() - track_off.red()) * self._pos))
        c.setGreen(int(track_off.green() + (track_on.green() - track_off.green()) * self._pos))
        c.setBlue(int(track_off.blue() + (track_on.blue() - track_off.blue()) * self._pos))
        pr.setPen(QPen(QColor(P["line"]), 1))
        pr.setBrush(QBrush(c))
        pr.drawRoundedRect(r, r.height() / 2, r.height() / 2)
        d = self.height() - 8
        x = 5 + self._pos * (self.width() - d - 10)
        pr.setPen(QPen(QColor(0, 0, 0, 40), 1))
        pr.setBrush(QBrush(QColor("#FFFFFF")))
        pr.drawEllipse(QRectF(x, 4, d, d))
        pr.end()


class Check(QAbstractButton):
    """18px 圆角勾选框，自绘，避免各平台原生样式割裂。"""

    def __init__(self, checked=False, on_change=None, parent=None):
        super().__init__(parent)
        self.setCheckable(True)
        self.setChecked(checked)
        self.setFixedSize(18, 18)
        self.setCursor(QCursor(Qt.PointingHandCursor))
        self.toggled.connect(self.update)
        if on_change:
            self.toggled.connect(lambda v: on_change(bool(v)))

    def paintEvent(self, ev):
        pr = QPainter(self)
        pr.setRenderHint(QPainter.Antialiasing)
        r = QRectF(1, 1, 16, 16)
        if self.isChecked():
            pr.setPen(Qt.NoPen)
            pr.setBrush(QBrush(QColor(P["primary"])))
            pr.drawRoundedRect(r, 5, 5)
            path = QPainterPath(QPointF(4.8, 9.2))
            path.lineTo(7.6, 12.0)
            path.lineTo(13.2, 6.2)
            pr.setBrush(Qt.NoBrush)
            pr.setPen(QPen(QColor(P["on_primary"]), 1.9, Qt.SolidLine, Qt.RoundCap, Qt.RoundJoin))
            pr.drawPath(path)
        else:
            pr.setPen(QPen(QColor(P["line2"]), 1.2))
            pr.setBrush(QBrush(QColor(P["surface2"])))
            pr.drawRoundedRect(r, 5, 5)
        pr.end()


class Progress(QWidget):
    def __init__(self, value=0.0, height=8, parent=None):
        super().__init__(parent)
        self._v = value
        self._h = height
        self.setFixedHeight(height)
        self.setSizePolicy(QSizePolicy.Expanding, QSizePolicy.Fixed)

    def set_value(self, v):
        v = max(0.0, min(1.0, v))
        if abs(v - self._v) > 0.0005:
            self._v = v
            self.update()

    def paintEvent(self, ev):
        pr = QPainter(self)
        pr.setRenderHint(QPainter.Antialiasing)
        r = QRectF(0, 0, self.width(), self._h)
        pr.setPen(Qt.NoPen)
        pr.setBrush(QBrush(QColor(P["surface2"])))
        pr.drawRoundedRect(r, self._h / 2, self._h / 2)
        if self._v > 0:
            w = max(self._h, self.width() * self._v)
            g = QLinearGradient(0, 0, w, 0)
            g.setColorAt(0, QColor(P["primary"]))
            g.setColorAt(1, QColor(P["primary"] if P["primary"] == "#000000" else "#5CF0C0"))
            pr.setBrush(QBrush(g))
            pr.drawRoundedRect(QRectF(0, 0, w, self._h), self._h / 2, self._h / 2)
        pr.end()


class Spark(QWidget):
    """速度波形：最近 60 个采样。"""

    def __init__(self, height=34, parent=None):
        super().__init__(parent)
        self.samples = []
        self.setFixedHeight(height)
        self.setSizePolicy(QSizePolicy.Expanding, QSizePolicy.Fixed)

    def push(self, v):
        self.samples.append(max(0.0, float(v)))
        if len(self.samples) > 60:
            self.samples = self.samples[-60:]
        self.update()

    def paintEvent(self, ev):
        pr = QPainter(self)
        pr.setRenderHint(QPainter.Antialiasing)
        w, h = self.width(), self.height()
        if len(self.samples) < 2:
            pr.setPen(QPen(QColor(P["line"]), 1))
            pr.drawLine(0, int(h - 2), w, int(h - 2))
            pr.end()
            return
        mx = max(self.samples) or 1.0
        pts = []
        n = len(self.samples)
        for i, s in enumerate(self.samples):
            x = w * i / max(1, n - 1)
            y = h - 3 - (s / mx) * (h - 8)
            pts.append(QPointF(x, y))
        path = QPainterPath(QPointF(0, h))
        path.lineTo(pts[0])
        for p in pts[1:]:
            path.lineTo(p)
        path.lineTo(w, h)
        path.closeSubpath()
        g = QLinearGradient(0, 0, 0, h)
        g.setColorAt(0, with_alpha(P["primary"], 0.26))
        g.setColorAt(1, with_alpha(P["primary"], 0.0))
        pr.setPen(Qt.NoPen)
        pr.setBrush(QBrush(g))
        pr.drawPath(path)
        line = QPainterPath(pts[0])
        for p in pts[1:]:
            line.lineTo(p)
        pr.setBrush(Qt.NoBrush)
        pr.setPen(QPen(QColor(P["primary"]), 1.4))
        pr.drawPath(line)
        pr.end()


class SegSlider(QWidget):
    """多段滑块：可点、可拖、松手吸附；滑块位置与文字高亮严格对齐。"""

    changed = Signal(int)

    def __init__(self, options=("单个下载", "批量勾选"), index=0, parent=None):
        super().__init__(parent)
        self.options = list(options) or ["选项"]
        self.index = max(0, min(len(self.options) - 1, int(index)))
        self.setFixedHeight(46)
        self.setMinimumWidth(300)
        self.setCursor(QCursor(Qt.PointingHandCursor))
        self.setFocusPolicy(Qt.StrongFocus)
        self._drag = False
        self._thumb = float(self.index)
        self._anim = QVariantAnimation(self)
        self._anim.setDuration(150)
        self._anim.setEasingCurve(QEasingCurve.OutCubic)
        self._anim.valueChanged.connect(self._on_anim)

    def sizeHint(self):
        return QSize(max(300, 132 * len(self.options)), 46)

    def _on_anim(self, v):
        self._thumb = float(v)
        self.update()

    def _target(self, idx):
        return float(idx)

    def set_index(self, idx, emit=False):
        idx = max(0, min(len(self.options) - 1, int(idx)))
        changed = idx != self.index
        self.index = idx
        self._anim.stop()
        if abs(self._thumb - idx) > 0.001:
            self._anim.setStartValue(self._thumb)
            self._anim.setEndValue(self._target(idx))
            self._anim.start()
        else:
            self._thumb = float(idx)
            self.update()
        if emit and changed:
            self.changed.emit(idx)

    def _slot_at(self, x):
        n = len(self.options)
        idx = int(x / max(1.0, self.width() / n))
        return max(0, min(n - 1, idx))

    def _thumb_at(self, x):
        """把指针位置换算成滑块进度，让滑块中心跟手。"""
        n = len(self.options)
        pad = 4.0
        tw = self.width() / n - pad
        span = max(1.0, self.width() - 2 * pad - tw)
        t = (x - pad - tw / 2) / span * (n - 1)
        return max(0.0, min(float(n - 1), t))

    def mousePressEvent(self, ev):
        if ev.button() != Qt.LeftButton:
            return
        self._drag = True
        self._anim.stop()
        self._thumb = self._thumb_at(ev.position().x())
        self.update()

    def mouseMoveEvent(self, ev):
        if self._drag:
            self._thumb = self._thumb_at(ev.position().x())
            self.update()

    def mouseReleaseEvent(self, ev):
        if not self._drag:
            return
        self._drag = False
        idx = self._slot_at(ev.position().x())
        self._anim.stop()
        self.set_index(idx, emit=True)

    def keyPressEvent(self, ev):
        if ev.key() in (Qt.Key_Left, Qt.Key_Up):
            self.set_index(self.index - 1, emit=True)
        elif ev.key() in (Qt.Key_Right, Qt.Key_Down):
            self.set_index(self.index + 1, emit=True)
        else:
            super().keyPressEvent(ev)

    def paintEvent(self, ev):
        pr = QPainter(self)
        pr.setRenderHint(QPainter.Antialiasing)
        w, h = float(self.width()), float(self.height())
        n = len(self.options)
        pr.setPen(QPen(QColor(P["line"]), 1))
        pr.setBrush(QBrush(QColor(P["surface2"])))
        pr.drawRoundedRect(QRectF(0.5, 0.5, w - 1, h - 1), 12, 12)
        pad = 4.0
        tw = w / n - pad
        tx = pad + self._thumb * (w - 2 * pad - tw)
        thumb = QRectF(tx, pad, tw, h - 2 * pad)
        pr.setPen(Qt.NoPen)
        pr.setBrush(QBrush(QColor(P["primary"])))
        pr.drawRoundedRect(thumb, 9, 9)
        active_idx = int(round(self._thumb))
        for i, opt in enumerate(self.options):
            cell = QRectF(i * w / n, 0, w / n, h)
            active = i == active_idx
            pr.setPen(QPen(QColor(P["on_primary"] if active else P["text2"])))
            f = pr.font()
            f.setPixelSize(12)
            f.setBold(active)
            pr.setFont(f)
            pr.drawText(cell, Qt.AlignCenter, opt)
        pr.end()


class EmptyState(QFrame):
    def __init__(self, icon_name="download", title="还没有任务", sub="贴上 GitHub 链接，选一条最快的链路", parent=None):
        super().__init__(parent)
        self.setObjectName("card")
        self.setProperty("tone", "empty")
        lay = QVBoxLayout(self)
        lay.setContentsMargins(24, 34, 24, 34)
        lay.setSpacing(8)
        ic = QLabel()
        ic.setPixmap(icon_pixmap(icon_name, P["text3"], 30, 1.5))
        ic.setAlignment(Qt.AlignCenter)
        lay.addWidget(ic)
        t = label(title, 13.5, P["text2"], bold=True)
        t.setAlignment(Qt.AlignCenter)
        lay.addWidget(t)
        s = label(sub, 11.5, P["text3"], wrap=True)
        s.setAlignment(Qt.AlignCenter)
        lay.addWidget(s)


class Toast(QLabel):
    def __init__(self, parent):
        super().__init__(parent)
        self.setVisible(False)
        self.setAttribute(Qt.WA_TransparentForMouseEvents)
        self._timer = QTimer(self)
        self._timer.setSingleShot(True)
        self._timer.timeout.connect(self.hide)

    def show_message(self, text, kind="info", ms=2600):
        color = {"ok": P["primary"], "err": P["danger"], "warn": P["warn"]}.get(kind, P["text"])
        self.setText(text)
        self.setStyleSheet(
            f"background:{P['surface2']}; color:{color}; border:1px solid {P['line2']};"
            f"border-radius:10px; padding:10px 18px; font-size:12.5px;")
        self.adjustSize()
        p = self.parentWidget()
        if p:
            self.move(int((p.width() - self.width()) / 2), int(p.height() - self.height() - 88))
        self.raise_()
        self.show()
        self._timer.start(ms)


# --------------------------------------------------------------------------
# 对话框
# --------------------------------------------------------------------------


class Dialog(QDialog):
    def __init__(self, parent, title, width=520):
        super().__init__(parent)
        self.setWindowTitle(title)
        self.setModal(True)
        self.setStyleSheet(build_qss())
        self.setMinimumWidth(width)
        self.root = QVBoxLayout(self)
        self.root.setContentsMargins(22, 20, 22, 18)
        self.root.setSpacing(14)
        self.header = QHBoxLayout()
        self.root.addLayout(self.header)

    def add_title(self, title, sub=""):
        box = QVBoxLayout()
        box.setSpacing(4)
        box.addWidget(label(title, 17, P["text"], bold=True))
        if sub:
            box.addWidget(label(sub, 11.5, P["text3"], wrap=True))
        self.header.addLayout(box, 1)


def dialog_buttons(dlg, ok_text="确定", cancel_text="取消", on_ok=None, danger=False):
    row = QHBoxLayout()
    row.setSpacing(10)
    row.addStretch(1)
    if cancel_text:
        b = button(cancel_text, "ghost")
        b.setMinimumWidth(88)
        b.clicked.connect(dlg.reject)
        row.addWidget(b)
    ok = button(ok_text, "danger" if danger else "primary")
    ok.setMinimumWidth(104)
    ok.clicked.connect(on_ok or dlg.accept)
    row.addWidget(ok)
    dlg.root.addLayout(row)
    return ok


def ask_choice(parent, title, message, options):
    """options: [(标题, 说明, 变体)]，返回索引，-1 = 取消"""
    dlg = Dialog(parent, title, 520)
    dlg.add_title(title)
    dlg.root.addWidget(label(message, 12.5, P["text2"], wrap=True))
    result = {"i": -1}
    for i, (name, sub, variant) in enumerate(options):
        btn = QPushButton()
        btn.setCursor(QCursor(Qt.PointingHandCursor))
        btn.setMinimumHeight(58)
        btn.setStyleSheet(
            f"QPushButton{{background:{P['surface2']};border:1px solid {P['line']};border-radius:12px;text-align:left;padding:0 14px;}}"
            f"QPushButton:hover{{border-color:{rgba(P['primary'],.5)};background:{rgba(P['primary'],.06)};}}")
        lay = QHBoxLayout(btn)
        lay.setContentsMargins(14, 0, 14, 0)
        box = QVBoxLayout()
        box.setSpacing(2)
        box.addWidget(label(name, 13, P["text"], bold=True))
        if sub:
            box.addWidget(label(sub, 11, P["text3"]))
        lay.addLayout(box, 1)
        lay.addWidget(label("›", 18, P["text3"]))
        btn.clicked.connect(lambda _=False, idx=i: (result.__setitem__("i", idx), dlg.accept()))
        dlg.root.addWidget(btn)
    cancel = button("取消", "plain")
    cancel.clicked.connect(dlg.reject)
    row = QHBoxLayout()
    row.addStretch(1)
    row.addWidget(cancel)
    dlg.root.addLayout(row)
    dlg.exec()
    return result["i"]


def ask_text(parent, title, prompt, default="", placeholder=""):
    dlg = Dialog(parent, title, 480)
    dlg.add_title(title, prompt)
    ed = QLineEdit(default)
    ed.setPlaceholderText(placeholder)
    dlg.root.addWidget(ed)
    dialog_buttons(dlg, "确定", "取消", on_ok=lambda: dlg.accept())
    ed.setFocus()
    if dlg.exec():
        return ed.text().strip()
    return None
# --------------------------------------------------------------------------
# 页面基类
# --------------------------------------------------------------------------


class PageBase(QWidget):
    def __init__(self, app):
        super().__init__()
        self.app = app
        self.setObjectName("pageBody")
        outer = QVBoxLayout(self)
        outer.setContentsMargins(0, 0, 0, 0)
        outer.setSpacing(0)
        self.head = QWidget()
        self.hlay = QHBoxLayout(self.head)
        self.hlay.setContentsMargins(30, 22, 30, 12)
        self.hlay.setSpacing(12)
        outer.addWidget(self.head)
        self.scroll = QScrollArea()
        self.scroll.setWidgetResizable(True)
        self.scroll.setFrameShape(QFrame.NoFrame)
        self.scroll.setHorizontalScrollBarPolicy(Qt.ScrollBarAlwaysOff)
        self.content = QWidget()
        self.content.setObjectName("pageBody")
        self.v = QVBoxLayout(self.content)
        self.v.setContentsMargins(30, 4, 30, 30)
        self.v.setSpacing(16)
        self.v.setAlignment(Qt.AlignTop)
        self.scroll.setWidget(self.content)
        self.outer = outer
        outer.addWidget(self.scroll, 1)

    def build_header(self, title, sub, right=None):
        box = QVBoxLayout()
        box.setSpacing(4)
        self.title_label = label(title, 24, P["text"], bold=True)
        self.sub_label = label(sub, 11.5, P["text3"])
        box.addWidget(self.title_label)
        box.addWidget(self.sub_label)
        self.hlay.addLayout(box)
        self.hlay.addStretch(1)
        if right is not None:
            self.hlay.addWidget(right, 0, Qt.AlignVCenter)

    def section(self, title, hint="", right=None):
        row = QHBoxLayout()
        row.setSpacing(8)
        row.addWidget(label(title, 13.5, P["text"], bold=True))
        if hint:
            row.addWidget(label(hint, 11, P["text3"]))
        row.addStretch(1)
        if right is not None:
            row.addWidget(right)
        return row


def pill(text, color=None, bg=None, size=11):
    lb = QLabel(text)
    color = color or P["primary"]
    bg = bg or rgba(color, 0.12)
    lb.setStyleSheet(f"color:{color}; background:{bg}; border-radius:9px;"
                     f"padding:2px 8px; font-size:{size}px; font-weight:600;")
    return lb


# --------------------------------------------------------------------------
# 首页：下载台
# --------------------------------------------------------------------------


class MirrorRow(QWidget):
    def __init__(self, mirror: dict, parent=None):
        super().__init__(parent)
        self.m = mirror
        self.setFixedHeight(54)
        lay = QHBoxLayout(self)
        lay.setContentsMargins(4, 0, 4, 0)
        lay.setSpacing(10)
        self.rank = QLabel("·")
        self.rank.setFixedSize(26, 26)
        self.rank.setAlignment(Qt.AlignCenter)
        self.rank.setStyleSheet(
            f"background:{P['surface2']}; color:{P['text3']}; border-radius:8px;"
            f"font-size:11px; font-weight:700;")
        lay.addWidget(self.rank)
        box = QVBoxLayout()
        box.setSpacing(1)
        self.name = label(mirror["name"], 13, P["text"], bold=True)
        mode = "拼 GitHub 路径" if mirror.get("mode") == "path" else ("直连" if not mirror.get("base") else "拼完整链接")
        self.sub = Elided(mode, P["text3"], 10.5)
        self.sub.setToolTip(mirror.get("base") or "github.com")
        box.addWidget(self.name)
        box.addWidget(self.sub)
        lay.addLayout(box, 1)
        self.right1 = label("待测", 11.5, P["text3"])
        self.right1.setAlignment(Qt.AlignRight | Qt.AlignVCenter)
        self.right1.setFixedWidth(142)
        lay.addWidget(self.right1)
        if mirror.get("speed"):
            self.set_result(True, int(mirror.get("latency") or 0), float(mirror.get("speed")))
        elif mirror.get("fail"):
            self.right1.setText(f"失败 {mirror['fail']} 次")
            self.right1.setStyleSheet(f"color:{P['danger']};font-size:10.5px;background:transparent;")
        elif mirror.get("checked"):
            self.right1.setText("超时")
            self.right1.setStyleSheet(f"color:{P['text3']};font-size:11px;background:transparent;")

    def set_rank(self, i):
        self.rank.setText(str(i))
        top = i == 1
        self.rank.setStyleSheet(
            f"background:{P['primary'] if top else P['surface2']};"
            f"color:{P['on_primary'] if top else P['text3']}; border-radius:8px;"
            f"font-size:11px; font-weight:700;")

    def testing(self):
        self.right1.setText("测试中…")
        self.right1.setStyleSheet(f"color:{P['text3']};font-size:11.5px;background:transparent;")
        self.setEnabled(True)

    def set_result(self, ok, lat, sp):
        if ok:
            self.setEnabled(True)
            self.right1.setText(f"{fmt_speed(sp)} · {lat} ms")
            self.right1.setStyleSheet(f"color:{P['primary']};font-size:11px;font-weight:700;background:transparent;")
        else:
            self.right1.setText("不可用")
            self.right1.setStyleSheet(f"color:{P['danger']};font-size:11.5px;background:transparent;")
            self.setEnabled(False)
            self.setStyleSheet("QWidget{opacity:.45;}")


class TaskCard(QFrame):
    def __init__(self, app, task, parent=None):
        super().__init__(parent)
        self.app = app
        self.task = task
        self.setObjectName("card")
        lay = QVBoxLayout(self)
        lay.setContentsMargins(14, 12, 14, 12)
        lay.setSpacing(7)

        top = QHBoxLayout()
        top.setSpacing(10)
        ic = QLabel()
        ic.setFixedSize(34, 34)
        ic.setAlignment(Qt.AlignCenter)
        ext = ext_of(task.filename)
        tone = {"exe": P["primary"], "msi": P["warn"], "zip": P["accent"],
                "rar": P["text2"], "7z": P["text2"]}.get(ext, P["text2"])
        ic.setStyleSheet(f"background:{rgba(tone, .12)};border-radius:10px;")
        ic.setPixmap(icon_pixmap("file", tone, 17))
        top.addWidget(ic)
        tbox = QVBoxLayout()
        tbox.setSpacing(2)
        self.name = Elided(task.filename, P["text"], 13, mode=Qt.ElideMiddle)
        f = self.name.font()
        f.setBold(True)
        self.name.setFont(f)
        self.sub = label("等待中…", 10.5, P["text3"])
        tbox.addWidget(self.name)
        tbox.addWidget(self.sub)
        top.addLayout(tbox, 1)
        self.pct = label("0%", 19, P["primary"], bold=True)
        self.pct.setAlignment(Qt.AlignRight | Qt.AlignVCenter)
        top.addWidget(self.pct)
        lay.addLayout(top)

        self.spark = Spark(30)
        lay.addWidget(self.spark)
        self.bar = Progress(0, 7)
        lay.addWidget(self.bar)
        self.stats = label("", 10.5, P["text3"])
        lay.addWidget(self.stats)
        self.path = Elided("", P["text3"], 9.5, mono=True)
        self.path.hide()
        lay.addWidget(self.path)
        self.err = label("", 10.5, P["danger"], wrap=True)
        self.err.hide()
        lay.addWidget(self.err)

        actions = QHBoxLayout()
        actions.setSpacing(8)
        self.pause_btn = button("暂停", "ghost", "pause", cb=self.toggle_pause)
        self.pause_btn.setFixedHeight(30)
        self.cancel_btn = button("取消", "ghost", "close", cb=self.cancel)
        self.cancel_btn.setFixedHeight(30)
        self.open_btn = button("打开文件", "ghost", "folder", cb=self.open_file)
        self.open_btn.setFixedHeight(30)
        self.open_btn.hide()
        actions.addWidget(self.pause_btn)
        actions.addWidget(self.cancel_btn)
        actions.addWidget(self.open_btn)
        actions.addStretch(1)
        lay.addLayout(actions)
        self.refresh()

    def toggle_pause(self):
        t = self.task
        if t.status == "paused":
            self.app.dm.resume(t)
        elif t.status in ("running", "probing"):
            self.app.dm.pause(t)
        self.refresh()

    def cancel(self):
        self.app.dm.cancel(self.task)
        self.refresh()

    def open_file(self):
        t = self.task
        if t.target.exists():
            open_path(t.target)
        else:
            reveal_path(t.target)

    def refresh(self):
        t = self.task
        st = t.status
        total = t.total
        pct = int(round(t.progress * 100))
        self.pct.setTextFormat(Qt.RichText)
        self.pct.setText(f"{pct}<span style='font-size:11px'>%</span>" if st not in ("done", "error", "canceled") else
                         ("✓" if st == "done" else "!"))
        if st == "done":
            self.pct.setStyleSheet(f"color:{P['primary']};font-size:19px;font-weight:700;background:transparent;")
        elif st in ("error", "canceled"):
            self.pct.setStyleSheet(f"color:{P['danger']};font-size:17px;font-weight:700;background:transparent;")
        else:
            self.pct.setStyleSheet(f"color:{P['primary']};font-size:19px;font-weight:700;background:transparent;")
        self.bar.set_value(t.progress if st != "done" else 1.0)
        if st in ("running", "probing"):
            self.sub.setText(f"via {t.mirror or '探测中'} · {t.segments or 1} 线程")
            self.stats.setText(
                f"{fmt_speed(t.speed)}   ·   已下载 {fmt_size(t.done)} / {fmt_size(total)}"
                f"   ·   剩余 {fmt_duration(t.eta)}")
            self.spark.push(t.speed)
            self.pause_btn.show()
            self.cancel_btn.show()
            self.pause_btn.setText("暂停")
        elif st == "paused":
            self.sub.setText(f"已暂停 · via {t.mirror or '—'}")
            self.stats.setText(f"已下载 {fmt_size(t.done)} / {fmt_size(total)} · 继续后从断点接上")
            self.pause_btn.show()
            self.cancel_btn.show()
            self.pause_btn.setText("继续")
        elif st == "done":
            self.sub.setText(f"完成 · via {t.mirror or '—'} · 用时 {fmt_duration(t.elapsed)}")
            self.stats.setText(f"{fmt_size(total)} · 已保存")
            self.path.setText(str(t.target))
            self.path.show()
            self.pause_btn.hide()
            self.cancel_btn.hide()
            self.open_btn.show()
        elif st == "error":
            self.sub.setText("下载失败")
            self.stats.setText(fmt_size(t.done) + " / " + fmt_size(total))
            self.path.setText(str(t.target))
            self.path.show()
            self.err.setText(t.error)
            self.err.show()
            self.pause_btn.hide()
            self.cancel_btn.hide()
            self.open_btn.show()
            self.open_btn.setText("打开目录")
        elif st == "canceled":
            self.sub.setText("已取消")
            self.stats.setText("")
            self.pause_btn.hide()
            self.cancel_btn.hide()
        else:
            self.sub.setText("排队中 · 等待空闲槽位")
            self.stats.setText("")


class HomePage(PageBase):
    def __init__(self, app):
        super().__init__(app)
        self.parsed = None
        self.mirror_rows = []
        self.task_cards = []
        hdr = QWidget()
        hl = QHBoxLayout(hdr)
        hl.setContentsMargins(0, 0, 0, 0)
        hl.setSpacing(8)
        self.hdr_chip = pill("", P["text3"], P["surface2"], 10.5)
        hl.addWidget(self.hdr_chip)
        op = button("下载目录", "ghost", "folder", cb=self.open_root)
        op.setFixedHeight(30)
        hl.addWidget(op)
        self.build_header("镜像下载台", "多任务并发 · 自动择优链路", hdr)
        self.setAcceptDrops(True)
        self.build_input()
        self.build_columns()
        self.parse_timer = QTimer(self)
        self.parse_timer.setSingleShot(True)
        self.parse_timer.timeout.connect(self.update_preview)
        self.url_edit.textChanged.connect(lambda: self.parse_timer.start(220))
        self.preview()

    # ---- 顶部输入 ----
    def build_input(self):
        card = make_card("hero", (18, 18, 18, 16), 12)
        row = QHBoxLayout()
        row.setSpacing(10)
        self.url_edit = QLineEdit()
        self.url_edit.setProperty("mono", True)
        self.url_edit.setPlaceholderText("贴上 GitHub 链接 / owner/repo，或者任意直链…")
        self.url_edit.setMinimumHeight(44)
        self.url_edit.returnPressed.connect(self.start_download)
        row.addWidget(self.url_edit, 1)
        paste = icon_button("paste", 44, "从剪贴板粘贴", self.paste)
        paste.setStyleSheet(
            f"QPushButton{{background:{P['surface2']};border:1px solid {P['line']};border-radius:11px;}}"
            f"QPushButton:hover{{border-color:{rgba(P['primary'],.4)};}}")
        row.addWidget(paste)
        card.body.addLayout(row)

        self.parsed_row = QHBoxLayout()
        self.parsed_row.setSpacing(8)
        self.chip_kind = pill("等待链接", P["text3"], P["surface2"], 10.5)
        self.parsed_name = label("粘贴后自动解析", 12, P["text2"])
        self.parsed_extra = label("", 11, P["text3"])
        self.parsed_row.addWidget(self.chip_kind)
        self.parsed_row.addWidget(self.parsed_name)
        self.parsed_row.addWidget(self.parsed_extra)
        self.parsed_row.addStretch(1)
        card.body.addLayout(self.parsed_row)

        act = QHBoxLayout()
        act.setSpacing(10)
        self.start_btn = button("测速并下载", "primary", "bolt", 46, self.start_download)
        self.start_btn.setMinimumWidth(186)
        self.start_btn.setEnabled(False)
        act.addWidget(self.start_btn)
        self.test_btn = button("只测速", "ghost", "speed", 46, self.test_only)
        self.test_btn.setMinimumWidth(100)
        act.addWidget(self.test_btn)
        act.addStretch(1)
        c = self.app.state.config
        self.tip = label(f"当前 {c['concurrency']} 文件并发 · 单文件 {c['threads']} 线程分段 · "
                         f"失败最多换 {c['failover_max']} 个镜像", 10.5, P["text3"])
        act.addWidget(self.tip)
        card.body.addLayout(act)
        self.v.addWidget(card)

    def build_columns(self):
        cols = QHBoxLayout()
        cols.setSpacing(16)
        # 左：镜像
        left = QVBoxLayout()
        left.setSpacing(10)
        self.mirror_hint = "· 逐个探测"
        self.mirror_count = label("", 10.5, P["text3"])
        left.addLayout(self.section("镜像测速", "", self._mirror_actions()))
        self.mirror_card = make_card("", (8, 8, 8, 8), 0)
        self.mirror_box = QVBoxLayout()
        self.mirror_box.setSpacing(1)
        self.mirror_card.body.addLayout(self.mirror_box)
        left.addWidget(self.mirror_card)
        left.addStretch(1)

        # 右：任务
        right = QVBoxLayout()
        right.setSpacing(10)
        self.task_count = label("0 个任务", 10.5, P["text3"])
        btns = QHBoxLayout()
        btns.setSpacing(6)
        btns.addWidget(self.task_count)
        clear = button("清除已完成", "plain")
        clear.clicked.connect(self.clear_finished)
        cancel_all = button("全部取消", "plain")
        cancel_all.clicked.connect(self.cancel_all)
        btns.addWidget(clear)
        btns.addWidget(cancel_all)
        right.addLayout(self.section("下载任务", "", self._wrap(btns)))
        self.task_box = QVBoxLayout()
        self.task_box.setSpacing(10)
        right.addLayout(self.task_box)
        self.task_empty = EmptyState("download", "还没有下载任务", "贴链接 → 测速 → 自动走最快的那条")
        right.addWidget(self.task_empty)
        right.addStretch(1)

        cols.addLayout(right, 3)
        cols.addLayout(left, 2)
        self.v.addLayout(cols)
        self.rebuild_mirrors()

    def _wrap(self, lay):
        w = QWidget()
        w.setLayout(lay)
        lay.setContentsMargins(0, 0, 0, 0)
        return w

    def _mirror_actions(self):
        w = QWidget()
        h = QHBoxLayout(w)
        h.setContentsMargins(0, 0, 0, 0)
        h.setSpacing(6)
        self.mirror_meta = label("", 10.5, P["text3"])
        h.addWidget(self.mirror_meta)
        b = button("重新测速", "ghost")
        b.setFixedHeight(28)
        b.clicked.connect(self.test_only)
        h.addWidget(b)
        return w

    # ---- 行为 ----
    def open_root(self):
        d = Path(self.app.state.config["download_root"])
        d.mkdir(parents=True, exist_ok=True)
        open_path(d)

    def dragEnterEvent(self, ev):
        md = ev.mimeData()
        if md.hasUrls() or md.hasText():
            ev.acceptProposedAction()

    def dropEvent(self, ev):
        md = ev.mimeData()
        text = ""
        if md.hasUrls() and md.urls():
            text = md.urls()[0].toString()
        elif md.hasText():
            text = md.text()
        if text.strip():
            self.url_edit.setText(text.strip())
            ev.acceptProposedAction()

    def paste(self):
        txt = QApplication.clipboard().text().strip()
        if txt:
            self.url_edit.setText(txt)

    def update_preview(self):
        self.preview()

    def preview(self):
        text = self.url_edit.text().strip()
        p = parse_github(text)
        self.parsed = p
        if not p:
            self.chip_kind.setText("等待链接")
            self.chip_kind.setStyleSheet(
                f"color:{P['text3']};background:{P['surface2']};border-radius:9px;padding:2px 8px;font-size:10.5px;")
            self.parsed_name.setText("粘贴后自动解析")
            self.parsed_name.setStyleSheet(f"color:{P['text2']};font-size:12px;background:transparent;")
            self.parsed_extra.setText("")
            self.start_btn.setEnabled(False)
            self.start_btn.setText("测速并下载")
            return
        self.chip_kind.setText(p.label)
        self.chip_kind.setStyleSheet(
            f"color:{P['accent']};background:{P['accent_soft']};border-radius:9px;padding:2px 8px;font-size:10.5px;")
        if p.kind == "repo":
            self.parsed_name.setText(p.slug)
            self.parsed_extra.setText("· 仓库地址，进入版本追踪")
            self.start_btn.setText("查看项目版本")
        else:
            self.parsed_name.setText(p.filename or "未命名文件")
            self.parsed_extra.setText("· 点击开始测速" if p.kind != "direct" else "· 直链")
            self.start_btn.setText("测速并下载")
        self.start_btn.setEnabled(True)

    def start_download(self):
        p = self.parsed
        if not p:
            return
        if p.kind == "repo":
            self.app.open_watch_repo(p.slug)
            return
        self.app.download_url(p.url, p.filename or "download.bin", p.slug, test_first=True)

    def test_only(self):
        p = self.parsed
        if not p or p.kind == "repo":
            self.app.toast("先贴一个文件链接再测速", "warn")
            return
        self.app.start_speed_test(p.url)

    # ---- 镜像 ----
    def rebuild_mirrors(self):
        while self.mirror_box.count():
            it = self.mirror_box.takeAt(0)
            w = it.widget()
            if w:
                w.setParent(None)
                w.deleteLater()
        self.mirror_rows = []
        enabled = [m for m in self.app.state.mirrors if m.get("enabled", True)]
        enabled.sort(key=mirror_score, reverse=True)
        for i, m in enumerate(enabled, 1):
            row = MirrorRow(m)
            row.set_rank(i)
            self.mirror_box.addWidget(row)
            self.mirror_rows.append(row)
        self.mirror_meta.setText(f"{len(enabled)} 个候选")
        if hasattr(self, "hdr_chip"):
            self.hdr_chip.setText(f"{len(enabled)} 个镜像 · {self.app.state.config['concurrency']} 并发")
        self.mirror_card.setVisible(bool(enabled))

    def reflow_ranks(self):
        rows = sorted(self.mirror_rows, key=lambda r: mirror_score(r.m), reverse=True)
        for i, r in enumerate(rows, 1):
            r.set_rank(i)
        self.mirror_rows = rows

    def mark_testing(self):
        for r in self.mirror_rows:
            r.testing()

    def mark_pending(self, on):
        """等待测速时把主按钮切成「测速中…」，测完由 preview() 恢复。"""
        btn = getattr(self, "start_btn", None)
        if btn is None:
            return
        if on:
            btn.setText("测速中…")
            btn.setEnabled(False)
        else:
            self.preview()

    def on_mirror_result(self, m, ok, lat, sp):
        for r in self.mirror_rows:
            if r.m is m:
                r.set_result(ok, lat, sp)
        self.reflow_ranks()

    # ---- 任务 ----
    def add_task(self, task):
        card = TaskCard(self.app, task)
        self.task_cards.insert(0, card)
        self.task_box.insertWidget(0, card)
        self.task_empty.hide()
        self.update_task_count()

    def clear_finished(self):
        keep = []
        for card in self.task_cards:
            if card.task.status in ("done", "error", "canceled"):
                card.setParent(None)
                card.deleteLater()
            else:
                keep.append(card)
        self.task_cards = keep
        self.app.dm.clear_finished()
        self.update_task_count()

    def cancel_all(self):
        for card in self.task_cards:
            if card.task.status in ("running", "probing", "queued", "paused"):
                self.app.dm.cancel(card.task)
        self.update_task_count()

    def update_task_count(self):
        active = sum(1 for c in self.task_cards
                     if c.task.status in ("running", "probing", "paused", "queued"))
        self.task_count.setText(f"{active} 个进行中 / {len(self.task_cards)} 个任务")
        self.task_empty.setVisible(len(self.task_cards) == 0)

    def tick(self):
        for card in list(self.task_cards):
            card.refresh()
        self.update_task_count()
# --------------------------------------------------------------------------
# 版本追踪
# --------------------------------------------------------------------------


class Elided(QLabel):
    """按控件宽度省略中间，鼠标悬停看全名。"""

    def __init__(self, text="", color=None, size=12, mono=False, mode=Qt.ElideMiddle, parent=None):
        super().__init__(parent)
        self.full = text
        self.color = color or P["text"]
        self.size = size
        self.mode = mode
        f = QFont()
        f.setFamilies(["Cascadia Mono", "Consolas", "SF Mono", "DejaVu Sans Mono"] if mono else
                      ["Microsoft YaHei UI", "PingFang SC", "Noto Sans CJK SC", "Segoe UI"])
        f.setPixelSize(size)
        self.setFont(f)
        self.setToolTip(text)
        self.setMinimumWidth(40)
        self.setSizePolicy(QSizePolicy.Expanding, QSizePolicy.Preferred)

    def setText(self, t):
        self.full = t or ""
        self.setToolTip(self.full)
        self.setAccessibleName(self.full)
        self.update()

    def sizeHint(self):
        return QSize(240, self.fontMetrics().height() + 2)

    def minimumSizeHint(self):
        return QSize(40, self.fontMetrics().height() + 2)

    def paintEvent(self, ev):
        pr = QPainter(self)
        pr.setFont(self.font())
        pr.setPen(QColor(self.color))
        fm = QFontMetrics(self.font())
        txt = fm.elidedText(self.full, self.mode, max(10, self.width() - 2))
        pr.drawText(self.rect().adjusted(0, 0, 0, 0), Qt.AlignVCenter | Qt.AlignLeft, txt)
        pr.end()


class WatchCard(QFrame):
    def __init__(self, app, w, parent=None):
        super().__init__(parent)
        self.app = app
        self.w = w
        self.setObjectName("card")
        lay = QVBoxLayout(self)
        lay.setContentsMargins(16, 14, 16, 14)
        lay.setSpacing(9)

        top = QHBoxLayout()
        top.setSpacing(8)
        title = Elided(w.get("note") or w["repo"], P["text"], 13.5, mono=False)
        f = title.font()
        f.setBold(True)
        title.setFont(f)
        title.setToolTip(w["repo"] + (f"　·　备注：{w['note']}" if w.get("note") else ""))
        top.addWidget(title, 1)
        self.state_chip = pill("检查中", P["text3"], P["surface2"], 10)
        top.addWidget(self.state_chip)
        rf = icon_button("refresh", 26, "只重新检查这个项目",
                         lambda: app.watch_page.check_one(w["repo"]))
        top.addWidget(rf)
        nb = button("备注", "plain")
        nb.setToolTip("给这个项目起一个自己认得的名字；追踪列表可以按备注名排序")
        nb.clicked.connect(self.edit_note)
        top.addWidget(nb)
        rm = button("移除", "plain")
        rm.clicked.connect(self.remove)
        top.addWidget(rm)
        lay.addLayout(top)
        if w.get("note"):
            repo_line = Elided(w["repo"], P["text3"], 10.5, mono=True)
            repo_line.setToolTip(w["repo"])
            lay.addWidget(repo_line)

        self.status = label("", 12.5, P["text2"], bold=True)
        lay.addWidget(self.status)
        self.meta = label("", 10.5, P["text3"], wrap=True)
        lay.addWidget(self.meta)

        folder_row = QHBoxLayout()
        folder_row.setSpacing(6)
        fi = QLabel()
        fi.setPixmap(icon_pixmap("folder", P["text3"], 13))
        folder_row.addWidget(fi)
        self.folder = Elided(str(app.state.project_dir(w["repo"])), P["text3"], 10.5, mono=True, mode=Qt.ElideMiddle)
        folder_row.addWidget(self.folder, 1)
        rule = REPLACE_RULES.get(app.state.project_rule(w["repo"]), "直接替换")
        folder_row.addWidget(label(f"同名：{rule}", 10.5, P["text3"]))
        chg = button("更改目录", "plain")
        chg.clicked.connect(self.change_dir)
        folder_row.addWidget(chg)
        lay.addLayout(folder_row)

        fmt_row = QHBoxLayout()
        fmt_row.setSpacing(6)
        fmt_row.addWidget(label("追踪格式", 10.5, P["text3"]))
        self.fmt_btn = button("", "plain")
        self.fmt_btn.setFixedHeight(26)
        self.fmt_btn.setToolTip("只改这个项目要看的格式；所有可选格式在设置里管理")
        self.fmt_btn.clicked.connect(self.open_format_menu)
        fmt_row.addWidget(self.fmt_btn)
        fmt_row.addWidget(label("只影响这个项目", 10, P["text3"]))
        fmt_row.addStretch(1)
        lay.addLayout(fmt_row)

        self.tags_host = QWidget()
        self.tags_lay = QHBoxLayout(self.tags_host)
        self.tags_lay.setContentsMargins(0, 0, 0, 0)
        self.tags_lay.setSpacing(8)
        self.tags_host.hide()
        lay.addWidget(self.tags_host)

        acts = QHBoxLayout()
        acts.setSpacing(8)
        view = button("查看版本", "ghost", "versions", cb=self.open_versions)
        view.setFixedHeight(32)
        acts.addWidget(view)
        self.dl = button("下载最新", "primary", "download", cb=self.download_latest)
        self.dl.setFixedHeight(32)
        self.dl.setMinimumWidth(104)
        acts.addWidget(self.dl)
        op = button("打开目录", "plain", "folder", cb=self.open_dir)
        acts.addWidget(op)
        acts.addStretch(1)
        lay.addLayout(acts)
        self.refresh()

    def refresh(self):
        w = self.w
        self._update_fmt_btn()
        latest = w.get("latest")
        self.tags_host.hide()
        if w.get("checking"):
            self.state_chip.setText("检查中")
            self.state_chip.setStyleSheet(
                f"color:{P['text3']};background:{P['surface2']};border-radius:9px;padding:2px 8px;font-size:10px;")
            self.status.setText("正在读取最新 release…")
            self.status.setStyleSheet(f"color:{P['text2']};font-size:12.5px;font-weight:700;background:transparent;")
            self.meta.setText("")
            return
        if w.get("error"):
            self.state_chip.setText("失败")
            self.state_chip.setStyleSheet(
                f"color:{P['danger']};background:{P['danger_soft']};border-radius:9px;padding:2px 8px;font-size:10px;")
            self.status.setText(w["error"])
            self.status.setStyleSheet(f"color:{P['danger']};font-size:12px;background:transparent;")
            self.meta.setText("")
            self.dl.setEnabled(False)
            return
        if not latest:
            self.state_chip.setText("待检查")
            self.state_chip.setStyleSheet(
                f"color:{P['text3']};background:{P['surface2']};border-radius:9px;padding:2px 8px;font-size:10px;")
            self.status.setText("还没检查过这个项目")
            self.meta.setText("")
            self.dl.setEnabled(False)
            return
        formats = self.app.state.project_formats(w["repo"])
        assets = filter_assets(latest.get("assets", []), formats)
        tag = latest.get("tag", "")
        when = bj_label(latest.get("newest"), True)
        if not assets:
            self.state_chip.setText("无匹配")
            self.state_chip.setStyleSheet(
                f"color:{P['warn']};background:{rgba(P['warn'], .12)};border-radius:9px;padding:2px 8px;font-size:10px;")
            self.status.setText("最新版本没有 " + " / ".join(x.upper() for x in formats) + " 文件")
            self.status.setStyleSheet(f"color:{P['warn']};font-size:12.5px;font-weight:700;background:transparent;")
            self.meta.setText(f"最新 {tag} · {when}（北京时间）")
            self.dl.setEnabled(False)
            return
        self.dl.setEnabled(True)
        exts = sorted({ext_of(a["name"]).upper() for a in assets})
        sel = [k for k in (w.get("variants") or []) if k]
        chosen = [a for a in assets if a.get("vkey") in sel]
        rec = (chosen[0] if chosen else None) or preferred_asset(assets, formats)
        self._build_tags(assets)
        lost = bool(sel) and not chosen
        tags_txt = ""
        if chosen:
            names = []
            for a in chosen:
                t = variant_text(a)
                if t not in names:
                    names.append(t)
            tags_txt = "　·　只看 " + " / ".join(names)
        self.meta.setText(f"最新 {tag} · {when}（北京时间） · {len(assets)} 个文件（{'/'.join(exts)}）"
                          + tags_txt
                          + ("　·　选中的标签在这个版本没有文件，先按全部文件显示" if lost else ""))
        if rec:
            self.dl.setToolTip(f"下载 {rec['name']}")
        if w.get("tag") and w["tag"] == tag:
            self.state_chip.setText("已是最新")
            self.state_chip.setStyleSheet(
                f"color:{P['primary']};background:{P['primary_soft']};border-radius:9px;padding:2px 8px;font-size:10px;")
            self.status.setText(f"已是最新 · {tag}")
            self.status.setStyleSheet(f"color:{P['primary']};font-size:12.5px;font-weight:700;background:transparent;")
        elif w.get("tag"):
            self.state_chip.setText("有更新")
            self.state_chip.setStyleSheet(
                f"color:{P['warn']};background:{rgba(P['warn'], .12)};border-radius:9px;padding:2px 8px;font-size:10px;")
            self.status.setText(f"有新版本 {w['tag']} → {tag}")
            self.status.setStyleSheet(f"color:{P['warn']};font-size:12.5px;font-weight:700;background:transparent;")
        else:
            self.state_chip.setText("可下载")
            self.state_chip.setStyleSheet(
                f"color:{P['accent']};background:{P['accent_soft']};border-radius:9px;padding:2px 8px;font-size:10px;")
            self.status.setText(f"发现 {tag} · 可以下载")
            self.status.setStyleSheet(f"color:{P['accent']};font-size:12.5px;font-weight:700;background:transparent;")

    def _clear(self, lay):
        while lay.count():
            it = lay.takeAt(0)
            w = it.widget()
            if w:
                w.setParent(None)
                w.deleteLater()
            elif it.layout():
                self._clear(it.layout())

    def _build_tags(self, assets):
        """标签行：支持多选。选中几个就只看这几个；一个都没选就看全部。"""
        self._clear(self.tags_lay)
        opts, seen = [], set()
        for a in assets:
            if not a.get("variant") or a["vkey"] in seen:
                continue
            seen.add(a["vkey"])
            opts.append(a)
        if not opts:
            self.tags_host.hide()
            return

        sel = [k for k in (self.w.get("variants") or []) if k in seen]
        expanded = bool(self.w.get("tags_open")) or not sel
        show = opts if expanded else [a for a in opts if a["vkey"] in sel]

        head = label("标签", 10.5, P["text3"])
        head.setAlignment(Qt.AlignTop | Qt.AlignLeft)
        head.setFixedWidth(30)
        self.tags_lay.addWidget(head, 0, Qt.AlignTop)

        holder = QWidget()
        flow = FlowLayout(holder, 0, 6, 5)
        for a in show:
            c = VariantChip(a["variant"] or "标准", ext_of(a["name"]).upper(),
                            a["vkey"] in sel)
            c.setToolTip(f"{a['name']}\n点一下选中 / 取消，可以多选")
            c.clicked.connect(lambda _=False, x=a: self.toggle_variant(x["vkey"]))
            flow.addWidget(c)
        self.tags_lay.addWidget(holder, 1, Qt.AlignTop)

        if sel:
            collapsed = not self.w.get("tags_open")
            t = button(f"全部标签 {len(opts)}" if collapsed else "收起", "plain")
            t.setFixedHeight(30)
            t.setToolTip("摊开所有标签，继续加选" if collapsed else "只看已选中的标签")
            t.clicked.connect(self.toggle_tags)
            self.tags_lay.addWidget(t, 0, Qt.AlignTop)
        self.tags_host.show()

    def _update_fmt_btn(self):
        avail = self.app.state.project_available_formats(self.w["repo"])
        cur = [f for f in self.app.state.project_formats(self.w["repo"])
               if not avail or f in avail]
        if not avail:
            text = "格式…" if not self.w.get("latest") else "无匹配"
        elif not cur:
            text = "无匹配"
        elif len(cur) <= 3:
            text = " · ".join(x.upper() for x in cur)
        else:
            text = f"{len(cur)} 种格式"
        self.fmt_btn.setText(text)
        if avail:
            tip = "这个项目最新版里有的格式：" + " / ".join(x.upper() for x in avail)
        else:
            tip = "还没拿到这个项目的文件列表，先检查一次更新"
        self.fmt_btn.setToolTip(tip + "\n点一下可以只保留其中几种（大范围在设置页改）")

    def open_format_menu(self):
        avail = self.app.state.project_available_formats(self.w["repo"])
        if not avail:
            self.app.toast("先检查一次更新，才知道这个项目有哪些格式"
                           if not self.w.get("latest")
                           else "这个项目最新版只有设置里没勾的格式，先去设置里加上", "warn")
            return
        labels = dict(self.app.state.format_menu())
        cur = set(self.app.state.project_formats(self.w["repo"]))
        m = QMenu(self)
        for e in avail:
            act = m.addAction(labels.get(e, e.upper()))
            act.setCheckable(True)
            act.setChecked(e in cur)
            act.setToolTip("这个项目最新版里确实有 " + e.upper())
            act.triggered.connect(lambda _=False, k=e: self.toggle_fmt(k))
        m.addSeparator()
        m.addAction("全选（这个项目有的）").triggered.connect(self.reset_fmt)
        m.exec(self.fmt_btn.mapToGlobal(QPoint(0, self.fmt_btn.height() + 4)))

    def toggle_fmt(self, key):
        avail = self.app.state.project_available_formats(self.w["repo"])
        cur = [f for f in self.app.state.project_formats(self.w["repo"]) if f in avail]
        if key in cur:
            if len(cur) <= 1:
                self.app.toast("至少留一个格式", "warn")
                return
            cur.remove(key)
        else:
            cur.append(key)
        self.w["formats"] = [] if set(cur) >= set(avail) else [f for f in avail if f in cur]
        self.app.state.save()
        QTimer.singleShot(0, self.app.watch_page.rebuild)
        self.app.toast("这个项目的追踪格式已更新", "ok")

    def reset_fmt(self):
        self.w["formats"] = []
        self.app.state.save()
        QTimer.singleShot(0, self.app.watch_page.rebuild)
        self.app.toast("已跟随设置里的格式范围", "ok")

    def toggle_tags(self):
        self.w["tags_open"] = not bool(self.w.get("tags_open"))
        self.app.state.save()
        QTimer.singleShot(0, self.refresh)

    def toggle_variant(self, key):
        cur = [k for k in (self.w.get("variants") or []) if k]
        if key in cur:
            cur.remove(key)
        else:
            cur.append(key)
        self.w["variants"] = cur
        self.w["tags_open"] = True if cur else self.w.get("tags_open")
        self.app.state.save()
        QTimer.singleShot(0, self.refresh)
        if cur:
            self.app.toast(f"已选 {len(cur)} 个标签，下载最新按这个范围挑" , "ok")
        else:
            self.app.toast("已取消标签筛选，显示这个版本的全部文件", "ok")

    def change_dir(self):
        d = QFileDialog.getExistingDirectory(self, "选择这个项目的下载目录",
                                             str(self.app.state.project_dir(self.w["repo"])))
        if d:
            self.w["dir"] = d
            self.app.state.save()
            self.folder.setText(d)
            self.app.toast("下载目录已更新", "ok")

    def edit_note(self):
        cur = self.w.get("note") or ""
        text = ask_text(self, "项目备注",
                        "起一个好认的名字，留空就恢复显示 owner/repo。",
                        cur, "例如：Obsidian 安装包 · 主力笔记")
        if text is None:
            return
        self.w["note"] = text[:40]
        self.app.state.save()
        self.app.watch_page.rebuild()
        self.app.toast("备注已更新" if text else "已清除备注", "ok")

    def open_dir(self):
        d = self.app.state.project_dir(self.w["repo"])
        d.mkdir(parents=True, exist_ok=True)
        open_path(d)

    def remove(self):
        idx = ask_choice(self, "移除追踪", f"不再跟踪 {self.w['repo']}？已下载的文件不会被删除。",
                         [("移除追踪", "只删掉这一条追踪记录", "danger")])
        if idx == 0:
            self.app.state.remove_watch(self.w["repo"])
            self.app.watch_page.rebuild()
            self.app.toast("已移除追踪")

    def open_versions(self):
        dlg = VersionDialog(self.app, self.w["repo"], self)
        dlg.exec()
        self.app.watch_page.rebuild()
        self.app.refresh_history()

    def download_latest(self):
        latest = self.w.get("latest") or {}
        formats = self.app.state.project_formats(self.w["repo"])
        assets = filter_assets(latest.get("assets", []), formats)
        sel = [k for k in (self.w.get("variants") or []) if k]
        pool = [a for a in assets if a.get("vkey") in sel] if sel else []
        asset = ((pool[0] if pool else None)
                 or pick_variant(assets, self.w.get("last_vkey") or "")
                 or preferred_asset(assets, formats))
        if not asset:
            self.app.toast("最新版本没有匹配格式的文件", "warn")
            return
        self.app.download_assets(self.w["repo"], [asset], parent=self, tag=latest.get("tag", ""))


_COLLATOR = None


def _text_cmp(a: str, b: str) -> int:
    """中文按拼音、英文按字母、数字按大小；用于备注名 / 项目名排序。"""
    global _COLLATOR
    if _COLLATOR is None:
        _COLLATOR = QCollator(QLocale(QLocale.Chinese, QLocale.China))
        _COLLATOR.setCaseSensitivity(Qt.CaseInsensitive)
        _COLLATOR.setNumericMode(True)
    return _COLLATOR.compare(a or "", b or "")


def _ago(ms) -> str:
    """毫秒时间戳 → 「刚刚 / 12 分钟前」这种给人看的话。"""
    try:
        d = max(0, now_ms() - int(ms or 0)) // 1000
    except Exception:
        return "刚刚"
    if d < 60:
        return "刚刚"
    if d < 3600:
        return f"{d // 60} 分钟前"
    if d < 86400:
        return f"{d // 3600} 小时前"
    return f"{d // 86400} 天前"


def watch_ts(w: dict) -> int:
    """项目最近一次「有内容变化」的时间：优先最新版本发布时间，其次最近检查/添加时间。"""
    latest = w.get("latest") or {}
    v = int(latest.get("newest_ms") or 0)
    if not v:
        dt = bj(latest.get("newest") or "")
        v = int(dt.timestamp() * 1000) if dt else 0
    return v or int(w.get("checked") or 0) or int(w.get("added") or 0)


class WatchPage(PageBase):
    def __init__(self, app):
        super().__init__(app)
        self.cards = []
        self._batch_seen = False
        # 检查中的轮询：按钮上实时显示「检查中 2/5」，全部回来就恢复
        self._poll = QTimer(self)
        self._poll.setInterval(600)
        self._poll.timeout.connect(self.refresh_check_state)
        self.build_header("版本追踪", "盯住最新 release · 只列 Windows x64 安装包 · 每个项目独立格式",
                          self.build_check())
        self.build_input()
        self.list_box = QVBoxLayout()
        self.list_box.setSpacing(10)
        self.v.addLayout(self.section("追踪列表", right=self.build_sort()))
        self.v.addLayout(self.list_box)
        self.empty = EmptyState("versions", "还没有追踪任何项目",
                                "填一个 owner/repo，或者从首页粘贴仓库地址")
        self.v.addWidget(self.empty)
        note = label("只读 GitHub 公开接口 · 每个项目独立的下载目录、同名规则、追踪格式和标签 · ARM64 / 32 位安装包不显示",
                     10.5, P["text3"], wrap=True)
        note.setAlignment(Qt.AlignCenter)
        self.v.addWidget(note)

    def build_check(self):
        box = QWidget()
        h = QHBoxLayout(box)
        h.setContentsMargins(0, 0, 0, 0)
        h.setSpacing(10)
        self.check_hint = label("", 10.5, P["text3"])
        h.addWidget(self.check_hint)
        self.check_btn = button("检查更新", "primary", "refresh", 34, self.check_all)
        self.check_btn.setMinimumWidth(112)
        self.check_btn.setToolTip("立刻重新拉一遍所有项目的最新 Release，跳过 10 分钟缓存")
        h.addWidget(self.check_btn)
        return box

    def build_input(self):
        card = make_card("hero", (18, 16, 18, 14), 11)
        row = QHBoxLayout()
        row.setSpacing(10)
        row.addWidget(label("项目地址", 11.5, P["text2"]))
        self.repo_edit = QLineEdit()
        self.repo_edit.setProperty("mono", True)
        self.repo_edit.setPlaceholderText("owner/repo 或 https://github.com/owner/repo")
        self.repo_edit.setMinimumHeight(42)
        self.repo_edit.returnPressed.connect(self.track)
        row.addWidget(self.repo_edit, 1)
        add = button("追踪", "primary", "plus", 42, self.track)
        add.setMinimumWidth(96)
        row.addWidget(add)
        card.body.addLayout(row)

        row2 = QHBoxLayout()
        row2.setSpacing(8)
        row2.addWidget(label("追踪格式", 11, P["text3"]))
        self.fmt_summary = label("", 11, P["text2"])
        row2.addWidget(self.fmt_summary, 1)
        go = button("去设置调整", "plain")
        go.setFixedHeight(30)
        go.setToolTip("大范围（勾哪些格式、加自定义格式）只在设置页统一改")
        go.clicked.connect(lambda: self.app.switch_page(4))
        row2.addWidget(go)
        card.body.addLayout(row2)
        card.body.addWidget(label(
            "大范围只在设置页统一选；每个项目卡片右上角还能单独收窄，"
            "而且只会列出这个项目真实存在的格式。", 10.5, P["text3"], wrap=True))
        self.v.addWidget(card)
        self.refresh_format_hint()

    def refresh_format_hint(self):
        g = self.app.state.global_formats()
        self.fmt_summary.setText(" / ".join(x.upper() for x in g)
                                 + "　·　点「去设置调整」增删")

    # ---- 排序 ----
    def build_sort(self):
        w = QWidget()
        h = QHBoxLayout(w)
        h.setContentsMargins(0, 0, 0, 0)
        h.setSpacing(6)
        self.list_count = label("", 10.5, P["text3"])
        h.addWidget(self.list_count)
        h.addSpacing(2)
        h.addWidget(label("排序", 10.5, P["text3"]))
        self.sort_chips = {}
        cur = self.app.state.config.get("watch_sort", "updated")
        tips = {
            "updated": "按最新版本发布时间排，刚更新的项目在最前",
            "note": "按备注名（没写备注就用项目名）从 A 到 Z 排",
            "name": "按仓库名从 A 到 Z 排",
        }
        for key, text in (("updated", "最近更新"), ("note", "备注名"), ("name", "项目名")):
            c = chip(text, cur == key)
            c.setToolTip(tips[key])
            c.clicked.connect(lambda _=False, k=key: self.set_sort(k))
            self.sort_chips[key] = c
            h.addWidget(c)
        return w

    def set_sort(self, key):
        if key not in ("updated", "note", "name"):
            return
        self.app.state.config["watch_sort"] = key
        self.app.state.save()
        for k, c in self.sort_chips.items():
            c.setChecked(k == key)
        self.rebuild()

    def sorted_watch(self):
        ws = list(self.app.state.watch)
        key = self.app.state.config.get("watch_sort", "updated")
        if key == "note":
            ws.sort(key=cmp_to_key(
                lambda a, b: _text_cmp(a.get("note") or a["repo"], b.get("note") or b["repo"])
                or _text_cmp(a["repo"], b["repo"])))
        elif key == "name":
            ws.sort(key=cmp_to_key(
                lambda a, b: _text_cmp(a["repo"].split("/")[-1], b["repo"].split("/")[-1])
                or _text_cmp(a["repo"], b["repo"])))
        else:
            ws.sort(key=watch_ts, reverse=True)
        return ws

    def track(self):
        text = self.repo_edit.text().strip()
        p = parse_github(text)
        if not p or not p.slug:
            self.app.toast("请输入 owner/repo 或仓库地址", "warn")
            return
        w = self.app.state.add_watch(p.slug)
        self.app.state.config["last_repo"] = p.slug
        self.app.state.save()
        self.repo_edit.clear()
        self.rebuild()
        self.app.check_repo(p.slug, force=True)
        self.app.toast(f"已开始追踪 {p.slug}", "ok")

    def rebuild(self):
        while self.list_box.count():
            it = self.list_box.takeAt(0)
            w = it.widget()
            if w:
                w.setParent(None)
                w.deleteLater()
        self.cards = []
        ws = self.sorted_watch()
        for w in ws:
            c = WatchCard(self.app, w)
            self.list_box.addWidget(c)
            self.cards.append(c)
        self.empty.setVisible(not ws)
        self.list_count.setText(f"{len(ws)} 个项目" if ws else "")
        cur = self.app.state.config.get("watch_sort", "updated")
        for k, c in getattr(self, "sort_chips", {}).items():
            c.setChecked(k == cur)

    def check_all(self):
        ws = list(self.app.state.watch)
        if not ws:
            self.app.toast("还没有追踪任何项目，先在上面填一个仓库地址", "warn")
            self.refresh_check_state()
            return
        if any(w.get("checking") for w in ws):
            self.app.toast("正在检查中，稍等一下", "")
            return
        self._batch_seen = True
        for w in ws:
            self.app.check_repo(w["repo"], force=True)
        self.app.toast(f"开始检查 {len(ws)} 个项目的更新…", "ok")
        self.refresh_check_state()

    def refresh_check_state(self):
        ws = list(self.app.state.watch)
        total = len(ws)
        # 兜底：网络卡死时线程可能一直不回来，超过 45 秒就当作失败放行
        stale = 0
        for w in ws:
            v = w.get("checking")
            if type(v) is int and now_ms() - v > 45000:
                w["checking"] = False
                w["error"] = w.get("error") or "检查超时，稍后重试"
                stale += 1
        if stale:
            self.app.state.save()
        left = sum(1 for w in ws if w.get("checking"))
        if not total:
            self.check_btn.setText("检查更新")
            self.check_btn.setEnabled(True)
            self.check_hint.setText("")
            self._batch_seen = False
            self._poll.stop()
            return
        if left:
            # 不禁用：保持可读的强调色，点一下只会提示「正在检查中」
            self.check_btn.setText(f"检查中 {total - left}/{total}")
            self.check_btn.setEnabled(True)
            self.check_hint.setText("正在拉取 Release 列表…")
            if not self._poll.isActive():
                self._poll.start()
            return
        self.check_btn.setText("检查更新")
        self.check_btn.setEnabled(True)
        self._poll.stop()
        done = sum(1 for w in ws if w.get("checked"))
        errs = sum(1 for w in ws if w.get("error"))
        ts = [int(w.get("checked") or 0) for w in ws if w.get("checked")]
        bits = []
        if ts:
            bits.append("上次检查 " + _ago(max(ts)))
        elif done:
            bits.append("还没检查过")
        if errs:
            bits.append(f"{errs} 个失败")
        self.check_hint.setText("　·　".join(bits))
        if self._batch_seen:
            self._batch_seen = False
            if errs:
                self.app.toast(f"检查完成 · {errs} 个项目没拉到，看看卡片上的提示", "warn")
            else:
                self.app.toast(f"检查完成 · {total} 个项目都刷新了", "ok")

    def refresh_cards(self):
        for c in self.cards:
            c.refresh()

    def on_show(self):
        self.rebuild()
        for i, w in enumerate(list(self.app.state.watch)):
            QTimer.singleShot(i * 260, lambda r=w["repo"]: self.app.check_repo(r))
        self.refresh_check_state()

    def check_one(self, repo):
        self.app.check_repo(repo, force=True)
        self.refresh_check_state()


class ReleaseGroup(QFrame):
    def __init__(self, app, repo, rel, batch, on_pick, all_arch=False, parent=None):
        super().__init__(parent)
        self.app = app
        self.repo = repo
        self.rel = rel
        self.batch = batch
        self.on_pick = on_pick
        self.all_arch = all_arch
        self.setObjectName("card")
        lay = QVBoxLayout(self)
        lay.setContentsMargins(14, 11, 14, 11)
        lay.setSpacing(7)

        head = QHBoxLayout()
        head.setSpacing(8)
        tag = label(rel["tag"] or "未命名", 13, P["text"], bold=True)
        head.addWidget(tag)
        if rel.get("prerelease"):
            head.addWidget(pill("预发布", P["warn"], rgba(P["warn"], .12), 9))
        if rel.get("name") and rel["name"] != rel["tag"]:
            head.addWidget(label(rel["name"], 10.5, P["text3"]))
        head.addStretch(1)
        head.addWidget(label(f"{bj_label(rel.get('newest'))}（北京时间）", 10.5, P["text3"]))
        lay.addLayout(head)

        full, assets, matched = on_pick.assets_for(rel)
        mv = set(on_pick.remembered_vkeys())
        only = bool(getattr(on_pick, "only_variant", True) and mv)
        hit = assets if matched else []

        # 标签条：这个版本里所有可选的标签，选中的高亮；点别的就换目标
        opts, seen = [], set()
        for a in full:
            if not a.get("variant") or a["vkey"] in seen:
                continue
            seen.add(a["vkey"])
            opts.append(a)
        if opts:
            wrap = QWidget()
            flow = FlowLayout(wrap, 0, 6, 5)
            for a in opts:
                c = VariantChip(a["variant"] or "标准", ext_of(a["name"]).upper(),
                                a["vkey"] in mv)
                c.setToolTip(f"{a['name']}\n点一下选中 / 取消，可以多选")
                c.clicked.connect(lambda _=False, x=a: self.on_pick.on_variant(x))
                flow.addWidget(c)
            row = QHBoxLayout()
            row.setContentsMargins(0, 1, 0, 2)
            row.setSpacing(8)
            head = label("标签", 10, P["text3"])
            head.setFixedWidth(26)
            head.setAlignment(Qt.AlignTop | Qt.AlignLeft)
            row.addWidget(head, 0, Qt.AlignTop)
            row.addWidget(wrap, 1, Qt.AlignTop)
            lay.addLayout(row)

        if not full:
            lay.addWidget(label("这个版本没有符合当前格式的文件", 10.5, P["text3"]))
        elif only and not hit:
            lay.addWidget(label("这个版本没有选中的标签，先列出全部文件", 10, P["text3"]))
        for a in assets:
            lay.addWidget(self.asset_row(a))

    def asset_row(self, a):
        row = QFrame()
        row.setObjectName("inner")
        row.setStyleSheet(f"QFrame#inner{{background:{P['surface2']};border:1px solid {P['line']};border-radius:10px;}}")
        h = QHBoxLayout(row)
        h.setContentsMargins(10, 7, 10, 7)
        h.setSpacing(9)
        cb = None
        if self.batch:
            cb = Check(self.on_pick.is_selected(a), lambda v, asset=a: self.on_pick.toggle(asset, v))
            h.addWidget(cb)
        fi = QLabel()
        fi.setPixmap(icon_pixmap("file", P["text3"], 14))
        h.addWidget(fi)
        # 文件名不省略：太长就缩字号、折行，保证完整可读
        nm = a["name"]
        fs = 11.5 if len(nm) <= 52 else (10.5 if len(nm) <= 76 else 9.5)
        name = mono_label(nm, fs, P["text"])
        name.setWordWrap(True)
        name.setSizePolicy(QSizePolicy.Expanding, QSizePolicy.Preferred)
        name.setMinimumWidth(120)
        name.setToolTip(nm)
        h.addWidget(name, 1)
        remembered = bool(a.get("vkey")) and a["vkey"] in self.on_pick.remembered_vkeys()
        if a.get("variant"):
            h.addWidget(pill(variant_text(a),
                             P["primary"] if remembered else P["text2"],
                             P["primary_soft"] if remembered else P["surface"],
                             9))
        h.addWidget(label(f"{fmt_size(a['size'])} · {bj_label(a['updated'], False)}", 10, P["text3"]))
        if a.get("arch") in ("arm", "x86"):
            h.addWidget(pill("ARM64" if a["arch"] == "arm" else "32 位", P["text3"], P["surface"], 9))
        target = self.app.state.project_dir(self.repo) / safe_name(a["name"])
        exists = target.exists()
        if exists and not self.batch:
            h.addWidget(pill("已在目录", P["primary"], P["primary_soft"], 9))
        if not self.batch:
            b = button("再下" if exists else "下载", "ghost")
            b.setFixedHeight(28)
            b.clicked.connect(lambda _=False, asset=a: self.on_pick.pick_single(asset))
            h.addWidget(b)
        row.setCursor(QCursor(Qt.PointingHandCursor))
        row.setToolTip(f"{a['name']}\n标签：{variant_text(a)}"
                       + ("（当前项目已选这个标签）" if remembered else ""))

        def row_click(ev):
            if self.batch and cb is not None:
                cb.toggle()
            elif not self.batch:
                self.on_pick.pick_single(a)

        row.mousePressEvent = row_click
        return row


class VersionDialog(Dialog):
    page_ready = Signal(object)
    load_error = Signal(str)

    def __init__(self, app, repo, parent=None):
        super().__init__(parent, f"{repo} · 版本列表", 1060)
        self.app = app
        self.repo = repo
        self.releases = []
        self.selected = {}
        self.loading = False
        self.next_page = 1
        self.has_more = True
        self.PAGE = 20
        self.all_arch = False
        self.only_variant = True
        self.setMinimumSize(960, 660)
        self.resize(1060, 760)
        self.avail_formats = app.state.project_available_formats(repo)
        cur_fmt = [f for f in app.state.project_formats(repo)
                   if not self.avail_formats or f in self.avail_formats]
        self.app.dialog_formats = cur_fmt or list(self.avail_formats)

        self.add_title(repo, "只列这个项目的追踪格式 · Windows x64 · 时间为北京时间")
        self.header.addStretch(1)
        self.count_label = label("正在读取…", 10.5, P["text3"])
        self.header.addWidget(self.count_label, 0, Qt.AlignBottom)

        # 目录 + 规则
        bar = QFrame()
        bar.setObjectName("card")
        bar.setProperty("tone", "")
        bar.setStyleSheet(f"QFrame#card{{background:{P['surface']};border:1px solid {P['line']};border-radius:12px;}}")
        bl = QHBoxLayout(bar)
        bl.setContentsMargins(12, 9, 12, 9)
        bl.setSpacing(8)
        bl.addWidget(label("下载到", 11, P["text3"]))
        self.dir_label = Elided(str(app.state.project_dir(repo)), P["text2"], 10.5, mono=True)
        bl.addWidget(self.dir_label, 1)
        chg = button("更改", "ghost")
        chg.setFixedHeight(28)
        chg.clicked.connect(self.change_dir)
        bl.addWidget(chg)
        op = icon_button("folder", 28, "打开下载目录", self.open_dir)
        bl.addWidget(op)
        bl.addWidget(label("同名文件", 11, P["text3"]))
        self.rule_combo = QComboBox()
        for k, v in REPLACE_RULES.items():
            self.rule_combo.addItem(v, k)
        cur = app.state.project_rule(repo)
        self.rule_combo.setCurrentIndex(max(0, list(REPLACE_RULES).index(cur)))
        self.rule_combo.currentIndexChanged.connect(self.rule_changed)
        bl.addWidget(self.rule_combo)
        self.root.addWidget(bar)

        # 格式：这里只调「这个项目」的小范围，大范围在设置里
        chips = QHBoxLayout()
        chips.setSpacing(7)
        chips.addWidget(label("本项目格式", 11, P["text3"]))
        self.chips = {}
        labels = dict(app.state.format_menu())
        for key in (self.avail_formats or app.state.global_formats()):
            text = labels.get(key, key.upper())
            c = chip(text, key in self.app.dialog_formats)
            c.setToolTip("只影响这个项目；想改总范围去设置页")
            c.clicked.connect(lambda _=False, k=key: self.toggle_filter(k))
            self.chips[key] = c
            chips.addWidget(c)
        self.reset_fmt_btn = button("全选", "plain")
        self.reset_fmt_btn.setFixedHeight(28)
        self.reset_fmt_btn.setToolTip("把这个项目真实存在的格式全选上")
        self.reset_fmt_btn.clicked.connect(self.reset_filter)
        chips.addWidget(self.reset_fmt_btn)
        chips.addStretch(1)
        self.only_chip = chip("只看选定文件", True)
        self.only_chip.setToolTip("打开后，每个版本只显示选中标签对应的文件；关掉则列出该版本的全部文件")
        self.only_chip.clicked.connect(self.toggle_only)
        chips.addWidget(self.only_chip)
        self.arch_chip = chip("仅 x64", True)
        self.arch_chip.setToolTip("Windows 端只列 x64 / 未标架构的包，点一下可以放开到 ARM64、32 位")
        self.arch_chip.clicked.connect(self.toggle_arch)
        chips.addWidget(self.arch_chip)
        self.root.addLayout(chips)

        self.scroll = QScrollArea()
        self.scroll.setWidgetResizable(True)
        self.scroll.setFrameShape(QFrame.NoFrame)
        self.body = QWidget()
        self.body.setObjectName("pageBody")
        self.bl = QVBoxLayout(self.body)
        self.bl.setContentsMargins(0, 4, 6, 10)
        self.bl.setSpacing(10)
        self.bl.setAlignment(Qt.AlignTop)
        self.scroll.setWidget(self.body)
        self.root.addWidget(self.scroll, 1)

        self.empty_hint = label("这个项目还没有发布版本，或者暂时读取不到", 11.5, P["text3"], wrap=True)
        self.empty_hint.setAlignment(Qt.AlignCenter)
        self.empty_hint.hide()
        self.bl.addWidget(self.empty_hint)
        self.more_btn = button("加载更多版本", "ghost", "refresh", cb=self.load_next)
        self.bl.addWidget(self.more_btn, 0, Qt.AlignHCenter)

        foot = QHBoxLayout()
        foot.setSpacing(10)
        self.foot_hint = label("单个下载模式：点任意文件立即开始", 10.5, P["text3"])
        foot.addWidget(self.foot_hint)
        foot.addStretch(1)
        self.sel_label = label("", 10.5, P["text3"])
        foot.addWidget(self.sel_label)
        closeb = button("关闭", "ghost", cb=self.reject)
        closeb.setMinimumWidth(88)
        foot.addWidget(closeb)
        self.batch_btn = button("测速并下载", "primary", "bolt", cb=self.start_batch)
        self.batch_btn.setMinimumWidth(150)
        self.batch_btn.hide()
        foot.addWidget(self.batch_btn)
        self.root.addLayout(foot)

        self.page_ready.connect(self._on_page)
        self.load_error.connect(self._error)
        QTimer.singleShot(0, self.load_next)
        self._spin = QTimer(self)
        self._spin.timeout.connect(self._tick_loading)
        self._spin.start(400)
        self._dots = 0

    # ---- 数据 ----
    def load_next(self):
        if self.loading or not self.has_more:
            return
        self.loading = True
        self.more_btn.setEnabled(False)
        self.more_btn.setText("正在加载…")
        page = self.next_page
        repo = self.repo
        token = self.app.state.config.get("github_token", "")
        proxy = self.app.state.config.get("proxy", "")
        size = self.PAGE

        def run():
            try:
                raw = api_releases(repo, page=page, per_page=size, token=token, proxy=proxy)
                self.page_ready.emit(raw or [])
            except HttpError as e:
                self.load_error.emit(str(e))
            except Exception as e:
                self.load_error.emit(f"读取失败：{e}")

        threading.Thread(target=run, daemon=True).start()

    def _on_page(self, raw):
        self.loading = False
        batch = [normalize_release(r) for r in raw if not r.get("draft")]
        if len(raw) < self.PAGE:
            self.has_more = False
        self.next_page += 1

        # 合并 → 按 tag 去重（分页边界漂移会重复同一条）→ 按发布时间倒序
        before = [r.get("tag") or "" for r in self.releases]
        merged, seen = [], set()
        for r in self.releases + batch:
            k = (r.get("tag") or "").lower()
            if k and k in seen:
                continue
            if k:
                seen.add(k)
            merged.append(r)
        self.releases = sort_releases(merged)

        # 顺序没被新数据打乱时只追加，避免整列表重建导致滚动跳动
        same_prefix = (len(self.releases) >= len(before)
                       and [r.get("tag") or "" for r in self.releases[:len(before)]] == before)
        if same_prefix:
            self.render_new(self.releases[len(before):])
        else:
            y = self.scroll.verticalScrollBar().value()
            self.clear_groups()
            self.render_new(self.releases)
            self.prune_selection()
            QTimer.singleShot(0, lambda: self.scroll.verticalScrollBar().setValue(y))
        self._spin.stop()
        n = len(self.visible_releases())
        self.count_label.setText(f"已加载 {len(self.releases)} 个版本 · 当前筛选 {n} 个")
        self.empty_hint.setText("没有符合当前筛选的版本，点上方「仅 x64」或格式标签可以放宽条件"
                                if self.releases else "这个项目还没有发布版本，或者暂时读取不到")
        self.empty_hint.setVisible(not n)
        self.count_label.setStyleSheet(f"color:{P['text3']};font-size:10.5px;background:transparent;")
        self.more_btn.setEnabled(self.has_more)
        self.more_btn.setText("加载更多版本" if self.has_more else "已经到底了")

    def _error(self, msg):
        self.loading = False
        self._spin.stop()
        self.count_label.setText(msg)
        self.count_label.setStyleSheet(f"color:{P['danger']};font-size:10.5px;background:transparent;")
        self.more_btn.setEnabled(True)
        self.more_btn.setText("重试")

    def _tick_loading(self):
        if not self.loading:
            return
        self._dots = (self._dots + 1) % 4
        self.count_label.setText("正在读取版本" + "·" * (self._dots + 1))

    # ---- 渲染 ----
    def visible_releases(self):
        out = []
        for r in self.releases:
            if filter_assets(r.get("assets", []), self.app.dialog_formats, self.all_arch):
                out.append(r)
        return out

    def render_new(self, batch):
        for rel in batch:
            if not filter_assets(rel.get("assets", []), self.app.dialog_formats, self.all_arch):
                continue
            g = ReleaseGroup(self.app, self.repo, rel, self.app.state.config.get("version_mode", 0) == 1,
                             self, self.all_arch)
            self.bl.insertWidget(self.bl.count() - 1, g)

    def assets_for(self, rel):
        """返回 (该版本全部文件, 实际显示的文件, 是否命中了选中的标签)。"""
        full = filter_assets(rel.get("assets", []), self.app.dialog_formats, self.all_arch)
        full.sort(key=lambda a: asset_score(a), reverse=True)
        keys = set(self.remembered_vkeys())
        if self.only_variant and keys:
            hit = [a for a in full if a.get("vkey") in keys]
            if hit:
                return full, hit, True
        return full, full, False

    def prune_selection(self):
        """过滤后看不见的文件不能还留在待下载列表里。"""
        keep = set()
        for r in self.releases:
            _, shown, _ = self.assets_for(r)
            keep.update(a["url"] for a in shown)
        drop = [u for u in list(self.selected) if u not in keep]
        for u in drop:
            self.selected.pop(u, None)
        if drop:
            self.update_sel()

    def rerender(self):
        self.clear_groups()
        self.render_new(self.releases)
        self.prune_selection()
        n = len(self.visible_releases())
        self.count_label.setText(f"已加载 {len(self.releases)} 个版本 · 当前筛选 {n} 个")
        self.empty_hint.setVisible(not n)
        self.empty_hint.setText("没有符合当前筛选的版本，点上方「仅 x64」或格式标签可以放宽条件"
                                if self.releases else "这个项目还没有发布版本，或者暂时读取不到")

    def toggle_arch(self):
        self.all_arch = not self.all_arch
        self.arch_chip.setChecked(not self.all_arch)
        self.arch_chip.setText("仅 x64" if not self.all_arch else "含 ARM/32 位")
        self.rerender()
        self.update_sel()

    def toggle_only(self):
        self.only_variant = not self.only_variant
        self.only_chip.setChecked(self.only_variant)
        self.rerender()
        self.update_sel()

    def on_variant(self, asset):
        """点版本里的标签：选中 / 取消（可多选），每个版本只列选中的那些文件。"""
        self.toggle_variant(asset)
        if not self.only_variant:
            self.only_variant = True
            self.only_chip.setChecked(True)
        self.rerender()
        self.update_sel()
        n = len(self.remembered_vkeys())
        self.count_label.setText(
            f"已加载 {len(self.releases)} 个版本 · " + (f"只看 {n} 个标签" if n else "显示全部文件"))

    def toggle_variant(self, asset):
        w = self.app.state.watch_entry(self.repo)
        if not w or not asset.get("vkey"):
            return
        cur = [k for k in (w.get("variants") or []) if k]
        if asset["vkey"] in cur:
            cur.remove(asset["vkey"])
        else:
            cur.append(asset["vkey"])
        w["variants"] = cur
        self.app.state.save()

    def load_more(self):
        self.load_next()

    def clear_groups(self):
        for i in reversed(range(self.bl.count())):
            w = self.bl.itemAt(i).widget()
            if isinstance(w, ReleaseGroup):
                w.setParent(None)
                w.deleteLater()

    def toggle_filter(self, key):
        g = self.avail_formats or self.app.state.global_formats()
        cur = [f for f in self.app.dialog_formats if f in g]
        if key in cur:
            if len(cur) <= 1:
                self.app.toast("至少留一个格式", "warn")
                self.sync_chips()
                return
            cur.remove(key)
        else:
            cur.append(key)
        self.set_project_formats(cur)

    def reset_filter(self):
        self.set_project_formats(list(self.avail_formats or self.app.state.global_formats()))

    def set_project_formats(self, fmts):
        g = self.avail_formats or self.app.state.global_formats()
        self.app.dialog_formats = [f for f in g if f in fmts] or list(g)
        w = self.app.state.watch_entry(self.repo)
        if w is not None:
            w["formats"] = [] if set(self.app.dialog_formats) >= set(g) else list(self.app.dialog_formats)
            self.app.state.save()
        self.sync_chips()
        self.rerender()
        self.update_sel()

    def sync_chips(self):
        for k, c in self.chips.items():
            c.setChecked(k in self.app.dialog_formats)
        if hasattr(self.app, "watch_page"):
            self.app.watch_page.rebuild()

    def open_dir(self):
        d = self.app.state.project_dir(self.repo)
        d.mkdir(parents=True, exist_ok=True)
        open_path(d)

    def change_dir(self):
        d = QFileDialog.getExistingDirectory(self, "选择这个项目的下载目录",
                                             str(self.app.state.project_dir(self.repo)))
        if d:
            w = self.app.state.watch_entry(self.repo)
            if w:
                w["dir"] = d
            self.app.state.save()
            self.dir_label.setText(d)

    def rule_changed(self, idx):
        key = self.rule_combo.itemData(idx)
        w = self.app.state.watch_entry(self.repo)
        if w:
            w["rule"] = key
            self.app.state.save()

    # ---- 选择 / 下载 ----
    def remembered_vkeys(self):
        w = self.app.state.watch_entry(self.repo) or {}
        return [k for k in (w.get("variants") or []) if k]

    def remember_variant(self, asset):
        """记住「上次下载的是哪个文件」，只用于推荐，不当作筛选条件。"""
        w = self.app.state.watch_entry(self.repo)
        if not w or not asset.get("vkey"):
            return
        if w.get("last_vkey") != asset["vkey"]:
            w["last_vkey"] = asset["vkey"]
            self.app.state.save()

    def is_selected(self, asset):
        return asset["url"] in self.selected

    def toggle(self, asset, on):
        if on:
            self.selected[asset["url"]] = asset
            if len(self.selected) == 1:
                self.remember_variant(asset)
        else:
            self.selected.pop(asset["url"], None)
        self.update_sel()

    def update_sel(self):
        n = len(self.selected)
        self.sel_label.setText(f"已选 {n} 个" if n else "")
        if self.app.state.config.get("version_mode", 0) == 1:
            self.batch_btn.setText(f"测速并下载 {n} 个" if n else "测速并下载")
            self.batch_btn.setEnabled(n > 0)
            self.batch_btn.show()
            self.foot_hint.setText("批量模式：勾选后一次性下载；只勾一个时会记住这个标签")
        else:
            self.batch_btn.hide()
            self.foot_hint.setText("单个下载模式：点任意文件立即开始，并记住这个标签")

    def pick_single(self, asset):
        self.remember_variant(asset)
        self.app.download_assets(self.repo, [asset], parent=self)
        self.accept()

    def start_batch(self):
        if not self.selected:
            return
        assets = list(self.selected.values())
        self.app.download_assets(self.repo, assets, parent=self)
        self.accept()

    def showEvent(self, ev):
        super().showEvent(ev)
        self.update_sel()
# --------------------------------------------------------------------------
# 下载记录
# --------------------------------------------------------------------------


class HistoryRow(QFrame):
    def __init__(self, app, rec, page, parent=None):
        super().__init__(parent)
        self.app = app
        self.rec = rec
        self.page = page
        self.setObjectName("card")
        self.setStyleSheet(f"QFrame#card{{background:{P['surface']};border:1px solid {P['line']};border-radius:12px;}}")
        h = QHBoxLayout(self)
        h.setContentsMargins(12, 10, 12, 10)
        h.setSpacing(10)

        self.check = Check(page.selected.get(rec.get("id"), False),
                           lambda v: page.toggle_select(rec.get("id"), v))
        self.check.setVisible(page.picking)
        h.addWidget(self.check)

        ic = QLabel()
        ic.setFixedSize(34, 34)
        ic.setAlignment(Qt.AlignCenter)
        ic.setStyleSheet(f"background:{P['surface2']};border-radius:9px;")
        ic.setPixmap(icon_pixmap("file", P["text2"], 16))
        h.addWidget(ic)

        box = QVBoxLayout()
        box.setSpacing(2)
        name = Elided(rec.get("name", "未命名"), P["text"], 12.5)
        f = name.font()
        f.setBold(True)
        name.setFont(f)
        path = Elided(rec.get("path", ""), P["text3"], 10, mono=True)
        box.addWidget(name)
        box.addWidget(path)
        h.addLayout(box, 1)

        ok = rec.get("status") == "done"
        st = pill("已完成" if ok else "失败", P["primary"] if ok else P["danger"],
                  P["primary_soft"] if ok else P["danger_soft"], 9)
        h.addWidget(st)
        h.addWidget(label(fmt_size(rec.get("size")), 10.5, P["text3"]))
        h.addWidget(label(fmt_ts_short(rec.get("ts")), 10.5, P["text3"]))

        op = icon_button("external", 30, "打开文件", self.open_file)
        fo = icon_button("folder", 30, "打开所在文件夹", self.open_folder)
        de = icon_button("trash", 30, "删除", self.remove)
        h.addWidget(op)
        h.addWidget(fo)
        h.addWidget(de)

    def open_file(self):
        p = Path(self.rec.get("path", ""))
        if p.exists():
            open_path(p)
        else:
            self.app.toast("文件已经不在了，可能被移动或删除", "warn")

    def open_folder(self):
        p = Path(self.rec.get("path", ""))
        if p.exists():
            reveal_path(p)
        elif p.parent.exists():
            open_path(p.parent)
        else:
            self.app.toast("目录不存在", "warn")

    def remove(self):
        self.page.ask_delete([self.rec.get("id")], single=True)


class HistoryPage(PageBase):
    def __init__(self, app):
        super().__init__(app)
        self.selected = {}
        self.picking = False
        self.filter = "all"
        self.rows = []
        right = QWidget()
        rh = QHBoxLayout(right)
        rh.setContentsMargins(0, 0, 0, 0)
        rh.setSpacing(8)
        self.pick_chip = chip("选择", False, self.toggle_pick)
        rh.addWidget(self.pick_chip)
        rh.addWidget(icon_button("trash", 34, "清空记录", self.ask_clear_all))
        self.build_header("下载记录", "共 0 条 · 保存在本机", right)

        chips = QHBoxLayout()
        chips.setSpacing(7)
        self.filters = {}
        for key, text in (("all", "全部"), ("done", "已完成"), ("error", "失败"), ("canceled", "已取消")):
            c = chip(text, key == "all")
            c.clicked.connect(lambda _=False, k=key: self.set_filter(k))
            self.filters[key] = c
            chips.addWidget(c)
        chips.addStretch(1)
        self.count = label("", 10.5, P["text3"])
        chips.addWidget(self.count)
        self.v.addLayout(chips)

        self.list_box = QVBoxLayout()
        self.list_box.setSpacing(8)
        self.v.addLayout(self.list_box)
        self.empty = EmptyState("history", "还没有下载记录", "下载完成的文件会出现在这里，可一键打开或定位")
        self.v.addWidget(self.empty)

        self.bar = QFrame()
        self.bar.setObjectName("card")
        self.bar.setStyleSheet(
            f"QFrame#card{{background:{P['surface2']};border:1px solid {P['line2']};border-radius:12px;}}")
        bh = QHBoxLayout(self.bar)
        bh.setContentsMargins(12, 8, 12, 8)
        self.sel_info = label("已选 0 条", 11.5, P["text2"])
        bh.addWidget(self.sel_info)
        bh.addStretch(1)
        allb = button("全选", "ghost", cb=self.select_all)
        allb.setFixedHeight(30)
        bh.addWidget(allb)
        delb = button("删除选中", "danger", cb=lambda: self.ask_delete(list(self.selected)))
        delb.setFixedHeight(30)
        bh.addWidget(delb)
        self.bar.hide()
        bar_wrap = QWidget()
        bwl = QHBoxLayout(bar_wrap)
        bwl.setContentsMargins(30, 0, 30, 16)
        bwl.addWidget(self.bar)
        self.outer.addWidget(bar_wrap)

    def rebuild(self):
        while self.list_box.count():
            it = self.list_box.takeAt(0)
            w = it.widget()
            if w:
                w.setParent(None)
                w.deleteLater()
        self.rows = []
        recs = self.app.state.history
        if self.filter != "all":
            recs = [r for r in recs if (r.get("status") or "done") == self.filter]
        for r in recs[: self.app.state.config.get("history_max", 200)]:
            row = HistoryRow(self.app, r, self)
            self.list_box.addWidget(row)
            self.rows.append(row)
        total = len(self.app.state.history)
        self.count.setText("" if self.filter == "all" else f"筛选出 {len(recs)} 条")
        self.sub_label.setText(f"共 {total} 条 · 保存在本机" if not self.picking
                               else f"已选 {len(self.selected)} 条 · 点「选择」退出多选")
        self.empty.setVisible(not recs)
        self.sel_info.setText(f"已选 {len(self.selected)} 条")
        self.sub_label.setText(f"已选 {len(self.selected)} 条 · 点「选择」退出多选")
        self.bar.setVisible(self.picking and bool(self.app.state.history))
        self.sub_label.setText(f"共 {len(self.app.state.history)} 条 · 保存在本机" if not self.picking
                               else f"已选 {len(self.selected)} 条 · 点「选择」退出多选")

    def set_filter(self, key):
        self.filter = key
        for k, c in self.filters.items():
            c.setChecked(k == key)
        self.rebuild()

    def toggle_pick(self):
        self.picking = not self.picking
        self.pick_chip.setChecked(self.picking)
        if not self.picking:
            self.selected.clear()
        self.rebuild()

    def toggle_select(self, rid, on):
        if on:
            self.selected[rid] = True
        else:
            self.selected.pop(rid, None)
        self.sel_info.setText(f"已选 {len(self.selected)} 条")
        self.sub_label.setText(f"已选 {len(self.selected)} 条 · 点「选择」退出多选")

    def select_all(self):
        recs = self.app.state.history
        if self.filter != "all":
            recs = [r for r in recs if (r.get("status") or "done") == self.filter]
        if len(self.selected) >= len(recs):
            self.selected.clear()
        else:
            self.selected = {r.get("id"): True for r in recs}
        self.rebuild()

    def ask_delete(self, ids, single=False):
        ids = [i for i in ids if i]
        if not ids:
            self.app.toast("先选中要删除的记录", "warn")
            return
        n = len(ids)
        title = "删除这条记录" if single else f"删除 {n} 条记录"
        idx = ask_choice(self, title, "只删记录不会动文件；一起删会把磁盘上的文件也删掉，删除后不能恢复。",
                         [("只删记录", "列表里不再显示，文件保留", ""),
                          ("记录和文件一起删", "同时删除磁盘上的文件", "danger")])
        if idx < 0:
            return
        self.app.state.delete_history(ids, remove_files=(idx == 1))
        self.selected.clear()
        self.rebuild()
        self.app.refresh_history()
        self.app.toast("已删除" if idx == 1 else "已删除记录", "ok")

    def ask_clear_all(self):
        if not self.app.state.history:
            self.app.toast("还没有记录", "warn")
            return
        idx = ask_choice(self, "清空下载记录", f"共 {len(self.app.state.history)} 条记录，清空后不能恢复。",
                         [("只清空记录", "磁盘上的文件保留", ""),
                          ("记录和文件一起删", "会删掉这些记录指向的所有文件", "danger")])
        if idx < 0:
            return
        ids = [h.get("id") for h in self.app.state.history]
        self.app.state.delete_history(ids, remove_files=(idx == 1))
        self.rebuild()
        self.app.refresh_history()
        self.app.toast("已清空", "ok")

    def on_show(self):
        self.rebuild()


# --------------------------------------------------------------------------
# 镜像源
# --------------------------------------------------------------------------


class MirrorPrefRow(QWidget):
    def __init__(self, app, m, idx, page, parent=None):
        super().__init__(parent)
        self.app = app
        self.m = m
        self.idx = idx
        self.page = page
        self.setFixedHeight(58)
        h = QHBoxLayout(self)
        h.setContentsMargins(4, 0, 4, 0)
        h.setSpacing(10)
        handle = QLabel()
        handle.setPixmap(icon_pixmap("grid", P["text3"], 15))
        handle.setFixedWidth(16)
        h.addWidget(handle)
        box = QVBoxLayout()
        box.setSpacing(1)
        box.addWidget(label(m["name"], 12.5, P["text"], bold=True))
        mode = "拼 GitHub 路径" if m.get("mode") == "path" else ("直连" if not m.get("base") else "拼完整链接")
        info = f"{m.get('base') or 'github.com'} · {mode}"
        if m.get("speed"):
            info += f" · 上次 {fmt_speed(m['speed'])} · {m.get('latency', 0)} ms"
        elif m.get("fail"):
            info += f" · 连续失败 {m['fail']} 次"
        else:
            info += " · 还没测过"
        box.addWidget(label(info, 10, P["text3"]))
        h.addLayout(box, 1)

        up = icon_button("chevron-up", 26, "上移", lambda: page.move(idx, -1))
        up.setStyleSheet("QPushButton{border:none;background:transparent;}")
        up.setEnabled(idx > 0)
        dn = icon_button("chevron-down", 26, "下移", lambda: page.move(idx, 1))
        dn.setStyleSheet("QPushButton{border:none;background:transparent;}")
        h.addWidget(up)
        h.addWidget(dn)

        sw = Switch(m.get("enabled", True), lambda on: page.toggle(idx, on))
        h.addWidget(sw)
        dele = button("删除", "plain")
        dele.clicked.connect(lambda: page.delete(idx))
        h.addWidget(dele)


class MirrorsPage(PageBase):
    def __init__(self, app):
        super().__init__(app)
        self.build_header("镜像源", "顺序 = 测速顺延顺序，同分时靠前优先")
        self.build_note()
        logb = button("测速日志", "ghost", "file", cb=lambda: open_path(log_path()))
        logb.setFixedHeight(28)
        self.v.addLayout(self.section("候选列表", right=logb))
        self.list_card = make_card("", (10, 4, 10, 4), 0)
        self.list_box = QVBoxLayout()
        self.list_box.setSpacing(2)
        self.list_card.body.addLayout(self.list_box)
        self.v.addWidget(self.list_card)
        self.build_add()
        self.rebuild()

    def build_note(self):
        card = make_card("", (16, 14, 16, 14), 8)
        card.setStyleSheet(
            f"QFrame#card{{background:qlineargradient(x1:0,y1:0,x2:1,y2:1,"
            f"stop:0 {rgba(P['accent'], .10)}, stop:0.8 {P['surface']});"
            f"border:1px solid {rgba(P['accent'], .18)};border-radius:16px;}}")
        row = QHBoxLayout()
        row.setSpacing(10)
        ic = QLabel()
        ic.setPixmap(icon_pixmap("info", P["accent"], 18))
        row.addWidget(ic, 0, Qt.AlignTop)
        kb = self.app.state.config.get("probe_kb", 256)
        self.note = label(
            f"逐个发 {kb} KB Range 请求，得分 = 速度 70% + 延迟 30%。多个文件可同时下载，共用这份排名；"
            f"失败按排名顺延，最多重试 {self.app.state.config.get('failover_max', 5)} 个镜像。",
            11.5, P["text2"], wrap=True)
        row.addWidget(self.note, 1)
        card.body.addLayout(row)
        self.v.addWidget(card)

    def rebuild(self):
        while self.list_box.count():
            it = self.list_box.takeAt(0)
            w = it.widget()
            if w:
                w.setParent(None)
                w.deleteLater()
        for i, m in enumerate(self.app.state.mirrors):
            self.list_box.addWidget(MirrorPrefRow(self.app, m, i, self))
            if i < len(self.app.state.mirrors) - 1:
                self.list_box.addWidget(hline())
        enabled = sum(1 for m in self.app.state.mirrors if m.get("enabled", True))
        self.note.setText(
            f"逐个发 {self.app.state.config.get('probe_kb', 256)} KB Range 请求，得分 = 速度 70% + 延迟 30%。"
            f"多个文件可同时下载，共用这份排名；失败按排名顺延，最多重试 "
            f"{self.app.state.config.get('failover_max', 5)} 个镜像。当前启用 {enabled} / {len(self.app.state.mirrors)} 个。")

    def toggle(self, idx, on):
        self.app.state.mirrors[idx]["enabled"] = on
        self.app.state.save()
        self.app.home_page.rebuild_mirrors()

    def move(self, idx, delta):
        j = idx + delta
        if 0 <= j < len(self.app.state.mirrors):
            ms = self.app.state.mirrors
            ms[idx], ms[j] = ms[j], ms[idx]
            self.app.state.save()
            self.rebuild()
            self.app.home_page.rebuild_mirrors()

    def delete(self, idx):
        m = self.app.state.mirrors[idx]
        dlg_idx = ask_choice(self, "删除镜像", f"删除 {m['name']}？之后不会再参与测速和下载。",
                             [("删除", "内置镜像可在「恢复内置」里找回", "danger")])
        if dlg_idx < 0:
            return
        self.app.state.mirrors.pop(idx)
        self.app.state.save()
        self.rebuild()
        self.app.home_page.rebuild_mirrors()
        self.app.toast("已删除镜像")

    def restore(self):
        existing = {m["name"] for m in self.app.state.mirrors}
        added = 0
        for m in DEFAULT_MIRRORS:
            if m["name"] not in existing:
                self.app.state.mirrors.append(dict(m, enabled=True, latency=0, speed=0.0, fail=0, checked=0))
                added += 1
        self.app.state.save()
        self.rebuild()
        self.app.home_page.rebuild_mirrors()
        self.app.toast(f"已恢复 {added} 个内置镜像" if added else "内置镜像都在", "ok")

    def build_add(self):
        head = QHBoxLayout()
        head.addLayout(self.section("添加自定义镜像"))
        restore = button("恢复内置", "ghost", cb=self.restore)
        restore.setFixedHeight(28)
        head.addWidget(restore)
        self.v.addLayout(head)
        card = make_card("", (16, 14, 16, 14), 10)
        row = QHBoxLayout()
        row.setSpacing(10)
        self.add_edit = QLineEdit()
        self.add_edit.setPlaceholderText("https://你的镜像域名/")
        self.add_edit.setProperty("mono", True)
        row.addWidget(self.add_edit, 1)
        addb = button("添加", "primary", "plus", 38, self.add_mirror)
        addb.setMinimumWidth(92)
        row.addWidget(addb)
        card.body.addLayout(row)
        chips = QHBoxLayout()
        chips.setSpacing(7)
        self.add_mode = "prefix"
        self.mode_prefix = chip("拼完整链接", True, lambda: self.set_mode("prefix"))
        self.mode_path = chip("拼 GitHub 路径", False, lambda: self.set_mode("path"))
        chips.addWidget(self.mode_prefix)
        chips.addWidget(self.mode_path)
        chips.addStretch(1)
        card.body.addLayout(chips)
        self.add_hint = label("拼完整链接：https://ghfast.top/ → 后面接完整的 https://github.com/owner/repo/...",
                              10.5, P["text3"], wrap=True)
        card.body.addWidget(self.add_hint)
        self.v.addWidget(card)

    def set_mode(self, mode):
        self.add_mode = mode
        self.mode_prefix.setChecked(mode == "prefix")
        self.mode_path.setChecked(mode == "path")
        self.add_hint.setText(
            "拼完整链接：https://ghfast.top/ → 后面接完整的 https://github.com/owner/repo/..."
            if mode == "prefix" else
            "拼 GitHub 路径：https://xget.xi-xu.me/gh/ → 后面接 owner/repo/releases/...")

    def add_mirror(self):
        base = self.add_edit.text().strip()
        if not base.startswith("http"):
            self.app.toast("镜像地址要以 http(s):// 开头", "warn")
            return
        base = base.rstrip("/") + "/"
        name = urllib.parse.urlparse(base).netloc or base
        if any(m.get("base") == base for m in self.app.state.mirrors):
            self.app.toast("这个镜像已经有了", "warn")
            return
        self.app.state.mirrors.append({
            "name": name, "base": base, "mode": self.add_mode, "enabled": True,
            "latency": 0, "speed": 0.0, "fail": 0, "checked": 0,
        })
        self.app.state.save()
        self.add_edit.clear()
        self.rebuild()
        self.app.home_page.rebuild_mirrors()
        self.app.toast("已添加镜像", "ok")

    def on_show(self):
        self.rebuild()


# --------------------------------------------------------------------------
# 设置
# --------------------------------------------------------------------------


def setting_row(key, sub, control, cb=None):
    row = QWidget()
    h = QHBoxLayout(row)
    h.setContentsMargins(14, 11, 14, 11)
    h.setSpacing(12)
    box = QVBoxLayout()
    box.setSpacing(2)
    box.addWidget(label(key, 12.5, P["text"]))
    if sub:
        box.addWidget(label(sub, 10.5, P["text3"]))
    h.addLayout(box, 1)
    h.addWidget(control, 0, Qt.AlignVCenter)
    if cb:
        row.setCursor(QCursor(Qt.PointingHandCursor))
        row.mousePressEvent = lambda ev: cb()
    return row


def setting_card(rows):
    card = make_card("", (2, 2, 2, 2), 0)
    for i, r in enumerate(rows):
        card.body.addWidget(r)
        if i < len(rows) - 1:
            card.body.addWidget(hline())
    return card


def combo(options, current, cb, width=132):
    c = QComboBox()
    for text, val in options:
        c.addItem(text, val)
    try:
        c.setCurrentIndex([v for _, v in options].index(current))
    except ValueError:
        c.setCurrentIndex(0)
    c.setFixedWidth(width)
    c.currentIndexChanged.connect(lambda i: cb(c.itemData(i)))
    return c


class Stepper(QWidget):
    """− / 数字 / + 三段式数字调节器：1–50 这种范围能一眼看懂、也能直接输入。"""

    changed = Signal(int)

    def __init__(self, lo, hi, value, suffix="", step=1, parent=None):
        super().__init__(parent)
        self.lo, self.hi = int(lo), int(hi)
        self.step = max(1, int(step))
        self._val = max(self.lo, min(self.hi, int(value)))
        lay = QHBoxLayout(self)
        lay.setContentsMargins(0, 0, 0, 0)
        lay.setSpacing(5)
        self.minus = self._btn("−")
        self.plus = self._btn("+")
        self.edit = QLineEdit(str(self._val))
        self.edit.setFixedSize(54, 30)
        self.edit.setAlignment(Qt.AlignCenter)
        self.edit.setValidator(QIntValidator(self.lo, self.hi, self))
        self.edit.setStyleSheet(
            f"QLineEdit{{min-height:30px;max-height:30px;padding:0 2px;border-radius:8px;"
            f"font-weight:700;font-size:12.5px;}}")
        lay.addWidget(self.minus)
        lay.addWidget(self.edit)
        if suffix:
            lay.addWidget(label(suffix, 11, P["text3"]))
        lay.addWidget(self.plus)
        self.setFixedHeight(32)
        self.minus.clicked.connect(lambda: self.set_value(self._val - self.step))
        self.plus.clicked.connect(lambda: self.set_value(self._val + self.step))
        self.edit.editingFinished.connect(self._commit)

    def _btn(self, text):
        b = QToolButton()
        b.setText(text)
        b.setFixedSize(30, 30)
        b.setCursor(QCursor(Qt.PointingHandCursor))
        b.setAutoRepeat(True)
        b.setAutoRepeatDelay(380)
        b.setAutoRepeatInterval(70)
        b.setStyleSheet(
            f"QToolButton{{background:{P['surface2']};border:1px solid {P['line']};"
            f"border-radius:8px;color:{P['text']};font-size:15px;font-weight:700;}}"
            f"QToolButton:hover{{border-color:{rgba(P['primary'],.45)};color:{P['primary']};}}"
            f"QToolButton:pressed{{background:{P['primary_soft']};}}")
        return b

    def value(self):
        return self._val

    def set_value(self, v, emit=True):
        try:
            v = int(v)
        except Exception:
            v = self._val
        v = max(self.lo, min(self.hi, v))
        if v == self._val and self.edit.text() == str(v):
            return
        self._val = v
        self.edit.setText(str(v))
        if emit:
            self.changed.emit(v)

    def _commit(self):
        self.set_value(self.edit.text() or self._val)

    def wheelEvent(self, ev):
        self.set_value(self._val + (self.step if ev.angleDelta().y() > 0 else -self.step))
        ev.accept()


def spin(lo, hi, current, suffix, cb, width=124, step=1):
    """兼容旧调用名：返回 Stepper 并接上回调。"""
    s = Stepper(lo, hi, current, suffix, step)
    s.changed.connect(lambda v: cb(int(v)))
    return s


class SettingsPage(PageBase):
    def __init__(self, app):
        super().__init__(app)
        self.build_header("设置", "下载、测速与版本页策略")
        self.build_download()
        self.build_version()
        self.build_speed()
        self.build_network()
        self.build_about()

    def _clear_layout(self, lay):
        while lay.count():
            it = lay.takeAt(0)
            w = it.widget()
            if w:
                w.setParent(None)
                w.deleteLater()
            elif it.layout():
                self._clear_layout(it.layout())

    def reload(self):
        self._clear_layout(self.v)
        self.build_download()
        self.build_version()
        self.build_speed()
        self.build_network()
        self.build_about()

    def cfg(self):
        return self.app.state.config

    def save(self):
        self.app.state.save()

    # ---- 下载 ----
    def build_download(self):
        c = self.cfg()
        self.v.addLayout(self.section("下载"))
        rows = []
        root = c["download_root"]
        loc = button(self._short_root(root), "ghost")
        loc.clicked.connect(self.pick_root)
        rows.append(setting_row("默认保存位置", "点一下换目录 · 未单独设置的项目都放这里", loc))
        conc = spin(1, 50, c["concurrency"], " 个文件", self.set_conc, 136)
        rows.append(setting_row("同时下载任务数", "1–50 个文件同时下载，共用同一份镜像排名", conc))
        sw = Switch(c["segments"], lambda v: self.set_bool("segments", v))
        rows.append(setting_row("单文件多线程", "切片并发拉取，镜像支持 Range 时生效", sw))
        th = spin(1, 50, c["threads"], " 线程", self.set_threads, 112)
        rows.append(setting_row("每个文件连接数", "1–50 段并发，机器和带宽够就拉满；网络差就调小", th))
        sw2 = Switch(c["failover"], lambda v: self.set_bool("failover", v))
        rows.append(setting_row("失败自动换镜像", "按测速排名顺延", sw2))
        fm = combo([(f"{i} 个", i) for i in range(1, 11)], c["failover_max"], self.set_failover)
        rows.append(setting_row("最多重试几个镜像", "一个失败就换下一个，超过就判失败", fm))
        sw3 = Switch(c["resume"], lambda v: self.set_bool("resume", v))
        rows.append(setting_row("断点续传", "镜像支持 Range 时，中断后从断点接上", sw3))
        self.v.addWidget(setting_card(rows))

    def _short_root(self, p):
        s = str(p)
        home = str(Path.home())
        if s.startswith(home):
            s = "~" + s[len(home):]
        if len(s) > 38:
            s = "…" + s[-36:]
        return s + "  ›"

    def pick_root(self):
        d = QFileDialog.getExistingDirectory(self, "选择默认下载目录", self.cfg()["download_root"])
        if d:
            self.cfg()["download_root"] = d
            self.save()
            self.reload()
            self.app.watch_page.rebuild()
            self.app.toast("默认下载目录已更新", "ok")

    def set_conc(self, v):
        self.cfg()["concurrency"] = max(1, min(50, int(v)))
        self.save()
        self.app.dm.pump()
        self.app.home_page.rebuild_mirrors()
        self.app.update_tip()

    def set_threads(self, v):
        self.cfg()["threads"] = max(1, min(50, int(v)))
        self.save()
        self.app.update_tip()

    def set_failover(self, v):
        self.cfg()["failover_max"] = int(v)
        self.save()
        self.app.update_tip()

    def set_bool(self, key, v):
        self.cfg()[key] = bool(v)
        self.save()

    # ---- 版本 ----
    def build_version(self):
        c = self.cfg()
        self.v.addLayout(self.section("版本页"))
        seg = SegSlider(("单个下载", "批量勾选"), c.get("version_mode", 0))
        seg.changed.connect(self.set_mode)
        sub = label("点一个文件就直接下载，适合只要一个包；批量模式先勾选再统一开跑。", 10.5, P["text3"], wrap=True)
        row = QWidget()
        h = QHBoxLayout(row)
        h.setContentsMargins(14, 12, 14, 12)
        h.setSpacing(16)
        box = QVBoxLayout()
        box.setSpacing(3)
        box.addWidget(label("挑文件方式", 12.5, P["text"]))
        box.addWidget(sub)
        h.addLayout(box, 1)
        h.addWidget(seg, 0, Qt.AlignVCenter)
        card1 = make_card("", (2, 2, 2, 2), 0)
        card1.body.addWidget(row)
        self.v.addWidget(card1)

        rows = []
        fmt_row = QWidget()
        fv = QVBoxLayout(fmt_row)
        fv.setContentsMargins(14, 12, 14, 12)
        fv.setSpacing(9)
        fbox = QVBoxLayout()
        fbox.setSpacing(2)
        fbox.addWidget(label("桌面端认哪些格式（大范围）", 12.5, P["text"]))
        fbox.addWidget(label("这里是总开关；每个项目还能在追踪卡片上再收窄成自己的小范围",
                             10.5, P["text3"]))
        fv.addLayout(fbox)
        self.fmt_chips_host = QWidget()
        self.fmt_chips_lay = FlowLayout(self.fmt_chips_host, 0, 7, 7)
        fv.addWidget(self.fmt_chips_host)
        fchips = QHBoxLayout()
        fchips.setSpacing(7)
        addf = button("+ 添加格式", "plain")
        addf.setToolTip("手动加一个扩展名，例如 iso、epub")
        addf.clicked.connect(self.add_format)
        fchips.addWidget(addf)
        reset = button("恢复默认", "plain")
        reset.setToolTip("回到 EXE / MSI / ZIP / RAR / PDF")
        reset.clicked.connect(self.reset_formats)
        fchips.addWidget(reset)
        fchips.addStretch(1)
        fv.addLayout(fchips)
        fv.addWidget(label("右键自定义格式可以删掉；内置格式只能取消勾选。",
                           10, P["text3"], wrap=True))
        self.build_fmt_chips()
        rows.append(fmt_row)
        rule = combo([(v, k) for k, v in REPLACE_RULES.items()], c["replace_rule"], self.set_rule, 150)
        rows.append(setting_row("同名文件默认规则", "项目没有单独设置时用这条；每个项目可覆盖", rule))
        self.v.addWidget(setting_card(rows))

    def build_fmt_chips(self):
        while self.fmt_chips_lay.count():
            it = self.fmt_chips_lay.takeAt(0)
            w = it.widget()
            if w:
                w.setParent(None)
                w.deleteLater()
        self.fmt_chips = {}
        fmts = self.app.state.global_formats()
        for ext, text in self.app.state.format_menu():
            cbtn = chip(text, ext in fmts)
            cbtn.setToolTip("勾上就追踪；自定义格式右键可删")
            cbtn.clicked.connect(lambda _=False, e=ext: self.toggle_fmt(e))
            if ext not in {k for k, _ in BUILTIN_FORMATS}:
                cbtn.setContextMenuPolicy(Qt.CustomContextMenu)
                cbtn.customContextMenuRequested.connect(
                    lambda pos, e=ext: self.remove_format(e))
            self.fmt_chips[ext] = cbtn
            self.fmt_chips_lay.addWidget(cbtn)

    def remove_format(self, ext):
        from PySide6.QtWidgets import QMessageBox
        ok = QMessageBox.question(self, "删除格式", f"从菜单里删掉 {ext.upper()}？\n"
                                  "只影响以后的选择，已下载的文件不动。")
        if ok != QMessageBox.Yes:
            return
        cfg = self.cfg()
        extra = cfg.setdefault("extra_formats", [])
        if ext in extra:
            extra.remove(ext)
        if ext in cfg["formats"] and len(cfg["formats"]) > 1:
            cfg["formats"].remove(ext)
        self.save()
        self.build_fmt_chips()
        self.app.watch_page.refresh_format_hint()
        self.app.watch_page.rebuild()
        self.app.toast(f"已删除 {ext.upper()}", "ok")

    def reset_formats(self):
        cfg = self.cfg()
        cfg["formats"] = list(DEFAULT_FORMATS)
        self.save()
        self.build_fmt_chips()
        self.app.watch_page.refresh_format_hint()
        self.app.watch_page.rebuild()
        self.app.toast("已恢复默认格式范围", "ok")

    def add_format(self):
        ext = ask_text(self, "添加格式",
                       "输入扩展名，例如 iso、epub。加进去以后会出现在格式菜单里，并可勾选启用。",
                       "", "例如 iso")
        if ext is None:
            return
        e = re.sub(r"[^a-z0-9]", "", ext.lower())
        if not e:
            self.app.toast("扩展名不合法", "warn")
            return
        cfg = self.cfg()
        extra = cfg.setdefault("extra_formats", [])
        if e not in extra and e not in {k for k, _ in BUILTIN_FORMATS}:
            extra.append(e)
        if e not in cfg["formats"]:
            cfg["formats"].append(e)
        self.save()
        self.build_fmt_chips()
        self.app.watch_page.refresh_format_hint()
        self.app.watch_page.rebuild()
        self.app.toast(f"已添加 {e.upper()}", "ok")

    def toggle_fmt(self, ext):
        fmts = self.cfg()["formats"]
        if ext in fmts:
            if len(fmts) == 1:
                c = self.fmt_chips.get(ext)
                if c:
                    c.setChecked(True)
                self.app.toast("至少留一个格式", "warn")
                return
            fmts.remove(ext)
        else:
            fmts.append(ext)
        c = self.fmt_chips.get(ext)
        if c:
            c.setChecked(ext in fmts)
        self.save()
        self.app.watch_page.refresh_format_hint()
        self.app.watch_page.rebuild()

    def set_rule(self, v):
        self.cfg()["replace_rule"] = v
        self.save()
        self.app.watch_page.rebuild()

    def set_mode(self, v):
        self.cfg()["version_mode"] = int(v)
        self.save()

    # ---- 测速 ----
    def build_speed(self):
        c = self.cfg()
        self.v.addLayout(self.section("测速"))
        rows = []
        kb = combo([(f"{k} KB", k) for k in (64, 128, 256, 512)], c["probe_kb"], self.set_probe)
        rows.append(setting_row("探测分片", "越大越准，也越费流量", kb))
        to = combo([(f"{t} 秒", t) for t in (5, 8, 12, 20)], c["timeout"], self.set_timeout)
        rows.append(setting_row("单个镜像超时", "超时直接判负，不拖慢整体", to))
        self.v.addWidget(setting_card(rows))

    def set_probe(self, v):
        self.cfg()["probe_kb"] = int(v)
        self.save()
        self.app.mirrors_page.rebuild()

    def set_timeout(self, v):
        self.cfg()["timeout"] = int(v)
        self.save()

    # ---- 网络 / 外观 ----
    def build_network(self):
        c = self.cfg()
        self.v.addLayout(self.section("网络与外观"))
        rows = []
        tok = QLineEdit(c.get("github_token", ""))
        tok.setPlaceholderText("可选，填了每小时 5000 次")
        tok.setFixedWidth(230)
        tok.setEchoMode(QLineEdit.Password)
        tok.editingFinished.connect(lambda: self.set_text("github_token", tok.text()))
        rows.append(setting_row("GitHub Token", "只读公开接口，不填也能用；额度用完了再填", tok))
        px = QLineEdit(c.get("proxy", ""))
        px.setPlaceholderText("如 http://127.0.0.1:7890")
        px.setFixedWidth(230)
        px.editingFinished.connect(lambda: self.set_text("proxy", px.text()))
        rows.append(setting_row("代理", "留空则直连；支持 http / https 代理", px))
        theme_row = QWidget()
        th = QHBoxLayout(theme_row)
        th.setContentsMargins(14, 11, 14, 11)
        tbox = QVBoxLayout()
        tbox.setSpacing(2)
        tbox.addWidget(label("外观主题", 12.5, P["text"]))
        tbox.addWidget(label("深色适合夜间，浅色适合白天，黑白用于墨水屏", 10.5, P["text3"]))
        th.addLayout(tbox, 1)
        self.theme_chips = {}
        for key, text in (("dark", "深色"), ("light", "浅色"), ("mono", "黑白")):
            cb = chip(text, c["theme"] == key)
            cb.clicked.connect(lambda _=False, k=key: self.set_theme(k))
            self.theme_chips[key] = cb
            th.addWidget(cb)
        rows.append(theme_row)
        self.v.addWidget(setting_card(rows))

    def set_text(self, key, val):
        self.cfg()[key] = val.strip()
        self.save()

    def set_theme(self, key):
        self.cfg()["theme"] = key
        self.save()
        self.app.apply_theme(key)

    # ---- 记录 / 关于 ----
    def build_about(self):
        c = self.cfg()
        self.v.addLayout(self.section("记录与关于"))
        rows = []
        hm = combo([(f"{n} 条", n) for n in (50, 100, 200, 500)], c["history_max"], self.set_history_max)
        rows.append(setting_row("历史记录条数", "超出后自动丢弃最旧的，不会删文件", hm))
        clear = button("清空记录", "danger")
        clear.clicked.connect(self.app.history_page.ask_clear_all)
        rows.append(setting_row("下载记录", "清空列表，可选是否连文件一起删", clear))
        log_tools = QWidget()
        lt = QHBoxLayout(log_tools)
        lt.setContentsMargins(0, 0, 0, 0)
        lt.setSpacing(6)
        ob = button("打开", "ghost", "external", cb=lambda: open_path(log_path()))
        ob.setFixedHeight(32)
        lt.addWidget(ob)
        cb = button("清空", "plain", cb=self.clear_log)
        lt.addWidget(cb)
        rows.append(setting_row("测速 / 运行日志", short_path(log_path(), 54), log_tools))
        rows.append(setting_row("版本", f"{APP_VERSION} (build {BUILD_NO})", label("桌面版", 11, P["text3"])))
        rows.append(setting_row("支持的链接", "release 附件 / archive / raw / 普通直链", label("4 类", 11, P["text3"])))
        self.v.addWidget(setting_card(rows))
        foot = label("光速镜像 · 只做链路选择，不代理、不缓存内容", 10.5, P["text3"])
        foot.setAlignment(Qt.AlignCenter)
        self.v.addWidget(foot)

    def set_history_max(self, v):
        self.cfg()["history_max"] = int(v)
        self.save()
        self.app.history_page.rebuild()

    def clear_log(self):
        p = log_path()
        idx = ask_choice(self, "清空日志",
                         f"{p}\n\n只会清空这个日志文件，配置和已下载的文件都不动。",
                         [("清空日志", "清空后程序仍会继续追加新的测速记录", "danger")])
        if idx != 0:
            return
        try:
            with _LOG_LOCK:
                p.write_text("", "utf-8")
        except Exception:
            pass
        log_line("日志", f"日志已手动清空 · {APP_VERSION}")
        self.app.toast("日志已清空，新的测速记录会继续写入", "ok")

    def on_show(self):
        pass
# --------------------------------------------------------------------------
# 主窗口
# --------------------------------------------------------------------------


class Bridge(QObject):
    """把后台线程的结果安全地抛回 UI 线程。"""

    mirror_result = Signal(object, bool, int, float)
    speed_done = Signal()
    watch_update = Signal(str)


NAV_ITEMS = [
    ("download", "下载台"),
    ("versions", "版本追踪"),
    ("history", "下载记录"),
    ("mirror", "镜像源"),
    ("settings", "设置"),
]


def make_logo(size=38):
    pm = QPixmap(size, size)
    pm.fill(Qt.transparent)
    pr = QPainter(pm)
    pr.setRenderHint(QPainter.Antialiasing)
    g = QLinearGradient(0, 0, size, size)
    g.setColorAt(0, QColor("#2BE3A7"))
    g.setColorAt(1, QColor("#0E8F6B"))
    pr.setPen(Qt.NoPen)
    pr.setBrush(QBrush(g))
    pr.drawRoundedRect(QRectF(0, 0, size, size), size * 0.28, size * 0.28)
    pr.setPen(QColor("#04140F"))
    f = QFont()
    f.setPixelSize(int(size * 0.42))
    f.setBold(True)
    pr.setFont(f)
    pr.drawText(QRectF(0, 0, size, size), Qt.AlignCenter, "LM")
    pr.end()
    return pm


class Sidebar(QWidget):
    def __init__(self, app, parent=None):
        super().__init__(parent)
        self.app = app
        self.setObjectName("sidebar")
        self.setFixedWidth(226)
        lay = QVBoxLayout(self)
        lay.setContentsMargins(16, 20, 16, 16)
        lay.setSpacing(4)

        logo_row = QHBoxLayout()
        logo_row.setSpacing(10)
        ic = QLabel()
        ic.setPixmap(make_logo(38))
        logo_row.addWidget(ic)
        box = QVBoxLayout()
        box.setSpacing(0)
        box.addWidget(label("光速镜像", 15.5, P["text"], bold=True))
        box.addWidget(label("GitHub 下载器 · 桌面版", 9.5, P["text3"]))
        logo_row.addLayout(box)
        lay.addLayout(logo_row)
        lay.addSpacing(18)

        self.buttons = []
        self.group = QButtonGroup(self)
        self.group.setExclusive(True)
        for i, (ic_name, text) in enumerate(NAV_ITEMS):
            b = QPushButton(text)
            b.setCheckable(True)
            b.setIcon(icon(ic_name, P["text2"], 18))
            b.setIconSize(QSize(18, 18))
            push(b, nav=True)
            b.clicked.connect(lambda _=False, idx=i: app.switch_page(idx))
            self.group.addButton(b, i)
            lay.addWidget(b)
            self.buttons.append(b)
        self.buttons[0].setChecked(True)

        lay.addStretch(1)
        self.status_card = QFrame()
        self.status_card.setObjectName("card")
        self.status_card.setStyleSheet(
            f"QFrame#card{{background:{P['surface']};border:1px solid {P['line']};border-radius:12px;}}")
        sl = QVBoxLayout(self.status_card)
        sl.setContentsMargins(12, 10, 12, 10)
        sl.setSpacing(3)
        self.st_count = label("当前没有任务", 11.5, P["text2"])
        self.st_speed = label("空闲", 15, P["primary"], bold=True)
        sl.addWidget(self.st_count)
        sl.addWidget(self.st_speed)
        lay.addWidget(self.status_card)
        lay.addSpacing(8)
        plat = "Windows" if IS_WIN else ("macOS" if IS_MAC else "Linux")
        ver = label(f"v{APP_VERSION} · {plat}", 9.5, P["text3"])
        ver.setAlignment(Qt.AlignCenter)
        lay.addWidget(ver)

    def set_active(self, idx):
        for i, b in enumerate(self.buttons):
            b.setChecked(i == idx)
            name = NAV_ITEMS[i][0]
            b.setIcon(icon(name, P["primary"] if i == idx else P["text2"], 18))

    def update_stats(self, active, speed):
        if active:
            self.st_count.setText(f"{active} 个任务进行中")
            self.st_speed.setText(fmt_speed(speed))
        else:
            self.st_count.setText("当前没有任务")
            self.st_speed.setText("空闲")


class AppWindow(QMainWindow):
    def __init__(self):
        super().__init__()
        self.state = State()
        set_theme(self.state.config.get("theme", "dark"))
        self.setWindowTitle("光速镜像 · GitHub 下载器")
        self.setWindowIcon(QIcon(make_logo(64)))
        self.resize(1280, 840)
        self.setMinimumSize(1080, 720)
        geo = self.state.config.get("window_geometry")
        if geo:
            try:
                from PySide6.QtCore import QByteArray
                self.restoreGeometry(QByteArray.fromBase64(geo.encode("ascii")))
            except Exception:
                pass
        self.dm = DownloadManager(self.state, on_change=self._on_tasks_changed)
        self.bridge = Bridge()
        self.bridge.mirror_result.connect(self._on_mirror_result)
        self.bridge.speed_done.connect(self._on_speed_done)
        self.bridge.watch_update.connect(self._on_watch_update)
        self.speed_runner = None
        # 下载前测速：同一时刻只跑一轮，多个下载请求排队等测速结果
        self._speed_busy = False
        self._speed_pending = []
        self._speed_gen = 0
        self.dialog_formats = list(self.state.global_formats())
        self._status_seen = {}
        self._build()
        self.timer = QTimer(self)
        self.timer.timeout.connect(self._tick)
        self.timer.start(400)

    # ---- 结构 ----
    def _build(self, index=0):
        root = QWidget()
        root.setObjectName("root")
        h = QHBoxLayout(root)
        h.setContentsMargins(0, 0, 0, 0)
        h.setSpacing(0)
        self.sidebar = Sidebar(self)
        h.addWidget(self.sidebar)
        self.stack = QStackedWidget()
        self.home_page = HomePage(self)
        self.watch_page = WatchPage(self)
        self.history_page = HistoryPage(self)
        self.mirrors_page = MirrorsPage(self)
        self.settings_page = SettingsPage(self)
        for p in (self.home_page, self.watch_page, self.history_page,
                  self.mirrors_page, self.settings_page):
            self.stack.addWidget(p)
        h.addWidget(self.stack, 1)
        self.setCentralWidget(root)
        self.toast_widget = Toast(root)
        self.toast_widget.hide()
        self.switch_page(index, force=True)
        self.home_page.url_edit.setFocus()
        # 恢复已有任务卡片
        for t in reversed(self.dm.tasks):
            self.home_page.add_task(t)

    def switch_page(self, idx, force=False):
        if idx == self.stack.currentIndex() and not force:
            return
        self.stack.setCurrentIndex(idx)
        self.sidebar.set_active(idx)
        page = self.stack.currentWidget()
        if hasattr(page, "on_show"):
            page.on_show()
        if idx == 0:
            self.home_page.update_task_count()

    def apply_theme(self, name):
        if name == self.state.config.get("theme"):
            pass
        self.state.config["theme"] = name
        self.state.save()
        set_theme(name)
        QApplication.instance().setStyleSheet(build_qss())
        idx = self.stack.currentIndex() if hasattr(self, "stack") else 0
        old = self.centralWidget()
        self._build(idx)
        if old is not None:
            old.deleteLater()

    # ---- 提示 ----
    def toast(self, text, kind="info"):
        try:
            self.toast_widget.show_message(text, kind)
        except Exception:
            pass

    def update_tip(self):
        c = self.state.config
        try:
            self.home_page.tip.setText(
                f"当前 {c['concurrency']} 文件并发 · 单文件 {c['threads']} 线程分段 · "
                f"失败最多换 {c['failover_max']} 个镜像")
            self.home_page.hdr_chip.setText(
                f"{sum(1 for m in self.state.mirrors if m.get('enabled', True))} 个镜像 · {c['concurrency']} 并发")
        except Exception:
            pass

    # ---- 下载 ----
    def target_for(self, repo, filename, parent=None, interactive=True):
        folder = self.state.project_dir(repo) if repo else Path(self.state.config["download_root"])
        try:
            folder.mkdir(parents=True, exist_ok=True)
        except Exception:
            pass
        path = folder / safe_name(filename)
        if not path.exists():
            return path
        rule = self.state.project_rule(repo) if repo else self.state.config.get("replace_rule", "replace")
        if rule == "ask" and interactive:
            idx = ask_choice(parent or self, "同名文件已存在",
                             f"{path.name}\n{folder}",
                             [("直接替换", "下载完成后覆盖旧文件", ""),
                              ("自动改名", "保留旧文件，新文件加 (1)", ""),
                              ("跳过不下载", "这个文件就不下了", "")])
            if idx < 0:
                return None
            rule = ("replace", "rename", "skip")[idx]
        if rule == "skip":
            return None
        if rule == "rename":
            return unique_path(path)
        return path

    def download_url(self, url, filename, repo="", test_first=True, tag="", parent=None):
        path = self.target_for(repo, filename, parent)
        if path is None:
            self.toast("同名文件已存在，已跳过", "warn")
            return

        def go():
            t = self.dm.create(url, path.name, path, repo=repo)
            t.tag = tag
            self.home_page.add_task(t)
            self.switch_page(0)
            self.dm.pump()
            self.toast(f"已开始下载 {path.name}", "ok")

        if test_first:
            self.switch_page(0)
            self.before_speed(url, go)
        else:
            go()

    def download_assets(self, repo, assets, parent=None, tag="", test_first=True):
        if not assets:
            return
        folder = self.state.project_dir(repo)
        folder.mkdir(parents=True, exist_ok=True)
        rule = self.state.project_rule(repo)
        conflicted = [a for a in assets if (folder / safe_name(a["name"])).exists()]
        batch_choice = None
        if conflicted and rule == "ask":
            idx = ask_choice(parent or self, "有同名文件",
                             f"下载目录里已经有 {len(conflicted)} 个同名文件：\n{folder}",
                             [("全部替换", "下载完成后覆盖旧文件", ""),
                              ("全部自动改名", "保留旧文件，新文件加序号", ""),
                              ("跳过这些", "只下不冲突的", "")])
            if idx < 0:
                return
            batch_choice = idx

        def go():
            made = 0
            for a in assets:
                path = folder / safe_name(a["name"])
                if path.exists():
                    if batch_choice == 1 or (batch_choice is None and rule == "rename"):
                        path = unique_path(path)
                    elif batch_choice == 2 or (batch_choice is None and rule == "skip"):
                        continue
                    elif batch_choice is None and rule == "ask":
                        path = unique_path(path)
                t = self.dm.create(a["url"], path.name, path, repo=repo,
                                   size_hint=a.get("size", 0))
                t.tag = tag
                self.home_page.add_task(t)
                made += 1
            if made == 0:
                self.toast("全部同名，已跳过", "warn")
                return
            self.switch_page(0)
            self.dm.pump()
            self.toast(f"已加入 {made} 个下载任务", "ok")

        if test_first:
            self.switch_page(0)
            self.before_speed(
                assets[0]["url"], go,
                f"先测速，再下载 {len(assets)} 个文件…" if len(assets) > 1
                else "先测速，挑出最快的镜像再开始下载…")
        else:
            go()

    def _on_tasks_changed(self):
        pass

    def refresh_history(self):
        if hasattr(self, "history_page"):
            self.history_page.rebuild()

    # ---- 测速 ----
    def before_speed(self, url, after, hint="先测速，挑出最快的镜像再开始下载…"):
        """所有下载入口统一走这里：先逐个镜像测速，测完再真正开跑。

        测速期间再点下载不会被丢掉，回调排进队列，等这一轮测速结束一起放行。
        """
        self._speed_pending.append(after)
        if self._speed_busy:
            self.toast("正在测速，测完自动开始下载", "")
            return
        self._speed_busy = True
        self.toast(hint)
        self.home_page.mark_testing()
        self.home_page.mark_pending(True)
        self.speed_runner = SpeedTestRunner(
            self.state,
            lambda m, ok, lat, sp: self.bridge.mirror_result.emit(m, ok, lat, sp),
            lambda: self.bridge.speed_done.emit())
        self.speed_runner.start(url)
        # 兜底：镜像全部超时也不至于让下载一直卡着
        self._speed_gen += 1
        gen = self._speed_gen
        n = max(1, sum(1 for m in self.state.mirrors if m.get("enabled", True)))
        # 单个镜像最多占 4.5 秒（probe_mirror 内部有时限），再留 5 秒余量
        QTimer.singleShot(int(n * 4500 + 5000), lambda: self._speed_watchdog(gen))

    def _finish_speed(self):
        """一轮测速结束（含兜底）：恢复按钮，放行排队中的下载。"""
        self._speed_busy = False
        self.home_page.mark_pending(False)
        pending = self._speed_pending
        self._speed_pending = []
        for cb in pending:
            try:
                cb()
            except Exception as e:
                log_line("下载", f"测速后排队的任务启动失败 · {e}")

    def _speed_watchdog(self, gen=0):
        if gen and gen != self._speed_gen:
            return          # 这一轮早结束了，定时器是上一轮的遗留
        if self._speed_busy:
            log_line("测速", "超时兜底：按已有结果继续")
            if self.speed_runner:
                self.speed_runner.cancel()
            self._finish_speed()

    def start_speed_test(self, url):
        if self._speed_busy:
            self.toast("正在测速，稍等一下", "")
            return
        self._speed_busy = True
        self.home_page.mark_testing()
        self.home_page.mark_pending(True)
        self.speed_runner = SpeedTestRunner(
            self.state,
            lambda m, ok, lat, sp: self.bridge.mirror_result.emit(m, ok, lat, sp),
            lambda: self.bridge.speed_done.emit())
        self.speed_runner.start(url)
        self.toast("正在逐个测速…")
        self._speed_gen += 1
        gen = self._speed_gen
        n = max(1, sum(1 for m in self.state.mirrors if m.get("enabled", True)))
        # 单个镜像最多占 4.5 秒（probe_mirror 内部有时限），再留 5 秒余量
        QTimer.singleShot(int(n * 4500 + 5000), lambda: self._speed_watchdog(gen))

    def _on_mirror_result(self, m, ok, lat, sp):
        self.home_page.on_mirror_result(m, ok, lat, sp)

    def _on_speed_done(self):
        self.home_page.rebuild_mirrors()
        rows = self.home_page.mirror_rows
        best = rows[0].m["name"] if rows else "—"
        if self._speed_pending:
            self.toast(f"测速完成 · 最快 {best} · 开始下载", "ok")
        else:
            self.toast(f"测速完成 · 最快 {best}", "ok")
        self._finish_speed()

    # ---- 版本追踪 ----
    def open_watch_repo(self, repo):
        self.switch_page(1)
        self.watch_page.repo_edit.setText(repo)
        self.watch_page.track()

    def check_repo(self, repo, force=False):
        w = self.state.watch_entry(repo)
        if not w or w.get("checking"):
            return
        if not force and w.get("latest") and now_ms() - int(w.get("checked") or 0) < self.state.config.get("cache_ttl", 10) * 60000:
            self.watch_page.refresh_cards()
            return
        w["checking"] = now_ms()   # 记开始时间，卡住时能超时放行
        w["error"] = ""
        self.watch_page.refresh_cards()

        def run():
            try:
                data = api_latest(repo, self.state.config.get("github_token", ""),
                                  self.state.config.get("proxy", ""))
                if not data:
                    raise HttpError("这个仓库还没有发布 Release")
                rows = sort_raw_releases(data)
                if not rows:
                    raise HttpError("这个仓库还没有发布 Release")
                w["latest"] = normalize_release(rows[0])
                w["checked"] = now_ms()
            except HttpError as e:
                w["error"] = str(e)
            except Exception as e:
                w["error"] = f"检查失败：{e}"
            w["checking"] = False
            self.state.save()
            self.bridge.watch_update.emit(repo)

        threading.Thread(target=run, daemon=True).start()

    def _on_watch_update(self, repo):
        self.watch_page.refresh_cards()
        self.watch_page.refresh_check_state()

    # ---- 定时刷新 ----
    def _tick(self):
        self.home_page.tick()
        active, speed = 0, 0.0
        for t in self.dm.tasks:
            if t.status in ("running", "probing"):
                active += 1
                speed += t.speed or 0
            seen = self._status_seen.get(t.id)
            if seen != t.status:
                self._status_seen[t.id] = t.status
                if t.status == "done":
                    if t.repo:
                        w = self.state.watch_entry(t.repo)
                        tag = getattr(t, "tag", "")
                        if w and tag:
                            w["tag"] = tag
                            self.state.save()
                            self.watch_page.refresh_cards()
                    if self.stack.currentIndex() == 2:
                        self.history_page.rebuild()
                if t.status in ("done", "error"):
                    self.history_page.rebuild() if self.stack.currentIndex() == 2 else None
        self.sidebar.update_stats(active, speed)

    # ---- 窗口 ----
    def closeEvent(self, ev):
        try:
            self.state.config["window_geometry"] = bytes(self.saveGeometry().toBase64()).decode("ascii")
            self.state.save()
        except Exception:
            pass
        log_line("退出", f"{APP_NAME} 正常退出")
        super().closeEvent(ev)

    def showEvent(self, ev):
        super().showEvent(ev)
        if IS_WIN:
            try:
                import ctypes
                hwnd = int(self.winId())
                for attr in (20, 19):
                    v = ctypes.c_int(1)
                    ctypes.windll.dwmapi.DwmSetWindowAttribute(hwnd, attr, ctypes.byref(v), ctypes.sizeof(v))
            except Exception:
                pass


def _install_excepthook(app):
    def hook(exc_type, exc, tb):
        text = "".join(traceback.format_exception(exc_type, exc, tb))
        try:
            (app_dir() / "error.log").write_text(text, "utf-8")
        except Exception:
            pass
        log_line("异常", text.strip().splitlines()[-1] if text.strip() else "未知错误")
        try:
            QMessageBox.critical(None, "光速镜像遇到了一个错误",
                                 "程序出现异常，详细信息已写入：\n"
                                 f"{app_dir() / 'error.log'}\n\n{text[-1200:]}")
        except Exception:
            pass

    sys.excepthook = hook


def main():
    app = QApplication(sys.argv)
    app.setApplicationName(APP_NAME)
    app.setOrganizationName("LightMirror")
    f = QFont()
    f.setFamilies(["Microsoft YaHei UI", "PingFang SC", "Noto Sans CJK SC", "Segoe UI"])
    f.setPixelSize(13)
    app.setFont(f)
    st = State()
    set_theme(st.config.get("theme", "dark"))
    app.setStyleSheet(build_qss())
    _install_excepthook(app)
    log_line("启动", f"{APP_NAME} {APP_VERSION} · build {BUILD_NO} · {platform.platform()} · "
                     f"并发 {st.config.get('concurrency')} · 线程 {st.config.get('threads')} · 日志 {log_path()}")
    try:
        win = AppWindow()
        win.show()
    except Exception:
        import traceback as _tb
        text = _tb.format_exc()
        try:
            (app_dir() / "error.log").write_text(text, "utf-8")
        except Exception:
            pass
        raise
    sys.exit(app.exec())


if __name__ == "__main__":
    main()
