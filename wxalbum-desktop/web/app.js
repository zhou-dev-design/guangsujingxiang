'use strict';
/* 公众号转 EPUB · 桌面端 v2 前端逻辑
   只依赖后端已有接口：/api/state /api/parse /api/build /api/cancel
   /api/watch* /api/history* /api/config /api/pickdir /api/open /api/openfile /api/download /api/thumb */

const $ = s => document.querySelector(s);
const $$ = s => Array.from(document.querySelectorAll(s));
const BUSY = ['parsing', 'fetching', 'building'];
const PHASE_TEXT = {
  idle: '准备就绪', parsing: '解析中', ready: '已就绪', fetching: '抓取正文',
  building: '打包 EPUB', done: '已完成', error: '出错了', cancelled: '已取消'
};

let S = null;
let cfg = null;
let history = [];
let histSel = new Set();
let sel = new Set();
let listId = -1;
let rows = [];
let rowsSig = '';
let watchSig = '';
let histRev = -1;
let logLen = -1;
let tab = 'download';
let toastTimer = null;

/* ---------------- 基础工具 ---------------- */
async function post(path, body) {
  const r = await fetch(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body || {})
  });
  let data = null;
  try { data = await r.json(); } catch (e) { data = null; }
  if (!r.ok) throw new Error((data && data.error) || ('HTTP ' + r.status));
  return data;
}
async function get(path) {
  const r = await fetch(path);
  if (!r.ok) throw new Error('HTTP ' + r.status);
  return r.json();
}
function toast(msg) {
  const el = $('#toast');
  el.textContent = msg;
  el.classList.add('on');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.classList.remove('on'), 2600);
}
function fmtSize(n) {
  if (!n) return '—';
  if (n < 1024) return n + ' B';
  if (n < 1024 * 1024) return (n / 1024).toFixed(0) + ' KB';
  return (n / 1024 / 1024).toFixed(2) + ' MB';
}
function fmtChars(n) {
  if (!n) return '—';
  return n >= 10000 ? (n / 10000).toFixed(1) + ' 万字' : n + ' 字';
}
function esc(s) {
  return String(s == null ? '' : s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
}

/* ---------------- 导航 ---------------- */
const SUBTITLE = {
  download: '粘贴合集链接或任意一篇文章，自动反查整本',
  shelf: '生成过的书都在这里，可检查更新并增量重建',
  history: '每次生成都会留一条记录，按需清理文件或记录',
  settings: '保存位置、并发、默认选项与主题'
};
function setTab(name) {
  tab = name;
  $$('.nav').forEach(b => b.classList.toggle('on', b.dataset.tab === name));
  $$('.view').forEach(v => v.classList.toggle('on', v.id === 'view-' + name));
  $('#pageTitle').textContent = { download: '下载', shelf: '书架', history: '历史', settings: '设置' }[name];
  $('#pageSub').textContent = SUBTITLE[name];
  if (name === 'history') loadHistory(true);
  if (name === 'settings') loadConfig();
}
$$('.nav').forEach(b => b.onclick = () => setTab(b.dataset.tab));

/* ---------------- 主题 ---------------- */
function applyTheme(t) {
  document.documentElement.dataset.theme = t;
  $('#btnTheme').textContent = t === 'dark' ? '切换浅色' : '切换深色';
  const selEl = $('#setTheme');
  if (selEl) selEl.value = t;
  try { localStorage.setItem('theme', t); } catch (e) {}
}
$('#btnTheme').onclick = () => applyTheme(document.documentElement.dataset.theme === 'dark' ? 'light' : 'dark');

/* ---------------- 选项 ---------------- */
function loadPrefs() {
  let img = false, info = true, delay = '0.5';
  try {
    img = localStorage.getItem('defImages') === '1';
    info = localStorage.getItem('defBookinfo') !== '0';
    delay = localStorage.getItem('defDelay') || '0.5';
  } catch (e) {}
  $('#optImages').checked = img;
  $('#optBookinfo').checked = info;
  $('#optDelay').value = delay;
  $('#setImages').checked = img;
  $('#setBookinfo').checked = info;
}
$('#optImages').onchange = () => {
  try { localStorage.setItem('defImages', $('#optImages').checked ? '1' : '0'); } catch (e) {}
};
$('#optBookinfo').onchange = () => {
  try { localStorage.setItem('defBookinfo', $('#optBookinfo').checked ? '1' : '0'); } catch (e) {}
};
$('#optDelay').onchange = () => {
  try { localStorage.setItem('defDelay', $('#optDelay').value); } catch (e) {}
};

/* ---------------- 解析 ---------------- */
$('#btnParse').onclick = async () => {
  const v = $('#inp').value.trim();
  if (!v) return toast('请先粘贴链接');
  $('#btnParse').disabled = true;
  $('#btnParse').textContent = '解析中…';
  try {
    await post('/api/parse', { input: v, expand: $('#optExpand').checked });
    toast('已开始解析');
  } catch (e) {
    toast('解析失败：' + e.message);
  } finally {
    $('#btnParse').disabled = false;
    $('#btnParse').textContent = '解析';
  }
};
$('#btnPaste').onclick = async () => {
  try {
    const t = await navigator.clipboard.readText();
    if (t) { $('#inp').value = t.trim(); toast('已粘贴'); }
  } catch (e) { toast('浏览器不允许读剪贴板，请手动粘贴'); }
};
$('#inp').addEventListener('keydown', e => { if (e.key === 'Enter') $('#btnParse').click(); });

/* ---------------- 章节选择 ---------------- */
$$('[data-sel]').forEach(b => b.onclick = () => {
  const mode = b.dataset.sel;
  const arts = (S && S.articles) || [];
  if (mode === 'all') sel = new Set(arts.map(a => a.key));
  else if (mode === 'none') sel = new Set();
  else if (mode === 'invert') sel = new Set(arts.filter(a => !sel.has(a.key)).map(a => a.key));
  else if (mode === 'current') sel = new Set(arts.filter(a => a.current).map(a => a.key));
  else if (mode === 'fail') sel = new Set(arts.filter(a => a.status === 'fail').map(a => a.key));
  syncChecks();
  updateActionBar();
});
function syncChecks() {
  rows.forEach(r => { r.cb.checked = sel.has(r.key); });
  $$('.arow').forEach(r => r.classList.toggle('sel', r.querySelector('input').checked));
}

/* ---------------- 状态轮询 ---------------- */
async function tick() {
  try {
    const st = await get('/api/state');
    S = st;
    render();
  } catch (e) { /* 服务重启瞬间会失败，忽略 */ }
}

function render() {
  if (!S) return;
  const busy = BUSY.includes(S.phase);
  $('#phaseText').textContent = S.message || PHASE_TEXT[S.phase] || S.phase;
  const dot = $('#dot');
  dot.className = 'dot ' + (busy ? 'run' : (S.phase === 'error' ? 'err' : (S.phase === 'done' ? 'ok' : '')));

  renderAlbum();
  renderList();
  updateActionBar();
  renderDock();
  renderLog();
  renderShelf();

  if (S.historyRev !== undefined && S.historyRev !== histRev) {
    histRev = S.historyRev;
    if (tab === 'history') loadHistory(true);
  }
}

function renderAlbum() {
  const a = S.album;
  const card = $('#albumCard');
  if (!a) { card.classList.add('hidden'); return; }
  card.classList.remove('hidden');
  $('#albumHint').textContent = a.album_id && a.album_id !== 'single' ? ('ID ' + a.album_id) : '单篇文章';
  const cov = $('#cover');
  cov.innerHTML = '';
  if (a.cover && !cov.dataset.src) {
    const img = new Image();
    img.onload = () => { cov.innerHTML = ''; cov.appendChild(img); };
    img.onerror = () => { cov.textContent = '无封面'; };
    img.src = '/api/thumb?u=' + encodeURIComponent(a.cover);
    cov.dataset.src = a.cover;
  } else if (!a.cover) {
    cov.textContent = '无封面';
  }
  const ti = $('#inpTitle'), au = $('#inpAuthor');
  if (document.activeElement !== ti) ti.value = a.title || '';
  if (document.activeElement !== au) au.value = a.author || '';
  const arts = S.articles || [];
  const chars = arts.reduce((s, x) => s + (x.chars || 0), 0);
  $('#metarow').innerHTML =
    '<span class="chip">' + arts.length + ' 篇</span>' +
    (a.count ? '<span class="chip">页面标注 ' + a.count + ' 篇</span>' : '') +
    (chars ? '<span class="chip">已抓 ' + fmtChars(chars) + '</span>' : '');
}

function renderList() {
  const arts = (S && S.articles) || [];
  const card = $('#listCard');
  if (!arts.length) { card.classList.add('hidden'); return; }
  card.classList.remove('hidden');
  $('#listCount').textContent = arts.length;

  if (S.listId !== listId) {
    listId = S.listId;
    sel = new Set(arts.map(a => a.key));
    buildRows(arts);
  }
  const sig = arts.map(a => (a.status || '') + (a.chars || 0)).join(',');
  if (sig !== rowsSig) {
    rowsSig = sig;
    arts.forEach((a, i) => {
      const r = rows[i];
      if (!r) return;
      const st = r.el.querySelector('.st');
      const map = { pending: ['待抓取', ''], fetching: ['抓取中', 'fetching'], done: ['完成', 'done'], fail: ['失败', 'fail'] };
      const m = map[a.status] || map.pending;
      st.textContent = m[0];
      st.className = 'st ' + m[1];
      r.el.querySelector('.ch').textContent = a.chars ? fmtChars(a.chars) : '';
    });
  }
}

function buildRows(arts) {
  const box = $('#alist');
  box.innerHTML = '';
  rows = [];
  const frag = document.createDocumentFragment();
  arts.forEach((a, i) => {
    const el = document.createElement('label');
    el.className = 'arow';
    el.innerHTML =
      '<input type="checkbox"/>' +
      '<span class="no">' + (a.no || i + 1) + '</span>' +
      '<span class="tt">' + esc(a.title || '(无标题)') + '</span>' +
      (a.current ? '<span class="tag">本文</span>' : '') +
      '<span class="st"></span><span class="ch"></span>';
    const cb = el.querySelector('input');
    cb.checked = sel.has(a.key);
    cb.onchange = () => {
      if (cb.checked) sel.add(a.key); else sel.delete(a.key);
      el.classList.toggle('sel', cb.checked);
      updateActionBar();
    };
    frag.appendChild(el);
    rows.push({ el, cb, key: a.key });
  });
  box.appendChild(frag);
  rowsSig = '';
}

function updateActionBar() {
  const arts = (S && S.articles) || [];
  const n = sel.size;
  $('#abTitle').textContent = arts.length ? (n + ' / ' + arts.length + ' 篇已选') : '等待解析';
  $('#abSub').textContent = arts.length
    ? '书名：' + (($('#inpTitle').value || (S.album && S.album.title) || '未命名'))
    : '解析后勾选章节即可生成 EPUB';
  $('#btnBuild').disabled = !arts.length || n === 0 || BUSY.includes(S.phase);
  $('#btnBuild').textContent = BUSY.includes(S.phase) ? '处理中…' : ('开始生成' + (n ? '（' + n + '）' : ''));
  $('#btnCancel').disabled = !BUSY.includes(S.phase);
}

$('#btnBuild').onclick = async () => {
  const keys = Array.from(sel);
  if (!keys.length) return toast('没有选中章节');
  const opts = {
    title: $('#inpTitle').value.trim(),
    author: $('#inpAuthor').value.trim(),
    images: $('#optImages').checked,
    bookinfo: $('#optBookinfo').checked,
    numbering: $('#optNumbering').checked,
    cache: $('#optCache').checked,
    delay: parseFloat($('#optDelay').value) || 0,
    titlemode: $('#optTitle').value,
    concurrency: (cfg && cfg.concurrency) || 0
  };
  if (opts.title === '') delete opts.title;
  if (opts.author === '') delete opts.author;
  try {
    await post('/api/build', { selected: keys, opts });
    toast('已开始生成');
  } catch (e) { toast('启动失败：' + e.message); }
};
$('#btnCancel').onclick = () => post('/api/cancel', {}).catch(() => {});

/* ---------------- 进度与日志 ---------------- */
function renderDock() {
  const p = S.progress || { total: 0, done: 0 };
  const busy = BUSY.includes(S.phase);
  const dock = $('#dock');
  dock.classList.toggle('up', busy || S.phase === 'done' || $('#logbox').classList.contains('on'));
  const pct = p.total ? Math.round(p.done / p.total * 100) : 0;
  $('#fill').style.width = (busy ? pct : (S.phase === 'done' ? 100 : 0)) + '%';
  $('#dockCur').textContent = busy
    ? (p.current ? ('正在抓取：' + p.current) : (S.message || '处理中…'))
    : (S.phase === 'done' ? (S.message || '已完成') : '空闲');
  $('#dockNums').textContent = p.total ? (p.done + ' / ' + p.total) : '0 / 0';
}

function renderLog() {
  const logs = (S && S.log) || [];
  if (logs.length === logLen) return;
  logLen = logs.length;
  const box = $('#logbox');
  box.innerHTML = logs.map(l =>
    '<div class="' + (l.lv || 'info') + '">' + esc(l.m) + '</div>').join('');
  box.scrollTop = box.scrollHeight;
}
$('#btnLog').onclick = () => {
  $('#logbox').classList.toggle('on');
  renderDock();
};

/* ---------------- 书架 ---------------- */
function renderShelf() {
  const w = S.watch || { items: [] };
  const items = w.items || [];
  const grid = $('#shelfGrid');
  const empty = $('#shelfEmpty');
  const badge = $('#navBadge');
  const fresh = items.reduce((s, x) => s + (x.new || 0), 0);
  badge.textContent = fresh ? String(fresh) : '';
  $('#shelfHint').textContent = w.checking
    ? '正在检查…'
    : (w.checked_at ? '上次检查 ' + w.checked_at : '生成过的合集会自动加入这里');
  $('#btnCheckAll').disabled = !!w.checking || BUSY.includes(S.phase);

  empty.style.display = items.length ? 'none' : 'block';
  const sig = items.map(i => [i.album_id, i.count, i.new, i.removed, i.error, i.last_build].join(',')).join('|')
    + '|' + (w.checking ? 1 : 0) + '|' + (BUSY.includes(S.phase) ? 1 : 0);
  if (sig === watchSig) return;
  watchSig = sig;
  grid.innerHTML = '';
  items.forEach(it => {
    const el = document.createElement('div');
    el.className = 'book';
    el.dataset.id = it.album_id;
    const fresh = it.new > 0;
    let badgeHtml = '<span class="chip">—</span>';
    if (it.error) badgeHtml = '<span class="chip" style="color:var(--danger);border-color:rgba(255,92,92,.3);background:var(--danger-soft)">检查失败</span>';
    else if (w.checking && it.new === null) badgeHtml = '<span class="chip">检查中</span>';
    else if (fresh) badgeHtml = '<span class="chip ok">+' + it.new + ' 章</span>';
    else if (it.new === 0) badgeHtml = '<span class="chip ok">已最新</span>';
    el.innerHTML =
      '<div class="cover">' + (it.cover ? '' : '书') + '</div>' +
      '<div class="bookmain">' +
        '<div class="booktitle"><span>' + esc(it.title || it.album_id) + '</span>' + badgeHtml + '</div>' +
        '<div class="booksub">' + (it.count || 0) + ' 篇' +
          (it.last_build ? ' · 生成于 ' + esc(it.last_build) : '') +
          (it.last_check ? '<br/>检查于 ' + esc(it.last_check) : '') +
          (it.error ? '<br/>' + esc(it.error) : '') +
        '</div>' +
        '<div class="bookacts">' +
          '<button class="sm" data-act="update">更新</button>' +
          '<button class="sm" data-act="remove">移除</button>' +
        '</div>' +
      '</div>';
    if (it.cover) {
      const img = new Image();
      const box = el.querySelector('.cover');
      img.onload = () => { box.innerHTML = ''; box.appendChild(img); };
      img.src = '/api/thumb?u=' + encodeURIComponent(it.cover);
    }
    grid.appendChild(el);
  });
}
$('#btnCheckAll').onclick = async () => {
  try { await post('/api/watch/check', {}); toast('正在检查更新…'); }
  catch (e) { toast('检查失败：' + e.message); }
};
$('#shelfGrid').addEventListener('click', async e => {
  const btn = e.target.closest('[data-act]');
  if (!btn) return;
  const id = btn.closest('.book').dataset.id;
  const act = btn.dataset.act;
  if (act === 'update') {
    if (!confirm('更新会重新抓取新增章节并覆盖原文件，旧版自动备份到 _history。继续？')) return;
    btn.disabled = true; btn.textContent = '更新中…';
    try { await post('/api/watch/update', { album_id: id }); setTab('download'); toast('开始更新，完成前请勿关闭'); }
    catch (err) { toast('失败：' + err.message); btn.disabled = false; btn.textContent = '更新'; }
  } else {
    if (!confirm('从书架移除？不会删除已生成的 EPUB。')) return;
    try { await post('/api/watch/remove', { album_id: id }); toast('已移除'); watchSig = ''; }
    catch (err) { toast('失败：' + err.message); }
  }
});

/* ---------------- 历史 ---------------- */
async function loadHistory(force) {
  try {
    const d = await get('/api/history');
    history = d.items || [];
    histSel.clear();
    renderHistory();
  } catch (e) { /* ignore */ }
}

function renderHistory() {
  const box = $('#histList');
  const empty = $('#histEmpty');
  empty.style.display = history.length ? 'none' : 'block';
  $('#histInfo').textContent = histSel.size
    ? ('已选 ' + histSel.size + ' / ' + history.length + ' 条')
    : ('共 ' + history.length + ' 条记录');

  box.innerHTML = '';
  history.forEach(it => {
    const el = document.createElement('div');
    el.className = 'hrow' + (histSel.has(String(it.id)) ? ' sel' : '');
    el.dataset.id = it.id;
    const missing = !it.exists || it.file_missing;
    el.innerHTML =
      '<input type="checkbox" ' + (histSel.has(String(it.id)) ? 'checked' : '') + '/>' +
      '<div class="cover">' + (it.cover ? '' : 'EPUB') + '</div>' +
      '<div class="hmain">' +
        '<div class="htitle">' + esc(it.title || it.name) +
          '<span class="chip">' + esc(it.mode || '纯文字') + '</span>' +
        '</div>' +
        '<div class="hsub">' +
          '<span>' + esc(it.time || '') + '</span>' +
          '<span>' + (it.chapters || 0) + ' 章</span>' +
          '<span>' + fmtChars(it.chars) + '</span>' +
          '<span>' + fmtSize(it.size) + '</span>' +
          (missing ? '<span class="miss">文件已删除</span>' : '') +
        '</div>' +
        '<div class="hsub"><span style="font-size:10.5px">' + esc(it.path || '') + '</span></div>' +
      '</div>' +
      '<div class="hacts">' +
        '<button class="sm" data-act="folder" ' + (missing ? 'disabled' : '') + '>文件夹</button>' +
        '<button class="sm" data-act="open" ' + (missing ? 'disabled' : '') + '>打开</button>' +
      '</div>';
    if (it.cover) {
      const img = new Image();
      const c = el.querySelector('.cover');
      img.onload = () => { c.innerHTML = ''; c.appendChild(img); };
      img.src = '/api/thumb?u=' + encodeURIComponent(it.cover);
    }
    box.appendChild(el);
  });
}

$('#histList').addEventListener('click', async e => {
  const row = e.target.closest('.hrow');
  if (!row) return;
  const id = row.dataset.id;
  const cb = row.querySelector('input');
  if (e.target.tagName !== 'BUTTON' && e.target !== cb) {
    // 点行空白也算切换
    if (cb.checked) histSel.delete(id); else histSel.add(id);
    cb.checked = !cb.checked;
    row.classList.toggle('sel', cb.checked);
    $('#histInfo').textContent = histSel.size
      ? ('已选 ' + histSel.size + ' / ' + history.length + ' 条')
      : ('共 ' + history.length + ' 条记录');
    return;
  }
  if (e.target === cb) {
    if (cb.checked) histSel.add(id); else histSel.delete(id);
    row.classList.toggle('sel', cb.checked);
    $('#histInfo').textContent = histSel.size
      ? ('已选 ' + histSel.size + ' / ' + history.length + ' 条')
      : ('共 ' + history.length + ' 条记录');
    return;
  }
  const btn = e.target.closest('[data-act]');
  if (!btn || btn.disabled) return;
  const item = history.find(x => String(x.id) === id);
  if (!item) return;
  try {
    if (btn.dataset.act === 'folder') await post('/api/open', { path: item.path });
    else await post('/api/openfile', { path: item.path });
  } catch (err) { toast('打开失败：' + err.message); }
});

$('#btnHistAll').onclick = () => {
  histSel = new Set(history.map(x => String(x.id)));
  renderHistory();
};
$('#btnHistNone').onclick = () => { histSel.clear(); renderHistory(); };
function histIds() {
  if (!histSel.size) { toast('请先选择记录'); return null; }
  return Array.from(histSel);
}
async function histDelete(mode, tip) {
  const ids = histIds();
  if (!ids) return;
  if (!confirm(tip)) return;
  try {
    const r = await post('/api/history/delete', { ids, mode });
    toast('已删除 ' + (mode === 'file' ? (r.files + ' 个文件') : (r.removed + ' 条记录' + (r.files ? '、' + r.files + ' 个文件' : ''))));
    histSel.clear();
    await loadHistory(true);
  } catch (e) { toast('删除失败：' + e.message); }
}
$('#btnDelRecord').onclick = () => histDelete('record', '只删除记录，EPUB 文件保留。继续？');
$('#btnDelFile').onclick = () => histDelete('file', '只删除 EPUB 文件，记录保留并标记「文件已删除」。继续？');
$('#btnDelBoth').onclick = () => histDelete('both', '删除文件同时删除记录，不可恢复（不进回收站）。继续？');
$('#btnHistOpen').onclick = () => post('/api/open', {}).catch(e => toast('打开失败：' + e.message));

/* ---------------- 设置 ---------------- */
async function loadConfig() {
  try {
    cfg = await get('/api/config');
    $('#setDir').textContent = cfg.outdir || '默认输出目录';
    const sel2 = $('#optConc');
    const v = cfg.concurrency || cfg.recommend || 6;
    sel2.value = String(v);
    if (!sel2.value) sel2.value = '6';
    updateConcHint(parseInt(sel2.value, 10), cfg);
    if (!$('#optConc').dataset.bound) {
      $('#optConc').dataset.bound = '1';
    }
  } catch (e) {}
}
function updateConcHint(v, c) {
  const el = $('#concHint');
  if (!el) return;
  if (v > 12) {
    el.textContent = v + ' 线程很可能触发微信限流，反而更慢；建议 4–8';
    el.style.color = 'var(--warn)';
  } else {
    el.textContent = '推荐 ' + (c.recommend || 6) + '（本机 ' + (c.cpus || '?') + ' 线程），上限 ' + (c.max || 50);
    el.style.color = '';
  }
}
$('#optConc').onchange = async () => {
  const v = parseInt($('#optConc').value, 10) || 1;
  if (cfg) updateConcHint(v, cfg);
  try { await post('/api/config', { concurrency: v }); }
  catch (e) { toast('保存失败：' + e.message); }
};
$('#btnChangeDir').onclick = async () => {
  const b = $('#btnChangeDir');
  b.disabled = true; b.textContent = '选择中…';
  try {
    const r = await post('/api/pickdir', {});
    if (r.outdir) { $('#setDir').textContent = r.outdir; toast('保存位置已更新'); }
  } catch (e) { toast('打开选择框失败：' + e.message); }
  b.disabled = false; b.textContent = '更改';
};
$('#setImages').onchange = () => {
  try { localStorage.setItem('defImages', $('#setImages').checked ? '1' : '0'); } catch (e) {}
  $('#optImages').checked = $('#setImages').checked;
};
$('#setBookinfo').onchange = () => {
  try { localStorage.setItem('defBookinfo', $('#setBookinfo').checked ? '1' : '0'); } catch (e) {}
  $('#optBookinfo').checked = $('#setBookinfo').checked;
};
$('#setTheme').onchange = () => applyTheme($('#setTheme').value);
$('#btnQuit').onclick = () => {
  if (confirm('退出程序？正在进行的任务会被中断。')) post('/api/quit', {}).catch(() => {});
};

/* ---------------- 启动 ---------------- */
(function init() {
  let t = 'dark';
  try { t = localStorage.getItem('theme') || 'dark'; } catch (e) {}
  applyTheme(t);
  loadPrefs();
  loadConfig();
  setTab('download');
  tick();
  setInterval(tick, 700);
})();
