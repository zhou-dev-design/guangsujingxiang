/* 光速镜像 · 前端逻辑
   一套界面同时跑 Windows 与 Android：宽屏用左侧导航，窄屏用底部页签。 */

/* 全部代码包在闭包里：Tauri 注入的全局里已经有 ipc 等标识符，
   顶层再声明同名函数会让整个脚本在解析期报错（Identifier already declared）。 */
(function () {

const TAURI = window.__TAURI__;
const INTERNALS = window.__TAURI_INTERNALS__;
/* 打包进应用时 Tauri 一定注入了 __TAURI_INTERNALS__；
   浏览器里直接打开则没有，此时才退回 mock.js 的假数据。 */
const IS_TAURI = !!(INTERNALS || (TAURI && TAURI.core && TAURI.core.invoke));
/* 打包后页面走 tauri:// 或 http://tauri.localhost；浏览器里是 file:/http://127.0.0.1 */
const IN_APP =
  location.protocol === "tauri:" ||
  location.hostname === "tauri.localhost" ||
  location.hostname === "localhost" && location.port === "" ||
  !!INTERNALS;

async function ipc(action, args = {}) {
  if (!IS_TAURI) {
    if (window.__MOCK__) return await window.__MOCK__.call(action, args);
    throw new Error("没有连上原生层");
  }
  const payload = { action, args };
  if (TAURI && TAURI.core && TAURI.core.invoke) {
    return await TAURI.core.invoke("call", payload);
  }
  return await INTERNALS.invoke("call", payload);
}

function onEvent(name, cb) {
  if (TAURI && TAURI.event && TAURI.event.listen) {
    return TAURI.event.listen(name, (e) => cb(e.payload));
  }
  const id = INTERNALS.transformCallback((e) => cb(e.payload));
  return INTERNALS.invoke("plugin:event|listen", {
    event: name,
    target: { kind: "Any" },
    handler: id,
  });
}

const S = {
  boot: null,
  config: {},
  mirrors: [],
  watch: [],
  history: [],
  tasks: [],
  speed: {},           // name -> {ok, latency, speed, testing}
  page: "home",
  sel: new Set(),
  picking: false,
  histLimit: 20,
  parsed: null,
  speedRunning: false,
  platform: "windows",
  version: "3.0.0",
  build: "local",
  rules: [],
  builtin: [],
  defaultFormats: [],
  formatMenu: [],
  versionsState: null,
};

const $ = (sel) => document.querySelector(sel);
const $$ = (sel) => Array.from(document.querySelectorAll(sel));

function esc(s) {
  return String(s == null ? "" : s)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}
function fmtSize(n) {
  n = Number(n) || 0;
  if (n <= 0) return "0 B";
  const u = ["B", "KB", "MB", "GB", "TB"];
  let i = 0;
  let v = n;
  while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
  return i === 0 ? `${n} B` : `${v.toFixed(1)} ${u[i]}`;
}
function fmtSpeed(mb) {
  if (!mb || mb <= 0) return "0.0 MB/s";
  return mb < 1 ? `${Math.round(mb * 1024)} KB/s` : `${mb.toFixed(1)} MB/s`;
}
function fmtDur(sec) {
  if (!isFinite(sec) || sec < 0) return "--:--";
  sec = Math.floor(sec);
  const h = Math.floor(sec / 3600);
  const m = Math.floor((sec % 3600) / 60);
  const s = sec % 60;
  const p = (x) => String(x).padStart(2, "0");
  return h > 0 ? `${p(h)}:${p(m)}:${p(s)}` : `${p(m)}:${p(s)}`;
}
function fmtTs(ms) {
  if (!ms) return "—";
  const d = new Date(Number(ms));
  const p = (x) => String(x).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}
function bjLabel(iso, withYear = true) {
  if (!iso) return "—";
  const d = new Date(iso);
  if (isNaN(d.getTime())) return "—";
  const bj = new Date(d.getTime() + (d.getTimezoneOffset() + 480) * 60000);
  const p = (x) => String(x).padStart(2, "0");
  const md = `${p(bj.getMonth() + 1)}-${p(bj.getDate())} ${p(bj.getHours())}:${p(bj.getMinutes())}`;
  return withYear ? `${bj.getFullYear()}-${md}` : md;
}
function extOf(name) {
  const n = String(name || "").toLowerCase();
  for (const m of [".tar.gz", ".tar.xz", ".tar.bz2", ".tar.zst"]) {
    if (n.endsWith(m)) return m.slice(1);
  }
  const i = n.lastIndexOf(".");
  return i >= 0 ? n.slice(i + 1) : "";
}
function icon(id, size = 16) {
  return `<svg width="${size}" height="${size}"><use href="#${id}"/></svg>`;
}
function toast(text, kind = "", ms = 2400) {
  const box = $("#toasts");
  const d = document.createElement("div");
  d.className = "toast " + kind;
  d.textContent = text;
  box.appendChild(d);
  setTimeout(() => {
    d.style.transition = "opacity .2s, transform .2s";
    d.style.opacity = "0";
    d.style.transform = "translateY(6px)";
    setTimeout(() => d.remove(), 220);
  }, ms);
}
function debounce(fn, ms) {
  let t;
  return (...a) => { clearTimeout(t); t = setTimeout(() => fn(...a), ms); };
}

/** 起不来的时候把原因摆在界面上，而不是只在 toast 里闪一下。 */
function showFatal(e) {
  const box = document.createElement("div");
  box.style.cssText =
    "position:fixed;left:50%;top:18px;transform:translateX(-50%);z-index:200;max-width:88vw;" +
    "background:#2a1416;border:1px solid rgba(255,92,92,.5);color:#ffd7d7;font-size:12px;" +
    "padding:10px 14px;border-radius:12px;line-height:1.6;white-space:pre-wrap";
  box.textContent = "启动失败：" + (e && e.message ? e.message : String(e)) +
    "\n（原生层：" + (IS_TAURI ? "已连接" : "未连接") + "）";
  document.body.appendChild(box);
  setTimeout(() => box.remove(), 15000);
}

/* ---------------- 导航 ---------------- */
const PAGES = [
  { key: "home", label: "下载台", icon: "i-home", title: "镜像下载台" },
  { key: "watch", label: "版本", icon: "i-clock", title: "版本追踪" },
  { key: "history", label: "记录", icon: "i-list", title: "下载记录" },
  { key: "mirrors", label: "镜像", icon: "i-mirror", title: "镜像源" },
  { key: "settings", label: "设置", icon: "i-gear", title: "设置" },
];

function buildNav() {
  const navLabels = { home: "下载台", watch: "版本追踪", history: "下载记录", mirrors: "镜像源", settings: "设置" };
  $("#nav").innerHTML = PAGES.map(
    (p) => `<button class="nav-item" data-page="${p.key}">${icon(p.icon, 17)}<span>${navLabels[p.key]}</span></button>`
  ).join("");
  $("#tabbar").innerHTML = PAGES.map(
    (p) => `<button class="tab" data-page="${p.key}">${icon(p.icon, 21)}<span>${p.label}</span></button>`
  ).join("");
  // 只绑导航按钮和页签；页面容器本身也带 data-page，绑上去会让页面内任何点击都触发切页
  document.querySelectorAll(".nav-item[data-page], .tab[data-page]").forEach((b) => {
    b.addEventListener("click", () => go(b.dataset.page));
  });
}

function go(page, force = false) {
  const changed = force || S.page !== page;
  S.page = page;
  $$(".page").forEach((p) => p.classList.toggle("on", p.dataset.page === page));
  $$(".nav-item").forEach((b) => b.classList.toggle("on", b.dataset.page === page));
  $$(".tab").forEach((b) => b.classList.toggle("on", b.dataset.page === page));
  if (!changed) return;   // 已经在这一页：不重绘、不复位滚动
  if (page === "watch") {
    renderWatch();
    if (!S._watchChecked) {
      S._watchChecked = true;
      if (S.watch.length) ipc("watch.check_all", { force: false }).catch(() => {});
    }
  }
  if (page === "settings") renderSettings();
  if (page === "history") renderHistory();
  if (page === "mirrors") renderMirrors();
  const body = document.querySelector(`.page[data-page="${page}"] .body`);
  if (body) body.scrollTop = 0;
}

/* ---------------- 主题 ---------------- */
function updateLayout() {
  const mob = S.platform === "android" || window.innerWidth < 880;
  document.body.classList.toggle("mob", mob);
}

function applyTheme() {
  let mode = S.config.theme || "dark";
  if (S.platform === "android" && (S.config.theme_mode || "auto") === "auto") {
    mode = window.matchMedia("(prefers-color-scheme: light)").matches ? "light" : "dark";
  }
  document.body.dataset.theme = mode;
}

/* ---------------- 配置 ---------------- */
async function saveConfig(patch) {
  S.config = Object.assign({}, S.config, patch);
  applyTheme();
  try {
    const cfg = await ipc("config.save", { config: S.config });
    S.config = cfg;
    applyTheme();
  } catch (e) {
    toast("保存失败：" + e, "bad");
  }
  // 只把值同步回控件，不重建 DOM —— 否则滚动位置和焦点都会丢
  const scroller = S.page === "settings" ? $("#settingsBody") : null;
  const keepTop = scroller ? scroller.scrollTop : 0;
  applyConfigUI();
  if (scroller) {
    scroller.scrollTop = keepTop;
    // 重排有可能延后一帧，再钉一次
    requestAnimationFrame(() => {
      if (scroller.scrollTop !== keepTop) scroller.scrollTop = keepTop;
    });
  }
}

/** 把 S.config 的变化写回现有控件（不重建节点）。 */
function applyConfigUI() {
  renderHomeChrome();
  if (S.page !== "settings") return;
  const body = $("#settingsBody");
  if (!body) return;
  const num = (txt) => ((String(txt).match(/^\s*\d+\s*(.*)$/) || [])[1] || "").trim();

  body.querySelectorAll("[data-cfg]").forEach((sw) => {
    const on = !!S.config[sw.dataset.cfg];
    if (sw.classList.contains("on") !== on) sw.classList.toggle("on", on);
  });
  body.querySelectorAll("[data-edit]").forEach((v) => {
    const key = v.dataset.edit;
    const suffix = num(v.textContent);
    const want = `${S.config[key]}${suffix ? " " + suffix : ""}`;
    if (v.textContent !== want) v.textContent = want;   // 没变就别碰 DOM
  });
  body.querySelectorAll("[data-sel]").forEach((sel) => {
    const key = sel.dataset.sel;
    const want = String(S.config[key]);
    if (sel.value !== want) sel.value = want;
  });
  body.querySelectorAll("[data-theme]").forEach((c) => {
    c.classList.toggle("on", (S.config.theme || "dark") === c.dataset.theme);
  });
  body.querySelectorAll("[data-theme-mode]").forEach((c) => {
    c.classList.toggle("on", (S.config.theme_mode || "auto") === c.dataset.themeMode);
  });
  body.querySelectorAll("[data-fmt]").forEach((c) => {
    c.classList.toggle("on", (S.config.formats || []).includes(c.dataset.fmt));
  });
  const seg = body.querySelector("#segMode");
  if (seg) {
    const i = Number(S.config.version_mode) === 1 ? 1 : 0;
    seg.classList.toggle("idx1", i === 1);
    seg.querySelectorAll("span[data-mode]").forEach((sp) =>
      sp.classList.toggle("on", Number(sp.dataset.mode) === i)
    );
  }
  const root = body.querySelector("#rowRoot");
  if (root && root.textContent !== S.config.download_root) {
    root.textContent = S.config.download_root;
  }
  if (root) root.title = S.config.download_root;
  const tk = body.querySelector("#rowToken");
  if (tk) {
    const want = S.config.github_token ? "已填写" : "未填写";
    if (tk.textContent !== want) tk.textContent = want;
  }
  const px = body.querySelector("#rowProxy");
  if (px) {
    const want = S.config.proxy || "未设置";
    if (px.textContent !== want) px.textContent = want;
    px.title = S.config.proxy || "";
  }
}

/* ---------------- 首页 ---------------- */
function renderHomeChrome() {
  const c = S.config;
  const on = S.mirrors.filter((m) => m.enabled).length;
  $("#homeStat").textContent = `${on} 个镜像 · ${c.concurrency} 并发`;
  $("#cfgHint").textContent = S.platform === "android"
    ? `当前 ${c.concurrency} 个文件并发 · 单文件 ${c.segments ? c.threads : 1} 段分段 · 失败最多换 ${c.failover ? c.failover_max : 0} 个镜像`
    : `当前 ${c.concurrency} 文件并发 · 单文件 ${c.segments ? c.threads : 1} 段分段 · 失败最多换 ${c.failover ? c.failover_max : 0} 个镜像`;
  $("#homeSub").textContent = S.platform === "android" ? "单任务下载 · 自动择优链路" : "多任务并发 · 自动择优链路";
  $("#mirrorHint").textContent = S.speedRunning ? "正在逐个探测…" : `${on} 个候选 · 逐个探测`;
  $("#btnRetest").disabled = !$("#urlInput").value.trim();
}

async function updateParsed() {
  const text = $("#urlInput").value.trim();
  const box = $("#parsed");
  if (!text) {
    S.parsed = null;
    box.innerHTML = `<span class="chip static">等待链接</span><span>粘贴后自动解析</span>`;
    $("#btnRetest").disabled = true;
    return;
  }
  try {
    const p = await ipc("link.parse", { text });
    S.parsed = p;
    if (!p) {
      box.innerHTML = `<span class="chip static">认不出</span><span>支持 owner/repo、release 附件、archive、raw 和普通直链</span>`;
      return;
    }
    const name = p.filename || p.slug || "链接";
    box.innerHTML = `<span class="chip accent">${esc(p.label)}</span>
      <span class="fname" title="${esc(name)}">${esc(name)}</span>`;
    $("#btnRetest").disabled = false;
  } catch (e) {
    box.innerHTML = `<span class="chip static">解析失败</span><span>${esc(e)}</span>`;
  }
}

function mirrorScore(m) {
  const sp = Math.min(1, (m.speed || 0) / 10);
  const lat = m.latency || 0;
  const ls = lat ? Math.max(0, 1 - lat / 2000) : 0;
  let s = 0.7 * sp + 0.3 * ls;
  if ((m.fail || 0) >= 3 && !m.speed) s -= 0.2;
  return s;
}

function renderMirrorMini() {
  const enabled = S.mirrors.filter((m) => m.enabled);
  const normal = enabled.filter((m) => m.base).sort((a, b) => mirrorScore(b) - mirrorScore(a));
  const direct = enabled.filter((m) => !m.base);
  const list = normal.concat(direct);
  const box = $("#mirrorList");
  if (!list.length) {
    box.innerHTML = `<div class="empty" style="padding:24px 10px"><p>还没有启用的镜像，去「镜像源」里打开几个</p></div>`;
    return;
  }
  const modeText = (m) => (m.mode === "path" ? "拼 GitHub 路径" : m.mode === "direct" || !m.base ? "直连" : "拼完整链接");
  box.innerHTML = list.map((m, i) => {
    const sp = S.speed[m.name];
    const testing = sp && sp.testing;
    const ok = sp && sp.ok;
    const win = i === 0 && (m.speed > 0 || testing);
    const right = testing
      ? `<div class="scan"><i></i><i></i><i></i></div>`
      : ok || m.speed > 0
        ? `<div class="mspd">${fmtSpeed(m.speed)}</div><div class="msub">${m.latency ? m.latency + " ms" : "—"}</div>`
        : (sp && !sp.ok)
          ? `<div class="msub" style="color:var(--danger)">失败</div>`
          : `<div class="msub">待测</div>`;
    return `<div class="mrow ${win ? "win" : ""}">
      <div class="mrank">${i + 1}</div>
      <div class="mmain">
        <div class="mname">${esc(m.name)}</div>
        <div class="mmeta">${esc(m.base || "github.com")} · ${modeText(m)}</div>
      </div>
      <div class="mright">${right}</div>
    </div>`;
  }).join("");
}

const STATUS_TEXT = {
  queued: "排队中",
  probing: "探测链路…",
  running: "下载中",
  paused: "已暂停",
  done: "完成",
  error: "失败",
  canceled: "已取消",
  skipped: "已跳过",
};

function taskCard(t) {
  const pct = t.total ? Math.min(100, (t.done / t.total) * 100) : 0;
  const done = t.status === "done";
  const err = t.status === "error";
  const active = ["running", "probing", "queued"].includes(t.status);
  const paused = t.status === "paused";
  const keptPct = t.total ? Math.min(99, Math.round((t.done / t.total) * 100)) : 0;
  const sub = done
    ? `完成 · via ${esc(t.mirror || "—")} · 用时 ${fmtDur(t.elapsed)}`
    : err
      ? esc((t.error || "下载中断") + (keptPct > 0 ? ` · 已保留 ${keptPct}%，点「继续」接着下` : ""))
      : paused
        ? `已暂停 · via ${esc(t.mirror || "—")}`
        : `via ${esc(t.mirror || "探测中")} · ${t.segments || 1} 线程`;
  const pctText = t.total ? `${pct.toFixed(0)}<small>%</small>` : "··";
  return `<div class="task ${done ? "done" : ""} ${err ? "err" : ""}">
    <div class="task-head">
      <div class="tico">${icon(done ? "i-check" : "i-download", 17)}</div>
      <div class="task-main">
        <div class="task-name">${esc(t.filename)}</div>
        <div class="task-sub" title="${esc(sub)}">${sub}</div>
      </div>
      <div class="pct">${pctText}</div>
    </div>
    <div class="bar"><i style="width:${pct}%"></i></div>
    <div class="stats">
      <span>${err ? "已中断" : fmtSpeed(t.speed)}</span>
      <span>已下载 <b>${fmtSize(t.done)}</b>${t.total ? " / " + fmtSize(t.total) : ""}</span>
      <span>${done ? "已保存" : t.eta >= 0 ? "剩余 " + fmtDur(t.eta) : "剩余 --:--"}</span>
    </div>
    <div class="task-actions">
      ${active ? `<button class="btn sm" data-task="pause" data-id="${t.id}">${icon("i-pause", 13)}暂停</button>` : ""}
      ${paused || err ? `<button class="btn sm" data-task="resume" data-id="${t.id}">${icon("i-play", 13)}继续</button>` : ""}
      ${active || paused ? `<button class="btn sm plain danger" data-task="cancel" data-id="${t.id}">取消</button>` : ""}
      ${done ? `<button class="btn sm" data-task="open" data-path="${esc(t.path)}">${icon("i-open", 13)}打开文件</button>` : ""}
      ${done ? `<button class="btn sm plain" data-task="reveal" data-path="${esc(t.path)}">所在文件夹</button>` : ""}
    </div>
    ${done ? `<div class="task-path">${esc(t.path)}</div>` : ""}
  </div>`;
}

function renderTasks() {
  const list = S.tasks;
  const box = $("#taskList");
  $("#taskCount").textContent = `${list.length} 个`;
  if (!list.length) {
    box.innerHTML = `<div class="card"><div class="empty">
      <div class="ring">${icon("i-download", 26)}</div>
      <h4>还没有任务</h4><p>贴上 GitHub 链接，选一条最快的链路</p>
    </div></div>`;
  } else {
    box.innerHTML = list.map(taskCard).join("");
  }
  const running = list.filter((t) => ["running", "probing"].includes(t.status));
  const sideK = $("#sideK");
  const sideV = $("#sideV");
  if (running.length) {
    const speed = running.reduce((a, t) => a + (t.speed || 0), 0);
    sideK.textContent = `${running.length} 个任务进行中`;
    sideV.textContent = fmtSpeed(speed);
    sideV.classList.add("busy");
  } else {
    sideK.textContent = list.length ? "当前没有任务" : "当前没有任务";
    sideV.textContent = "空闲";
    sideV.classList.remove("busy");
  }
}

async function startFlow(onlyTest) {
  const text = $("#urlInput").value.trim();
  if (!text) return toast("先贴一个链接");
  let p = S.parsed;
  try {
    p = await ipc("link.parse", { text });
  } catch (e) {
    return toast("解析失败：" + e, "bad");
  }
  if (!p) return toast("认不出这个链接", "bad");
  S.parsed = p;
  if (p.kind === "repo") {
    go("watch");
    $("#repoInput").value = p.slug || text;
    await trackRepo(p.slug || text);
    return;
  }
  if (onlyTest) {
    await withBusy(() => runSpeedTest(p.url || text));
    return;
  }
  // 先测速，测完再按最新排名下载
  await withBusy(() => runSpeedTest(p.url || text));
  const name = p.filename || (p.url || text).split("?")[0].split("/").pop() || "download.bin";
  try {
    const r = await ipc("download.start", {
      items: [{ url: p.url || text, filename: name, repo: p.slug || "", tag: p.tag || "" }],
    });
    if (r.skipped && r.skipped.length) toast("目录里已有同名文件，按规则跳过");
  } catch (e) {
    toast("启动失败：" + e, "bad");
  }
}

async function runSpeedTest(url) {
  if (!url) return null;
  S.speedRunning = true;
  S.speed = {};
  S.speedDone = 0;
  S.speedTotal = S.mirrors.filter((m) => m.enabled).length;
  S.mirrors.filter((m) => m.enabled).forEach((m) => { S.speed[m.name] = { testing: true }; });
  renderMirrorMini();
  renderHomeChrome();
  let res = null;
  try {
    // 后端会真的逐个测完再返回，不是发个任务就完事
    res = await ipc("speed.start", { url });
  } catch (e) {
    toast("测速失败：" + e, "bad");
  }
  S.speedRunning = false;
  renderMirrorMini();
  renderHomeChrome();
  if (res) {
    if (res.ok > 0) {
      const sp = Number(res.bestSpeed || 0).toFixed(2);
      toast(`测速完成 · ${res.ok}/${res.total} 可用 · 最快 ${res.best} ${sp} MB/s`, "ok");
    } else {
      toast("所有镜像都没测通，仍会按顺序尝试", "bad");
    }
  }
  return res;
}

/** 测速期间把按钮锁住，避免用户以为没反应又点一次。 */
async function withBusy(fn) {
  const a = $("#btnStart");
  const b = $("#btnTestOnly");
  const ta = a ? a.textContent : "";
  const tb = b ? b.textContent : "";
  if (a) { a.disabled = true; a.textContent = "正在测速…"; }
  if (b) { b.disabled = true; b.textContent = "正在测速…"; }
  try {
    return await fn();
  } finally {
    if (a) { a.disabled = false; a.textContent = ta; }
    if (b) { b.disabled = false; b.textContent = tb; }
  }
}

/* ---------------- 版本追踪 ---------------- */
function projectFormats(w) {
  const g = S.config.formats || [];
  const own = (w.formats || []).filter((f) => g.includes(f));
  return own.length ? own : g;
}

function latestAssets(w) {
  return (w.latest && w.latest.assets) || [];
}

function tagMatches(a, tags) {
  if (!tags || !tags.length) return true;
  return tags.includes(a.variant || "");
}

function scoreAsset(a) {
  const n = a.name.toLowerCase();
  const e = extOf(n);
  const order = ["exe", "msi", "msix", "zip", "7z", "rar", "apk"];
  let s = order.includes(e) ? (10 - order.indexOf(e)) * 10 : 0;
  if (["x64", "x86_64", "amd64", "win64", "64bit"].some((k) => n.includes(k))) s += 30;
  if (n.includes("arm64") || n.includes("aarch64")) s -= 20;
  if (/(^|[^0-9])x86([^0-9]|$)|win32|32bit/.test(n)) s -= 10;
  if (["setup", "installer", "install"].some((k) => n.includes(k))) s += 8;
  if (n.includes("msvc")) s += 6;
  if (n.includes("gnu")) s += 2;
  if (n.includes("portable") || n.includes("noinstall")) s += 4;
  if (n.includes("debug") || n.includes("symbols")) s -= 50;
  return s;
}

function isNoiseName(name) {
  const n = name.toLowerCase();
  const bad = ["sig", "asc", "pdb", "sha1", "sha256", "sha512", "md5", "sha256sum"];
  if (bad.includes(extOf(n))) return true;
  return ["symbols", "symbol", "checksums", "checksum", "shasums", "md5sums"].some((k) => n.includes(k));
}

function pickAsset(assets, formats, tags) {
  const pool = assets.filter((a) => {
    const e = extOf(a.name);
    return (formats.length === 0 || formats.includes(e)) && !isNoiseName(a.name) && tagMatches(a, tags);
  });
  if (!pool.length) return null;
  return pool.sort((a, b) => scoreAsset(b) - scoreAsset(a))[0];
}

function watchStatus(w) {
  const latest = w.latest;
  if (!latest) return { cls: "muted", text: "还没检查", kind: "none" };
  if (!w.tag) return { cls: "warn", text: `发现 ${latest.tag} · 可以下载`, kind: "new" };
  if (w.tag === latest.tag) return { cls: "ok", text: "已是最新", kind: "ok" };
  return { cls: "warn", text: `有新版本 ${w.tag} → ${latest.tag}`, kind: "update" };
}

function sortedWatch() {
  const list = S.watch.slice();
  const sort = S.config.watch_sort || "updated";
  const note = (w) => (w.note || w.repo.split("/")[1] || w.repo).toLowerCase();
  if (sort === "note") {
    list.sort((a, b) => note(a).localeCompare(note(b), "zh-CN"));
  } else if (sort === "name") {
    list.sort((a, b) => (a.repo.split("/")[1] || "").localeCompare(b.repo.split("/")[1] || "", "zh-CN"));
  } else {
    list.sort((a, b) => ((b.latest && b.latest.newest_ms) || 0) - ((a.latest && a.latest.newest_ms) || 0));
  }
  return list;
}

function renderWatch() {
  const list = sortedWatch();
  $("#watchCount").textContent = `${list.length} 个项目`;
  $$("[data-sort]").forEach((b) => b.classList.toggle("on", b.dataset.sort === (S.config.watch_sort || "updated")));
  const fmts = (S.config.formats || []).map((f) => f.toUpperCase()).join(" / ");
  $("#fmtHint").innerHTML = `<span>默认格式</span>
    <span class="chip accent">${esc(fmts)}</span>
    <span style="margin-left:auto">当前：${esc(fmts)}</span>
    <button class="btn sm plain" id="btnGotoFmt">去设置调整</button>`;
  const g = $("#btnGotoFmt");
  if (g) g.onclick = () => go("settings");

  const box = $("#watchList");
  if (!list.length) {
    box.innerHTML = `<div class="card"><div class="empty">
      <div class="ring">${icon("i-clock", 26)}</div>
      <h4>还没有追踪的项目</h4><p>填 owner/repo，或直接贴仓库地址</p>
    </div></div>`;
    return;
  }
  box.innerHTML = list.map(watchCard).join("");
  bindWatchEvents();
}

function watchCard(w) {
  const st = watchStatus(w);
  const latest = w.latest;
  const fmts = projectFormats(w).map((f) => f.toUpperCase());
  const assets = latestAssets(w).filter((a) => projectFormats(w).includes(extOf(a.name)) && !isNoiseName(a.name));
  const tags = w.variants || [];
  const labelSet = [];
  assets.forEach((a) => { if (a.variant && !labelSet.includes(a.variant)) labelSet.push(a.variant); });
  const title = w.note || w.repo;
  const meta = latest
    ? `最新 ${esc(latest.tag)} · ${bjLabel(latest.newest)}（北京时间）· ${assets.length} 个文件（${esc(fmts.join(" / ") || "全部")}）`
    : "还没有拿到 release 信息";
  const dir = w.dir || `${S.config.download_root}/${w.repo.replace("/", "__")}`;
  const rule = w.rule || S.config.replace_rule || "replace";
  const ruleLabel = (S.rules.find((r) => r[0] === rule) || ["", "直接替换"])[1];
  const shownTags = tags.length ? tags : labelSet;
  const chips = shownTags.slice(0, 6).map((t) =>
    `<span class="chip ${tags.includes(t) ? "on" : ""}" data-wtag="${esc(t)}" data-repo="${esc(w.repo)}">${esc(t)}</span>`
  ).join("");
  return `<div class="wcard" data-repo="${esc(w.repo)}">
    <div class="top">
      <div class="wtitle" title="${esc(w.repo)}">${esc(title)}</div>
      <span class="pill ${st.cls}">${esc(st.text)}</span>
      <button class="btn sm plain" data-wact="note">备注</button>
      <button class="btn sm plain danger" data-wact="remove">移除</button>
    </div>
    ${w.note ? `<div class="wrepo">${esc(w.repo)}</div>` : ""}
    <div class="wstate">${latest ? `<b>${esc(latest.tag)}</b> · ${esc(latest.name || "")}` : "等待检查"}</div>
    <div class="wmeta">${meta}</div>
    <div class="wdir">
      ${icon("i-folder", 13)}
      <span class="p" title="${esc(dir)}">${esc(dir)}</span>
      <span class="rule">同名：${esc(ruleLabel)}</span>
      <button class="btn sm plain" data-wact="dir">更改</button>
    </div>
    <div class="wtags">
      <span class="chip static">追踪格式 ${esc(fmts.join("·") || "全部")}</span>
      ${chips}
      ${labelSet.length > 6 ? `<button class="chip" data-wact="tags">全部标签 ${labelSet.length}</button>` : ""}
    </div>
    <div class="wactions">
      <button class="btn sm" data-wact="versions">${icon("i-tag", 13)}查看版本</button>
      <button class="btn sm primary" data-wact="latest">${icon("i-download", 13)}下载最新</button>
      <button class="btn sm plain" data-wact="open">${icon("i-folder", 13)}打开目录</button>
    </div>
  </div>`;
}

function bindWatchEvents() {
  $$(".wcard").forEach((card) => {
    const repo = card.dataset.repo;
    const w = S.watch.find((x) => x.repo === repo);
    if (!w) return;
    card.querySelectorAll("[data-wact]").forEach((btn) => {
      btn.onclick = (e) => {
        e.stopPropagation();
        const act = btn.dataset.wact;
        if (act === "note") return askNote(w);
        if (act === "remove") return removeWatch(w);
        if (act === "dir") return changeDir(w);
        if (act === "versions") return openVersions(w);
        if (act === "latest") return downloadLatest(w);
        if (act === "open") return ipc("open.path", { path: w.dir || `${S.config.download_root}/${repo.replace("/", "__")}` }).catch((e) => toast(String(e), "bad"));
        if (act === "tags") return openTagPicker(w);
      };
    });
    card.querySelectorAll("[data-wtag]").forEach((chip) => {
      chip.onclick = async () => {
        const tag = chip.dataset.wtag;
        let tags = (w.variants || []).slice();
        tags = tags.includes(tag) ? tags.filter((x) => x !== tag) : tags.concat([tag]);
        await ipc("watch.update", { repo, patch: { variants: tags } });
        w.variants = tags;
        renderWatch();
      };
    });
  });
}

async function trackRepo(text) {
  let repo = text;
  try {
    const p = await ipc("link.parse", { text });
    if (p && p.slug) repo = p.slug;
  } catch (e) {}
  if (!repo || !repo.includes("/")) return toast("填 owner/repo 或仓库地址", "bad");
  try {
    const w = await ipc("watch.add", { repo });
    const i = S.watch.findIndex((x) => x.repo === repo);
    if (i >= 0) S.watch[i] = w; else S.watch.unshift(w);
    $("#repoInput").value = "";
    renderWatch();
    toast("已追踪 " + repo, "ok");
  } catch (e) {
    toast(String(e), "bad");
  }
}

async function askNote(w) {
  const v = await promptDialog("项目备注", "留空就显示 owner/repo，例如「主力笔记 · Obsidian」", w.note || "");
  if (v === null) return;
  await ipc("watch.update", { repo: w.repo, patch: { note: v.trim() } });
  w.note = v.trim();
  renderWatch();
}

async function changeDir(w) {
  const cur = w.dir || `${S.config.download_root}/${w.repo.replace("/", "__")}`;
  const choice = await choiceDialog("这个项目下载到哪", cur, [
    { key: "pick", title: "设置下载目录", sub: "弹框里可以选文件夹，也可以直接粘贴路径" },
    { key: "rule", title: "改同名文件规则", sub: "直接替换 / 自动改名 / 跳过不下载 / 每次询问" },
  ]);
  if (choice === "pick" || choice === "manual") {
    const p = await pathDialog("下载目录", "这个项目单独下到这里", cur);
    if (p) {
      await ipc("watch.update", { repo: w.repo, patch: { dir: p } });
      w.dir = p;
    }
  } else if (choice === "rule") {
    const opts = S.rules.map(([k, l], i) => ({ key: k, title: l, sub: i === 0 ? "下完再原子替换旧文件" : "" }));
    const r = await choiceDialog("同名文件规则", "只对这个项目生效", opts);
    if (r) {
      await ipc("watch.update", { repo: w.repo, patch: { rule: r } });
      w.rule = r;
    }
  }
  renderWatch();
}

function openTagPicker(w) {
  const available = [];
  latestAssets(w).forEach((a) => {
    if (a.variant && !available.includes(a.variant) && !isNoiseName(a.name)) available.push(a.variant);
  });
  const tags = (w.variants || []).slice();
  const body = `<div class="fmt-chips">${available.map((t) =>
    `<span class="chip ${tags.includes(t) ? "on" : ""}" data-pick="${esc(t)}">${esc(t)}</span>`).join("")}</div>
    <p style="font-size:11px;color:var(--text3);margin-top:10px;line-height:1.6">点一下选中、再点取消。选中几个就只看这几个文件；全部取消回到「这个版本的全部文件」。</p>`;
  const dlg = openDialog({ title: "标签筛选", sub: w.repo, body, footer: [{ text: "完成", cls: "primary" }] });
  dlg.querySelectorAll("[data-pick]").forEach((chip) => {
    chip.onclick = async () => {
      const t = chip.dataset.pick;
      const next = tags.includes(t) ? tags.filter((x) => x !== t) : tags.concat([t]);
      tags.length = 0; tags.push(...next);
      chip.classList.toggle("on");
      await ipc("watch.update", { repo: w.repo, patch: { variants: next } });
      w.variants = next;
    };
  });
  dlg.querySelector(".dfoot .btn").onclick = () => {
    closeDialog();
    renderWatch();
  };
}

async function removeWatch(w) {
  const ok = await confirmDialog("移除追踪", `不再跟踪 ${w.repo}？已下载的文件不会被删除。`, "移除", true);
  if (!ok) return;
  await ipc("watch.remove", { repo: w.repo });
  S.watch = S.watch.filter((x) => x.repo !== w.repo);
  renderWatch();
}

async function downloadLatest(w) {
  const assets = latestAssets(w);
  const a = pickAsset(assets, projectFormats(w), w.variants || []);
  if (!a) return toast("这个版本里没有符合格式的文件", "bad");
  await startAssetDownload(w, a, w.latest.tag);
}

async function startAssetDownload(w, asset, tag) {
  const rule = w.rule || S.config.replace_rule;
  if (rule === "ask") {
    const ok = await confirmDialog("同名文件", `如果目录里已有 ${asset.name}，要替换它吗？`, "替换", false, "取消");
    if (!ok) return;
  }
  try {
    const r = await ipc("download.start", {
      items: [{ url: asset.url, filename: asset.name, repo: w.repo, tag: tag, size: asset.size }],
    });
    if (r && r.skipped && r.skipped.length) {
      toast("目录里已有同名文件，按规则跳过");
    } else {
      await ipc("watch.update", { repo: w.repo, patch: { tag: tag, last_vkey: asset.vkey || "" } });
      w.tag = tag;
      toast("已开始下载 · " + asset.name, "ok");
    }
    go("home");
  } catch (e) {
    toast(String(e), "bad");
  }
}

/* ---------------- 版本弹层 ---------------- */
async function openVersions(w) {
  const st = { repo: w.repo, page: 1, perPage: 20, releases: [], loading: true, sel: new Set(), onlyTags: true, allArch: false, w };
  S.versionsState = st;
  const dlg = openDialog({
    title: w.repo,
    sub: "只列这个项目的追踪格式 · 时间为北京时间",
    wide: true,
    body: `<div id="verBody"><div class="empty"><p>正在读取版本…</p></div></div>`,
    footer: [
      { text: "关闭", cls: "ghost", onClick: () => closeDialog() },
      { text: "下载选中 0 个", cls: "primary", id: "verOk" },
    ],
  });
  const okBtn = dlg.querySelector("#verOk");
  okBtn.onclick = async () => {
    const picked = [];
    st.releases.forEach((rel) => rel.assets.forEach((a) => {
      if (st.sel.has(a.url)) picked.push(Object.assign({ _tag: rel.tag }, a));
    }));
    if (!picked.length) return;
    for (const a of picked) {
      await ipc("download.start", {
        items: [{ url: a.url, filename: a.name, repo: w.repo, tag: a._tag, size: a.size }],
      });
    }
    if (picked.length) {
      await ipc("watch.update", { repo: w.repo, patch: { tag: picked[0]._tag } });
      w.tag = picked[0]._tag;
    }
    toast(`已加入 ${picked.length} 个文件`, "ok");
    closeDialog();
    go("home");
  };
  await loadVersions(st, 1, true);
  renderVersions(st);
}

async function loadVersions(st, page, replace) {
  st.loading = true;
  try {
    const list = await ipc("releases.list", { repo: st.repo, page, per_page: st.perPage });
    if (replace) st.releases = list; else st.releases = st.releases.concat(list);
    st.page = page;
    st.hasMore = list.length >= st.perPage;
  } catch (e) {
    st.error = String(e);
  }
  st.loading = false;
  renderVersions(st);
}

function versionAssets(st, rel) {
  const w = st.w;
  const fmts = projectFormats(w);
  let list = rel.assets.filter((a) => fmts.includes(extOf(a.name)) && !isNoiseName(a.name));
  if (st.onlyTags && (w.variants || []).length) {
    list = list.filter((a) => (w.variants || []).includes(a.variant));
  }
  if (!st.allArch && S.platform !== "android") {
    const x = list.filter((a) => !/arm64|aarch64|armv[5-8]|win32|32bit|(?<![0-9a-z])x86(?![0-9a-z])/i.test(a.name));
    if (x.length) list = x;
  }
  return list;
}

function renderVersions(st) {
  const root = $("#verBody");
  if (!root) return;
  const w = st.w;
  if (st.error) {
    root.innerHTML = `<div class="empty"><h4>读不到版本</h4><p>${esc(st.error)}</p></div>`;
    return;
  }
  const fmts = projectFormats(w);
  const tags = w.variants || [];
  const filterBar = `<div class="vfilter">
    <span style="font-size:11px;color:var(--text3)">本项目格式</span>
    ${S.builtin.filter(([k]) => fmts.includes(k)).map(([k, l]) => `<span class="chip static ${fmts.includes(k) ? "on" : ""}">${l}</span>`).join("")}
    <div style="flex:1"></div>
    <button class="chip ${st.onlyTags ? "on" : ""}" id="verOnlyTags">只看选定文件</button>
    <button class="chip ${st.allArch ? "on" : ""}" id="verAllArch">全部架构</button>
  </div>
  <div style="font-size:11px;color:var(--text3);padding:8px 0 2px">
    ${tags.length ? "已选标签：" + tags.map(esc).join(" / ") : "未选标签，显示全部文件"}
  </div>`;
  $(".dialog.wide .dbody").scrollTop;
  root.innerHTML = filterBar + (st.releases.length
    ? st.releases.map((rel) => {
        const assets = versionAssets(st, rel);
        if (!assets.length) return "";
        const labels = [];
        assets.forEach((a) => { if (a.variant && !labels.includes(a.variant)) labels.push(a.variant); });
        return `<div class="vrow">
          <div class="vhead"><b>${esc(rel.tag)}</b><span class="vname">${esc(rel.name || "")}</span>
            <span class="vdate">${bjLabel(rel.newest)}</span></div>
          <div class="vtags"><span class="chip static" style="background:transparent;border-color:transparent;color:var(--text3)">标签</span>
            ${labels.map((l) => `<span class="chip ${tags.includes(l) ? "on" : ""}" data-vtag="${esc(l)}">${esc(l)}</span>`).join("")}
          </div>
          ${assets.map((a) => {
            const on = st.sel.has(a.url);
            return `<div class="arow ${on ? "sel" : ""}" data-url="${esc(a.url)}">
              ${S.config.version_mode === 1 ? `<span class="chk ${on ? "on" : ""}">✓</span>` : ""}
              <span class="aname">${esc(a.name)}</span>
              <span class="ameta">${fmtSize(a.size)} · ${bjLabel(a.updated, false)}</span>
              <button class="abtn" data-dl="${esc(a.url)}">下载</button>
            </div>`;
          }).join("")}
        </div>`;
      }).join("")
    : `<div class="empty"><p>${st.loading ? "正在读取版本…" : "这个仓库还没有 Release"}</p></div>`);
  if (st.hasMore && !st.loading) {
    root.insertAdjacentHTML("beforeend", `<div class="loadmore"><button class="btn sm" id="verMore">加载更多版本</button></div>`);
    $("#verMore").onclick = () => loadVersions(st, st.page + 1, false);
  }
  // 绑定
  const dlg = $("#modalRoot");
  root.querySelectorAll("[data-vtag]").forEach((chip) => {
    chip.onclick = async () => {
      const t = chip.dataset.vtag;
      const next = (w.variants || []).includes(t) ? w.variants.filter((x) => x !== t) : (w.variants || []).concat([t]);
      await ipc("watch.update", { repo: w.repo, patch: { variants: next } });
      w.variants = next;
      renderVersions(st);
      renderWatch();
    };
  });
  const only = $("#verOnlyTags");
  if (only) only.onclick = () => { st.onlyTags = !st.onlyTags; renderVersions(st); };
  const arch = $("#verAllArch");
  if (arch) arch.onclick = () => { st.allArch = !st.allArch; renderVersions(st); };
  root.querySelectorAll(".arow").forEach((row) => {
    const url = row.dataset.url;
    const rel = st.releases.find((r) => r.assets.some((a) => a.url === url));
    const asset = rel && rel.assets.find((a) => a.url === url);
    if (!asset) return;
    if (S.config.version_mode === 1) {
      row.onclick = (e) => {
        if (e.target.dataset.dl) return;
        if (st.sel.has(url)) st.sel.delete(url); else st.sel.add(url);
        renderVersions(st);
        updateVerFooter(st);
      };
    } else {
      row.onclick = (e) => {
        if (e.target.dataset.dl) return;
        startAssetDownload(w, asset, rel.tag);
        closeDialog();
      };
    }
    const btn = row.querySelector("[data-dl]");
    btn.onclick = async (e) => {
      e.stopPropagation();
      await startAssetDownload(w, asset, rel.tag);
      closeDialog();
    };
  });
  updateVerFooter(st);
}

function updateVerFooter(st) {
  const btn = document.querySelector("#verOk");
  if (!btn) return;
  if (S.config.version_mode === 1) {
    btn.classList.remove("hide");
    btn.textContent = `下载选中 ${st.sel.size} 个`;
    btn.disabled = st.sel.size === 0;
  } else {
    btn.classList.add("hide");
  }
}

/* ---------------- 下载记录 ---------------- */
function histDay(ts) {
  const d = new Date(ts);
  const now = new Date();
  const same = (a, b) => a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate();
  const y = new Date(now.getTime() - 86400000);
  if (same(d, now)) return "今天";
  if (same(d, y)) return "昨天";
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}

function renderHistory() {
  const list = S.history.slice(0, S.histLimit);
  $("#histSub").textContent = `列表最多显示 ${S.histLimit} 条 · 共 ${S.history.length} 条`;
  $("#btnPickHist").textContent = S.picking ? "取消选择" : "选择";
  $("#selBar").classList.toggle("on", S.picking);
  const box = $("#histList");
  if (!list.length) {
    box.innerHTML = `<div class="card"><div class="empty">
      <div class="ring">${icon("i-list", 26)}</div><h4>还没有下载记录</h4><p>下完的文件会出现在这里，只存本机</p>
    </div></div>`;
    return;
  }
  let html = "";
  let day = "";
  for (const h of list) {
    const d = histDay(h.ts);
    if (d !== day) { day = d; html += `<div class="day">${esc(d)}</div>`; }
    const ok = h.status === "done";
    const picked = S.sel.has(h.id);
    html += `<div class="hrow ${picked ? "picked" : ""}" data-id="${esc(h.id)}">
      ${S.picking ? `<span class="hpick ${picked ? "on" : ""}">✓</span>` : ""}
      <div class="hico ${ok ? "ok" : "bad"}">${icon(ok ? "i-download" : "i-x", 16)}</div>
      <div class="hmain">
        <div class="hname">${esc(h.name)}</div>
        <div class="hsub">${esc(fmtSize(h.size))} · ${esc(h.mirror || "—")}${ok ? "" : " · " + esc(h.error || "失败")}</div>
      </div>
      <div class="hright">${fmtTs(h.ts)}</div>
    </div>`;
  }
  if (S.history.length > S.histLimit) {
    html += `<div style="text-align:center;font-size:11px;color:var(--text3);padding:12px">还有 ${S.history.length - S.histLimit} 条更早的记录没显示</div>`;
  }
  box.innerHTML = html;
  $("#btnDelSel").textContent = `删除选中 ${S.sel.size} 条`;
  box.querySelectorAll(".hrow").forEach((row) => {
    const id = row.dataset.id;
    const rec = S.history.find((h) => h.id === id);
    let press;
    row.onmousedown = () => { press = setTimeout(() => { S.picking = true; S.sel.add(id); renderHistory(); }, 550); };
    row.onmouseup = () => clearTimeout(press);
    row.onclick = () => {
      if (S.picking) {
        if (S.sel.has(id)) S.sel.delete(id); else S.sel.add(id);
        renderHistory();
      } else if (rec && rec.status === "done") {
        ipc("open.path", { path: rec.path }).catch((e) => toast(String(e), "bad"));
      }
    };
    row.oncontextmenu = (e) => {
      e.preventDefault();
      S.picking = true;
      S.sel.add(id);
      renderHistory();
    };
  });
}

async function deleteSelected() {
  if (!S.sel.size) return;
  const ids = Array.from(S.sel);
  const files = S.history.filter((h) => ids.includes(h.id) && h.status === "done");
  const choice = await choiceDialog(
    "删除记录",
    `${ids.length} 条记录${files.length ? `，其中 ${files.length} 个文件还在手机里` : ""}`,
    [
      { key: "records", title: "只删记录", sub: "文件留在原处，之后还能在文件夹里找到" },
      { key: "both", title: "记录和文件一起删", sub: "按记录里的路径删文件，删不掉会提示", danger: true },
    ]
  );
  if (!choice) return;
  await ipc("history.delete", { ids, remove_files: choice === "both" });
  S.history = S.history.filter((h) => !ids.includes(h.id));
  S.sel.clear();
  S.picking = false;
  renderHistory();
  toast("已删除 " + ids.length + " 条", "ok");
}

/* ---------------- 镜像源 ---------------- */
function renderMirrors() {
  const c = S.config;
  $("#mirrorNotice").innerHTML = `逐个发 <b>${c.probe_kb} KB</b> Range 请求，得分 = 速度 70% + 延迟 30%。
    多个文件可同时下载，共用这份排名；失败按排名顺延，最多重试 <b>${c.failover ? c.failover_max : 0}</b> 个镜像。
    当前启用 ${S.mirrors.filter((m) => m.enabled).length} / ${S.mirrors.length} 个。`;
  const box = $("#mirrorManage");
  const modeText = (m) => (m.mode === "path" ? "拼 GitHub 路径" : m.mode === "direct" || !m.base ? "直连" : "拼完整链接");
  box.innerHTML = S.mirrors.map((m, i) => {
    const last = m.speed > 0
      ? `上次 ${fmtSpeed(m.speed)} · ${m.latency} ms`
      : m.fail >= 3
        ? `连续失败 ${m.fail} 次 · 建议删除`
        : m.checked ? "上次没测通" : "还没测过";
    return `<div class="mitem ${m.enabled ? "" : "off"}">
      <span class="grip"><b></b><b></b><b></b></span>
      <div style="flex:1;min-width:0">
        <div class="mname">${esc(m.name)}</div>
        <div class="mmeta">${esc(m.base || "github.com")} · ${modeText(m)} · ${esc(last)}</div>
      </div>
      <button class="mini-btn" data-mmove="-1" data-i="${i}" title="上移">${icon("i-up", 14)}</button>
      <button class="mini-btn" data-mmove="1" data-i="${i}" title="下移">${icon("i-down", 14)}</button>
      <div class="switch ${m.enabled ? "on" : ""}" data-mtoggle="${i}"><i></i></div>
      <button class="mini-btn danger" data-mdel="${i}" title="删除">${icon("i-trash", 14)}</button>
    </div>`;
  }).join("");
  box.querySelectorAll("[data-mtoggle]").forEach((sw) => {
    sw.onclick = async () => {
      const i = Number(sw.dataset.mtoggle);
      const on = !S.mirrors[i].enabled;
      S.mirrors[i].enabled = on;
      await ipc("mirror.toggle", { index: i, on });
      renderMirrors();
      renderMirrorMini();
    };
  });
  box.querySelectorAll("[data-mmove]").forEach((b) => {
    b.onclick = async () => {
      const i = Number(b.dataset.i);
      const d = Number(b.dataset.mmove);
      const j = i + d;
      if (j < 0 || j >= S.mirrors.length) return;
      const [x] = S.mirrors.splice(i, 1);
      S.mirrors.splice(j, 0, x);
      await ipc("mirror.move", { index: i, delta: d });
      renderMirrors();
      renderMirrorMini();
    };
  });
  box.querySelectorAll("[data-mdel]").forEach((b) => {
    b.onclick = async () => {
      const i = Number(b.dataset.mdel);
      const ok = await confirmDialog("删除镜像", `删掉 ${S.mirrors[i].name}？之后可以从「恢复内置」加回来。`, "删除", true);
      if (!ok) return;
      S.mirrors.splice(i, 1);
      await ipc("mirror.delete", { index: i });
      renderMirrors();
      renderMirrorMini();
    };
  });
}

/* ---------------- 设置 ---------------- */
function srow(title, sub, control) {
  return `<div class="srow"><div class="k">${esc(title)}${sub ? `<small>${esc(sub)}</small>` : ""}</div>${control || ""}</div>`;
}
function swControl(key) {
  const on = !!S.config[key];
  return `<div class="switch ${on ? "on" : ""}" data-cfg="${key}"><i></i></div>`;
}
function stepControl(key, min, max, suffix) {
  return `<div class="stepper"><button data-step="${key}" data-d="-1">−</button>
    <span class="val" data-edit="${key}">${S.config[key]}${suffix ? " " + suffix : ""}</span>
    <button data-step="${key}" data-d="1">+</button></div>`;
}
function selControl(key, options) {
  return `<select data-sel="${key}">${options.map(([v, l]) =>
    `<option value="${v}" ${String(S.config[key]) === String(v) ? "selected" : ""}>${l}</option>`).join("")}</select>`;
}

/** 异步版钉住：fn 结束后，再在随后几帧 / 120ms / 400ms 各校正一次。
    用来挡住异步事件（切页、重绘、焦点回收）把滚动位置改掉。 */
async function pinRowAsync(el, fn) {
  const scroller = el ? el.closest(".body") : null;
  if (!scroller) return fn();
  const before = el.getBoundingClientRect().top;
  const fix = () => {
    const d = el.getBoundingClientRect().top - before;
    if (Math.abs(d) > 0.5) scroller.scrollTop += d;
  };
  try {
    return await fn();
  } finally {
    fix();
    requestAnimationFrame(fix);
    setTimeout(fix, 120);
    setTimeout(fix, 400);
  }
}

/** 以某个元素为锚点执行一段 DOM 更新，并保持它在屏幕上不动。
    行内加点东西导致换行/变高时，下面内容不该整块跳走。 */
function keepAnchor(el, fn) {
  const scroller = el ? el.closest(".body") : null;
  const before = el ? el.getBoundingClientRect().top : 0;
  fn();
  if (!el || !scroller) return;
  const delta = el.getBoundingClientRect().top - before;
  if (Math.abs(delta) > 0.5) scroller.scrollTop += delta;
  requestAnimationFrame(() => {
    const d2 = el.getBoundingClientRect().top - before;
    if (Math.abs(d2) > 0.5) scroller.scrollTop += d2;
  });
}

/** 格式标签的 HTML，单独抽出来方便原地刷新。 */
function fmtChipsHTML(c) {
  const chips = S.formatMenu
    .map(([k, l]) => {
      const custom = (c.extra_formats || []).includes(k);
      const on = (c.formats || []).includes(k) ? "on" : "";
      return `<span class="chip ${on}" data-fmt="${k}"${custom ? ' data-custom="1" title="右键删除"' : ""}>${l}${custom ? " ×" : ""}</span>`;
    })
    .join("");
  return (
    chips +
    '<span class="chip" data-fmtadd="1" style="border-style:dashed">+ 添加格式</span>' +
    '<span class="chip" data-fmtreset="1">恢复默认</span>'
  );
}

/** 只重画格式标签这一小块，不动整页，也不让它上下乱跳。 */
function refreshFmtChips() {
  const anchor = document.querySelector("#settingsBody [data-fmtadd]");
  if (!anchor) return;
  const box = anchor.parentElement;
  const row = box.closest(".srow") || box;
  keepAnchor(row, () => {
    box.innerHTML = fmtChipsHTML(S.config);
    bindFmtChips(box);
  });
}

function renderSettings() {
  const box = $("#settingsBody");
  const keepTop = box ? box.scrollTop : 0;   // 重绘前记住位置，改完滚回去
  const c = S.config;
  const fmts = (c.formats || []).map((f) => f.toUpperCase()).join(" / ");
  const html = [];
  if (S.platform === "android") {
    html.push(`<div class="group-title">显示</div><div class="group">
      ${srow("显示主题", "深色 / 浅色 / 墨水屏黑白",
        `<div class="fmt-chips">
          ${[["auto", "自动"], ["dark", "深色"], ["light", "浅色"], ["mono", "黑白"]].map(([k, l]) =>
            `<span class="chip ${(c.theme_mode || "auto") === k ? "on" : ""}" data-theme-mode="${k}">${l}</span>`).join("")}
        </div>`)}
    </div>`);
  } else {
    html.push(`<div class="group-title">显示</div><div class="group">
      ${srow("主题", "深空 / 纸白 / 黑白三套",
        `<div class="fmt-chips">${[["dark", "深空"], ["light", "纸白"], ["mono", "黑白"]].map(([k, l]) =>
          `<span class="chip ${c.theme === k ? "on" : ""}" data-theme="${k}">${l}</span>`).join("")}</div>`)}
    </div>`);
  }
  html.push(`<div class="group-title">下载</div><div class="group">
    ${srow("默认保存位置", "点一下换个文件夹 · 未单独设置的项目都放这里",
      `<span class="v" id="rowRoot" title="${esc(c.download_root)}">${esc(c.download_root)}</span>`)}
    ${srow("同时下载任务数", "1–50 个文件同时下载，共用同一份镜像排名", stepControl("concurrency", 1, 50, "个"))}
    ${srow("单文件多线程", "切片并发拉取，镜像支持 Range 时生效", swControl("segments"))}
    ${srow("每个文件连接数", "1–50 段并发，机器和带宽够就拉满；网络差就调小", stepControl("threads", 1, 50, "线程"))}
    ${srow("失败自动换镜像", "按测速排名顺延", swControl("failover"))}
    ${srow("最多重试几个镜像", "一个失败就换下一个", selControl("failover_max", [1, 2, 3, 4, 5, 6, 7, 8, 9, 10].map((v) => [v, `${v} 个`])))}
    ${srow("断点续传", "镜像支持 Range 时，中断后从断点接上", swControl("resume"))}
  </div>`);
  html.push(`<div class="group-title">版本页</div><div class="group">
    ${srow("挑文件方式", "点一个文件就直接下载，适合只要一个包；批量模式先勾选再统一开跑",
      `<div class="seg ${c.version_mode === 1 ? "idx1" : ""}" id="segMode"><i></i>
        <span class="${c.version_mode === 0 ? "on" : ""}" data-mode="0">单个下载</span>
        <span class="${c.version_mode === 1 ? "on" : ""}" data-mode="1">批量勾选</span></div>`)}
    ${srow("默认下载格式", "版本页只显示勾选的格式；桌面端默认 EXE / MSI / ZIP / RAR / PDF",
      `<span class="v" id="rowFmt">${esc(fmts)}</span>`)}
    ${srow("格式范围", "多选，随时增删；自定义格式右键可删", `<div class="fmt-chips" style="max-width:62%">${fmtChipsHTML(c)}</div>`)}
    ${srow("同名文件默认规则", "项目没有单独设置时用它", selControl("replace_rule", S.rules.map(([k, l]) => [k, l])))}
  </div>`);
  html.push(`<div class="group-title">测速</div><div class="group">
    ${srow("探测分片", "越大越准，也越费流量", selControl("probe_kb", [[128, "128 KB"], [256, "256 KB"], [512, "512 KB"], [1024, "1 MB"], [2048, "2 MB"]]))}
    ${srow("单个镜像超时", "超时直接判负，不拖慢整体", selControl("timeout", [3, 4, 5, 6, 8, 10, 15, 20, 30, 60].map((v) => [v, `${v} 秒`])))}
    ${srow("复用测速结果", "周期内的结果直接使用，不必每次重测", `<div class="switch ${c.cache_on ? "on" : ""}" data-cfg="cache_on"><i></i></div>`)}
    ${srow("复用周期", "1–720 分钟，网络经常变就调短一点", selControl("cache_ttl", [[5, "5 分钟"], [10, "10 分钟"], [30, "30 分钟"], [60, "1 小时"], [180, "3 小时"], [720, "12 小时"]]))}
  </div>`);
  html.push(`<div class="group-title">记录与关于</div><div class="group">
    ${srow("历史记录条数", "列表最多显示多少条，调小不会删掉记录", selControl("history_max", [[20, "20 条"], [50, "50 条"], [100, "100 条"], [200, "200 条"]]))}
    ${srow("GitHub Token", "不填时每小时 60 次接口额度；填了 5000 次", `<span class="v" id="rowToken">${c.github_token ? "已填写" : "未填写"}</span>`)}
    ${srow("网络代理", "留空表示直连；支持 http:// 与 socks5://", `<span class="v" id="rowProxy" title="${esc(c.proxy)}">${esc(c.proxy || "未设置")}</span>`)}
    ${srow("测速 / 运行日志", "追加写入，可直接打开或清空", `<span class="v" id="rowLog">打开</span>`)}
    ${srow("版本", "Rust + Tauri 重写版", `<span class="v" style="cursor:default">${esc(S.version)} · ${esc(S.build || "")}</span>`)}
  </div>`);
  html.push(`<div style="text-align:center;font-size:11px;color:var(--text3);line-height:1.8;padding:18px 10px 4px">
    光速镜像 · 只做链路选择<br/>不代理、不缓存任何内容
  </div>`);
  box.innerHTML = html.join("");
  bindSettings();
  box.scrollTop = keepTop;
  requestAnimationFrame(() => {
    if (Math.abs(box.scrollTop - keepTop) > 0.5) box.scrollTop = keepTop;
  });
}

function bindSettings() {
  const body = $("#settingsBody");
  body.querySelectorAll("[data-cfg]").forEach((sw) => {
    sw.onclick = async () => {
      const key = sw.dataset.cfg;
      if (key === "cache_on") {
        await saveConfig({ cache_on: !S.config.cache_on });
      } else {
        await saveConfig({ [key]: !S.config[key] });
      }
    };
  });
  body.querySelectorAll("[data-step]").forEach((b) => {
    b.onclick = async () => {
      const key = b.dataset.step;
      const d = Number(b.dataset.d);
      let v = Number(S.config[key]) + d;
      const limits = { concurrency: [1, 50], threads: [1, 50] };
      const [lo, hi] = limits[key] || [1, 50];
      v = Math.max(lo, Math.min(hi, v));
      await saveConfig({ [key]: v });
    };
  });
  body.querySelectorAll("[data-edit]").forEach((v) => {
    v.onclick = async () => {
      const key = v.dataset.edit;
      const cur = S.config[key];
      const r = await promptDialog("输入数字", key === "concurrency" ? "1–50" : "1–50", String(cur));
      if (r === null) return;
      const n = parseInt(r, 10);
      if (!n || n < 1 || n > 50) return toast("请输入 1–50 的数字", "bad");
      await saveConfig({ [key]: n });
    };
  });
  body.querySelectorAll("[data-sel]").forEach((sel) => {
    sel.onchange = async () => {
      const key = sel.dataset.sel;
      const v = sel.value;
      await saveConfig({ [key]: isNaN(Number(v)) ? v : Number(v) });
    };
  });
  body.querySelectorAll("[data-theme]").forEach((c) => {
    c.onclick = () => saveConfig({ theme: c.dataset.theme });
  });
  body.querySelectorAll("[data-theme-mode]").forEach((c) => {
    c.onclick = () => saveConfig({ theme_mode: c.dataset.themeMode });
  });
  body.querySelectorAll("[data-fmt]").forEach((c) => {
    c.onclick = async () => {
      const k = c.dataset.fmt;
      let f = (S.config.formats || []).slice();
      f = f.includes(k) ? f.filter((x) => x !== k) : f.concat([k]);
      if (!f.length) return toast("至少留一个格式", "bad");
      await saveConfig({ formats: f });
    };
    if (c.dataset.custom) {
      c.addEventListener("contextmenu", async (e) => {
        e.preventDefault();
        const k = c.dataset.fmt;
        const ok = await confirmDialog("删除自定义格式", `不再跟踪 .${k}？`, "删除", true);
        if (!ok) return;
        await saveConfig({
          extra_formats: (S.config.extra_formats || []).filter((x) => x !== k),
          formats: (S.config.formats || []).filter((x) => x !== k),
        });
        await loadBoot();
      });
    }
  });

  bindFmtChips(body);
  // 整行可点：点标题/说明等同于点右侧控件（多选行除外）
  body.querySelectorAll(".srow").forEach((row) => {
    if (row.querySelector(".fmt-chips")) return;
    row.classList.add("clickable");
    row.addEventListener("click", (e) => {
      if (!e.target.closest(".k")) return;
      const inner = row.querySelector(".switch, [data-edit], .v, .seg span, select");
      if (inner) inner.click();
    });
  });
  const seg = $("#segMode");
  if (seg) {
    seg.querySelectorAll("[data-mode]").forEach((s) => {
      s.onclick = () => saveConfig({ version_mode: Number(s.dataset.mode) });
    });
  }
  const rowRoot = $("#rowRoot");
  if (rowRoot) {
    rowRoot.onclick = async () => {
      const p = await pathDialog(
        "默认保存位置",
        "没有单独设置目录的项目都下到这里",
        S.config.download_root
      );
      if (p) await saveConfig({ download_root: p });
    };
  }
  const rowFmt = $("#rowFmt");
  if (rowFmt) {
    // 点这行只是带路，别再把格式重置掉
    rowFmt.onclick = () => {
      const chip = document.querySelector("#settingsBody [data-fmt]");
      if (chip) {
        chip.closest(".srow").scrollIntoView({ block: "center", behavior: "smooth" });
        toast("在下面「格式范围」里勾选");
      }
    };
  }
  const rowToken = $("#rowToken");
  if (rowToken) {
    rowToken.onclick = async () => {
      const v = await promptDialog("GitHub Token", "只存在本机，用来提高接口额度", S.config.github_token || "");
      if (v === null) return;
      await saveConfig({ github_token: v.trim() });
    };
  }
  const rowProxy = $("#rowProxy");
  if (rowProxy) {
    rowProxy.onclick = async () => {
      const v = await promptDialog("网络代理", "例如 http://127.0.0.1:7890，留空表示直连", S.config.proxy || "");
      if (v === null) return;
      await saveConfig({ proxy: v.trim() });
    };
  }
  const rowLog = $("#rowLog");
  if (rowLog) rowLog.onclick = () => openLog();
}

function bindFmtChips(body) {
  body.querySelectorAll("[data-fmt]").forEach((c) => {
    c.onclick = async () => {
      const k = c.dataset.fmt;
      let f = (S.config.formats || []).slice();
      f = f.includes(k) ? f.filter((x) => x !== k) : f.concat([k]);
      if (!f.length) return toast("至少留一个格式", "bad");
      await saveConfig({ formats: f });
    };
    if (c.dataset.custom) {
      c.addEventListener("contextmenu", async (e) => {
        e.preventDefault();
        const k = c.dataset.fmt;
        const ok = await confirmDialog("删除自定义格式", `不再跟踪 .${k}？`, "删除", true);
        if (!ok) return;
        await saveConfig({
          extra_formats: (S.config.extra_formats || []).filter((x) => x !== k),
          formats: (S.config.formats || []).filter((x) => x !== k),
        });
        S.formatMenu = S.formatMenu.filter(([x]) => x !== k);
        refreshFmtChips();
      });
    }
  });

  const addBtn = body.querySelector("[data-fmtadd]");
  if (addBtn) {
    addBtn.onclick = () =>
      pinRowAsync(addBtn.closest(".srow"), async () => {
        const v = await promptDialog("添加格式", "填扩展名，例如 iso / epub / apk（不带点）", "");
        if (v === null) return;
        const ext = v.trim().toLowerCase().replace(/^\./, "");
        if (!/^[a-z0-9]{1,10}$/.test(ext)) return toast("扩展名只能是字母或数字，1–10 位", "bad");
        if ((S.config.formats || []).includes(ext)) return toast("这个格式已经在列表里了");
        const extra = Array.from(new Set([...(S.config.extra_formats || []), ext]));
        await saveConfig({ extra_formats: extra, formats: [...(S.config.formats || []), ext] });
        if (!S.formatMenu.some(([x]) => x === ext)) {
          S.formatMenu = [...S.formatMenu, [ext, ext.toUpperCase()]];
        }
        refreshFmtChips();
        toast(`已添加 .${ext}`, "ok");
      });
  }

  const resetBtn = body.querySelector("[data-fmtreset]");
  if (resetBtn) {
    resetBtn.onclick = () =>
      pinRowAsync(resetBtn.closest(".srow"), async () => {
        const ok = await confirmDialog("恢复默认格式", "把格式范围和自定义格式都恢复成默认值？", "恢复", false);
        if (!ok) return;
        await saveConfig({ formats: (S.defaultFormats || []).slice(), extra_formats: [] });
        S.formatMenu = (S.builtin || []).slice();
        refreshFmtChips();
        toast("已恢复默认格式", "ok");
      });
  }

}

/* ---------------- 弹层 ---------------- */
function openDialog({ title, sub, body, footer = [], wide = false, sheet = false, onClose }) {
  const mask = document.createElement("div");
  mask.className = "mask" + (sheet ? "" : " center");
  mask.innerHTML = `<div class="dialog ${wide ? "wide" : ""} ${sheet ? "sheet" : ""}">
    <div class="dhead">
      <div style="flex:1;min-width:0"><h3>${esc(title)}</h3>${sub ? `<p>${esc(sub)}</p>` : ""}</div>
      <button class="xbtn" data-close>${icon("i-x", 16)}</button>
    </div>
    <div class="dbody">${body || ""}</div>
    ${footer.length ? `<div class="dfoot">${footer.map((f) =>
      `<button class="btn ${f.cls || ""}" ${f.id ? `id="${f.id}"` : ""}>${esc(f.text)}</button>`).join("")}</div>` : ""}
  </div>`;
  // 记下当前滚动位置：弹框关闭后焦点回收，WebView 有时会把页面滚到别处
  const scroller = document.querySelector(".page.on .body");
  mask.__keepTop = scroller ? scroller.scrollTop : -1;
  $("#modalRoot").appendChild(mask);
  requestAnimationFrame(() => mask.classList.add("on"));
  mask.onclick = (e) => {
    if (e.target === mask) closeDialog();
  };
  mask.querySelector("[data-close]").onclick = () => closeDialog();
  const btns = mask.querySelectorAll(".dfoot .btn");
  footer.forEach((f, i) => {
    if (f.onClick) btns[i].onclick = f.onClick;
  });
  window.__dlgOnClose = onClose;
  return mask;
}

function closeDialog() {
  const mask = $("#modalRoot .mask");
  if (!mask) return;
  const scroller = document.querySelector(".page.on .body");
  const keepTop = mask.__keepTop;
  if (window.__dlgOnClose) { try { window.__dlgOnClose(); } catch (e) {} window.__dlgOnClose = null; }
  mask.remove();
  if (scroller && keepTop >= 0 && Math.abs(scroller.scrollTop - keepTop) > 0.5) {
    scroller.scrollTop = keepTop;
  }
}

function confirmDialog(title, msg, okText = "确定", danger = false, cancelText = "取消") {
  return new Promise((resolve) => {
    const dlg = openDialog({
      title,
      body: `<p style="font-size:12.5px;color:var(--text2);line-height:1.7;margin:0 0 4px">${esc(msg)}</p>`,
      footer: [
        { text: cancelText, cls: "ghost", onClick: () => { closeDialog(); resolve(false); } },
        { text: okText, cls: danger ? "danger" : "primary", onClick: () => { closeDialog(); resolve(true); } },
      ],
    });
    dlg.classList.add("center");
  });
}

function choiceDialog(title, msg, options) {
  return new Promise((resolve) => {
    let done = false;
    const body = `<p style="font-size:12px;color:var(--text3);margin:0;line-height:1.6">${esc(msg)}</p>
      ${options.map((o, i) => `<button class="dopt ${o.danger ? "danger" : ""}" data-opt="${i}">
        <b>${esc(o.title)}</b>${o.sub ? `<small>${esc(o.sub)}</small>` : ""}</button>`).join("")}`;
    const dlg = openDialog({ title, body, footer: [{ text: "取消", cls: "ghost" }] });
    dlg.querySelectorAll("[data-opt]").forEach((b) => {
      b.onclick = () => {
        done = true;
        const k = options[Number(b.dataset.opt)].key;
        closeDialog();
        resolve(k);
      };
    });
    dlg.querySelector(".dfoot .btn").onclick = () => { closeDialog(); resolve(null); };
    window.__dlgOnClose = () => { if (!done) resolve(null); };
  });
}

function promptDialog(title, msg, value = "") {
  return new Promise((resolve) => {
    let done = false;
    const body = `<p style="font-size:12px;color:var(--text3);margin:0 0 10px;line-height:1.6">${esc(msg)}</p>
      <input class="input-field" id="promptInput" style="width:100%;font-family:var(--font)" value="${esc(value)}"/>`;
    const dlg = openDialog({
      title,
      body,
      footer: [
        { text: "取消", cls: "ghost", onClick: () => { done = true; closeDialog(); resolve(null); } },
        { text: "确定", cls: "primary", onClick: () => { done = true; const v = dlg.querySelector("#promptInput").value; closeDialog(); resolve(v); } },
      ],
    });
    const input = dlg.querySelector("#promptInput");
    input.focus();
    input.select();
    input.onkeydown = (e) => {
      if (e.key === "Enter") {
        done = true;
        closeDialog();
        resolve(input.value);
      }
    };
    window.__dlgOnClose = () => { if (!done) resolve(null); };
  });
}

/** 路径选择：自带输入框，另有「选择文件夹…」调系统对话框，两边都能用。 */
function pathDialog(title, msg, value = "") {
  return new Promise((resolve) => {
    let done = false;
    const body = `<p style="font-size:12px;color:var(--text3);margin:0 0 10px;line-height:1.6">${esc(msg)}</p>
      <input class="input-field" id="pathInput" spellcheck="false" style="width:100%;font-family:var(--mono);font-size:12px" value="${esc(value)}"/>
      <div style="margin-top:10px;display:flex;gap:8px;align-items:center">
        <button class="btn sm" id="pathBrowse">选择文件夹…</button>
        <span style="font-size:11px;color:var(--text3)">也可以直接粘贴路径</span>
      </div>`;
    const dlg = openDialog({
      title,
      body,
      footer: [
        { text: "取消", cls: "ghost", onClick: () => { done = true; closeDialog(); resolve(null); } },
        {
          text: "确定",
          cls: "primary",
          onClick: () => {
            done = true;
            const v = dlg.querySelector("#pathInput").value;
            closeDialog();
            resolve(v.trim() || null);
          },
        },
      ],
    });
    const input = dlg.querySelector("#pathInput");
    input.focus();
    input.select();
    input.onkeydown = (e) => {
      if (e.key === "Enter") {
        done = true;
        closeDialog();
        resolve(input.value.trim() || null);
      }
    };
    dlg.querySelector("#pathBrowse").onclick = async () => {
      try {
        const r = await ipc("dir.pick");
        if (r && r.path) input.value = r.path;
        else if (r && r.unsupported) toast("这个平台没有目录选择器，直接填路径即可");
      } catch (e) {
        toast("选择失败：" + e, "bad");
      }
    };
    window.__dlgOnClose = () => { if (!done) resolve(null); };
  });
}

async function openLog() {
  let data = { path: "", text: "" };
  try {
    data = await ipc("log.text", { limit: 400 });
  } catch (e) {}
  const body = `<div style="font-family:var(--mono);font-size:10.5px;line-height:1.75;white-space:pre-wrap;color:var(--text2);max-height:52vh;overflow:auto;background:var(--surface2);border:1px solid var(--line);border-radius:10px;padding:10px">${esc(data.text || "还没有日志")}</div>
    <div style="font-size:10.5px;color:var(--text3);margin-top:8px;word-break:break-all">${esc(data.path)}</div>`;
  openDialog({
    title: "测速 / 运行日志",
    sub: "追加写入，不覆盖旧内容",
    wide: true,
    body,
    footer: [
      { text: "清空", cls: "danger", onClick: async () => { await ipc("log.clear"); toast("已清空日志", "ok"); closeDialog(); } },
      { text: "关闭", cls: "ghost", onClick: () => closeDialog() },
    ],
  });
}

/* ---------------- 事件绑定 ---------------- */
function bindGlobal() {
  $("#urlInput").addEventListener("input", debounce(updateParsed, 220));
  $("#urlInput").addEventListener("keydown", (e) => {
    if (e.key === "Enter") startFlow(false);
  });
  $("#btnPaste").onclick = async () => {
    try {
      const t = await navigator.clipboard.readText();
      if (t) {
        $("#urlInput").value = t.trim();
        await updateParsed();
      } else {
        toast("剪贴板是空的");
      }
    } catch (e) {
      $("#urlInput").focus();
      toast("请手动长按输入框粘贴");
    }
  };
  $("#btnStart").onclick = () => startFlow(false);
  $("#btnTestOnly").onclick = () => startFlow(true);
  $("#btnRetest").onclick = () => {
    const p = S.parsed;
    const url = (p && p.url) || $("#urlInput").value.trim();
    if (url) withBusy(() => runSpeedTest(url));
    else toast("先贴一个链接");
  };
  $("#btnOpenRoot").onclick = () => ipc("open.path", { path: S.config.download_root }).catch((e) => toast(String(e), "bad"));
  $("#btnClearDone").onclick = async () => { await ipc("task.clear"); };
  $("#btnCancelAll").onclick = async () => {
    const ok = await confirmDialog("全部取消", "正在下载和排队的任务都会停下来，临时文件会被删除。", "全部取消", true);
    if (!ok) return;
    await ipc("task.cancel_all");
  };
  document.addEventListener("click", (e) => {
    const b = e.target.closest("[data-task]");
    if (!b) return;
    const act = b.dataset.task;
    const id = b.dataset.id;
    if (act === "pause") ipc("task.pause", { id });
    else if (act === "resume") ipc("task.resume", { id });
    else if (act === "cancel") ipc("task.cancel", { id });
    else if (act === "open") ipc("open.path", { path: b.dataset.path }).catch((x) => toast(String(x), "bad"));
    else if (act === "reveal") ipc("reveal.path", { path: b.dataset.path }).catch((x) => toast(String(x), "bad"));
  });

  $("#btnTrack").onclick = () => {
    const v = $("#repoInput").value.trim();
    if (!v) return toast("填 owner/repo");
    trackRepo(v);
  };
  $("#repoInput").addEventListener("keydown", (e) => {
    if (e.key === "Enter") $("#btnTrack").click();
  });
  $("#btnCheckAll").onclick = async () => {
    toast("正在检查全部项目…");
    await ipc("watch.check_all", { force: true });
    await loadBoot();
  };
  $$("[data-sort]").forEach((b) => {
    b.onclick = () => saveConfig({ watch_sort: b.dataset.sort });
  });

  $("#btnPickHist").onclick = () => {
    S.picking = !S.picking;
    S.sel.clear();
    renderHistory();
  };
  $("#btnSelAll").onclick = () => {
    const list = S.history.slice(0, S.histLimit);
    if (S.sel.size === list.length) S.sel.clear();
    else list.forEach((h) => S.sel.add(h.id));
    renderHistory();
  };
  $("#btnDelSel").onclick = deleteSelected;
  $("#btnClearHist").onclick = async () => {
    if (!S.history.length) return;
    const choice = await choiceDialog("清空全部记录", `共 ${S.history.length} 条`, [
      { key: "records", title: "只删记录", sub: "文件都留着" },
      { key: "both", title: "记录和文件一起删", sub: "按记录路径删文件，删不掉会提示", danger: true },
    ]);
    if (!choice) return;
    await ipc("history.clear", { remove_files: choice === "both" });
    S.history = [];
    S.sel.clear();
    S.picking = false;
    renderHistory();
  };

  $("#btnLog").onclick = openLog;
  $("#btnRestoreMirror").onclick = async () => {
    await ipc("mirror.restore");
    await loadBoot();
    toast("已恢复内置镜像", "ok");
  };
  $("#btnAddMirror").onclick = async () => {
    try {
      await ipc("mirror.add", {
        name: $("#newMirrorName").value.trim(),
        base: $("#newMirrorBase").value.trim(),
        mode: $("#newMirrorMode").value,
      });
      $("#newMirrorName").value = "";
      $("#newMirrorBase").value = "";
      await loadBoot();
      toast("已添加", "ok");
    } catch (e) {
      toast(String(e), "bad");
    }
  };
}

/* ---------------- 数据加载 ---------------- */
async function loadBoot() {
  const b = await ipc("bootstrap", {
    globals: { tauri: !!TAURI, internals: !!INTERNALS },
  });
  S.boot = b;
  S.config = b.config;
  S.mirrors = b.mirrors;
  S.watch = b.watch;
  S.history = b.history;
  S.platform = b.platform || "windows";
  S.version = b.version;
  S.build = b.build || "local";
  S.rules = b.rules || [];
  S.builtin = b.builtin || [];
  S.defaultFormats = b.default_formats || [];
  S.formatMenu = b.format_menu || [];
  document.body.classList.toggle("plat-android", S.platform === "android");
  updateLayout();
  applyTheme();
  $("#sideVer").textContent = "v" + b.version + " · " + (b.build || "local");
  renderHomeChrome();
  renderMirrorMini();
  renderTasks();
  renderWatch();
  if (S.page === "settings") applyConfigUI();   // 不重建，避免跳顶
  if (S.page === "history") renderHistory();
  if (S.page === "mirrors") renderMirrors();
}

function renderAll() {
  renderHomeChrome();
  renderMirrorMini();
  renderWatch();
  if (S.page === "settings") renderSettings();
}

async function init() {
  buildNav();
  go("home", true);
  bindGlobal();
  updateLayout();
  // 先把空态铺上，哪怕后端起不来也不会只剩一片黑
  renderTasks();
  renderMirrorMini();
  renderWatch();
  window.addEventListener("resize", debounce(updateLayout, 120));
  if (!IS_TAURI) {
    if (IN_APP) {
      showFatal("原生桥接未注入（__TAURI__ 与 __TAURI_INTERNALS__ 都不存在）");
      return;
    }
    if (!window.__MOCK__) {
      toast("请在应用内运行");
      return;
    }
  }
  try {
    await loadBoot();
  } catch (e) {
    showFatal(e);
  }
  if (!IS_TAURI && window.__MOCK__) {
    S.tasks = window.__MOCK__.tasks || [];
    renderTasks();
    const q = new URLSearchParams(location.search);
    if (q.get("page")) go(q.get("page"));
    return;
  }
  await onEvent("task:update", (list) => {
    S.tasks = list || [];
    renderTasks();
  });
  await onEvent("speed:result", (p) => {
    if (!p) return;
    S.speed[p.name] = { ok: p.ok, latency: p.latency, speed: p.speed, testing: false };
    S.speedDone = (S.speedDone || 0) + 1;
    if (S.speedRunning) {
      const btn = $("#btnStart");
      if (btn) btn.textContent = `正在测速 ${S.speedDone}/${S.speedTotal || "?"}…`;
    }
    const m = S.mirrors.find((x) => x.name === p.name);
    if (m) {
      m.latency = p.latency;
      m.speed = p.speed;
      m.fail = p.ok ? 0 : (m.fail || 0) + 1;
      m.checked = Date.now();
    }
    renderMirrorMini();
  });
  await onEvent("speed:done", () => {
    // 提示语统一由 runSpeedTest 负责，这里只收尾状态
    S.speedRunning = false;
    renderMirrorMini();
    renderHomeChrome();
  });
  await onEvent("watch:update", async () => {
    await loadBoot();
  });
  await onEvent("config:update", (cfg) => {
    if (cfg) {
      S.config = cfg;
      applyTheme();
      applyConfigUI();
    }
  });
}

window.addEventListener("DOMContentLoaded", init);
})();
