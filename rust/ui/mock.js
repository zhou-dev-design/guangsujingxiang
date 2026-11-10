/* 浏览器预览用的假数据：直接用浏览器打开 index.html 时才会被加载。
   打包进应用后，这段脚本永远不会执行（应用里有真实 IPC）。 */
(function () {
  const now = Date.now();
  const iso = (d) => new Date(d).toISOString();

  const mirrors = [
    ["ghfast.top", "https://ghfast.top/", "prefix", 9.8, 182],
    ["xget.xi-xu.me/gh", "https://xget.xi-xu.me/gh/", "path", 12.1, 96],
    ["gh-proxy.com", "https://gh-proxy.com/", "prefix", 8.4, 206],
    ["ghproxy.net", "https://ghproxy.net/", "prefix", 6.2, 240],
    ["hub.gitmirror.com", "https://hub.gitmirror.com/", "prefix", 5.1, 320],
    ["gh.llkk.cc", "https://gh.llkk.cc/", "prefix", 3.4, 410],
    ["ghproxy.cn", "https://ghproxy.cn/", "prefix", 0, 0],
    ["ghps.cc", "https://ghps.cc/", "prefix", 2.2, 530],
    ["github.moeyy.xyz", "https://github.moeyy.xyz/", "prefix", 4.6, 300],
    ["GitHub 直连", "", "direct", 0, 0],
  ].map(([name, base, mode, speed, latency]) => ({
    name, base, mode,
    enabled: true,
    latency, speed,
    fail: 0,
    checked: speed ? now - 600000 : 0,
  }));

  const mkAssets = (prefix, ver) => [
    { name: `${prefix}-${ver}-x64-setup.exe`, size: 91823456, updated: iso(now - 3600e3), url: "https://example.com/a.exe", downloads: 12, arch: "x64", vkey: `${prefix.toLowerCase()}-*-x64-setup.exe`, variant: "安装版" },
    { name: `${prefix}-${ver}-x64.zip`, size: 109723456, updated: iso(now - 3600e3), url: "https://example.com/b.zip", downloads: 8, arch: "x64", vkey: `${prefix.toLowerCase()}-*-x64.zip`, variant: "标准" },
  ];

  const latest = {
    tag: "v0.87.0",
    name: "PowerToys 0.87.0",
    body: "",
    published: iso(now - 3600e3),
    prerelease: false,
    draft: false,
    html_url: "",
    assets: mkAssets("PowerToys", "0.87.0"),
    newest: iso(now - 3600e3),
    newest_ms: now - 3600e3,
    size: 200546912,
  };

  const older = JSON.parse(JSON.stringify(latest));
  older.tag = "v0.86.0";
  older.name = "PowerToys 0.86.0";
  older.assets = mkAssets("PowerToys", "0.86.0");
  older.newest = iso(now - 8 * 86400e3);
  older.newest_ms = now - 8 * 86400e3;

  const boot = {
    version: "3.0.0",
    build: "mock",
    platform: new URLSearchParams(location.search).get("plat") || "windows",
    config: {
      download_root: "C:\\Users\\me\\Downloads\\LightMirror",
      concurrency: 6,
      threads: 16,
      segments: true,
      failover: true,
      failover_max: 5,
      resume: true,
      formats: ["exe", "msi", "zip", "rar", "pdf"],
      extra_formats: [],
      replace_rule: "replace",
      version_mode: 0,
      watch_sort: "updated",
      theme: "dark",
      probe_kb: 256,
      timeout: 8,
      history_max: 200,
      github_token: "",
      proxy: "",
      cache_ttl: 10,
      cache_on: true,
      last_repo: "",
      theme_mode: "auto",
    },
    mirrors,
    watch: [
      {
        repo: "microsoft/PowerToys",
        tag: "v0.86.0",
        name: "PowerToys 0.87.0",
        note: "主力工具 · PowerToys",
        dir: "",
        rule: "",
        variants: [],
        last_vkey: "",
        formats: [],
        tags_open: false,
        added: now - 86400e3,
        latest,
        checked: now - 120000,
      },
      {
        repo: "obsidianmd/obsidian-releases",
        tag: "",
        name: "",
        note: "",
        dir: "D:\\Installers\\Obsidian",
        rule: "rename",
        variants: ["安装版"],
        last_vkey: "",
        formats: ["exe"],
        tags_open: false,
        added: now - 86400e3 * 2,
        latest: {
          tag: "v1.8.2",
          name: "Obsidian 1.8.2",
          assets: [
            { name: "Obsidian-1.8.2.exe", size: 87 * 1048576, updated: iso(now - 86400e3), url: "https://example.com/o.exe", downloads: 3, arch: "x64", vkey: "obsidian-*.exe", variant: "安装版" },
          ],
          newest: iso(now - 86400e3),
          newest_ms: now - 86400e3,
          size: 87 * 1048576,
        },
        checked: now - 300000,
      },
    ],
    history: [
      { id: "h1", name: "Everything-1.4.1.1026.x64.zip", path: "C:\\Users\\me\\Downloads\\LightMirror\\Everything-1.4.1.1026.x64.zip", size: 18350080, ts: now - 900e3, mirror: "gh-proxy.com", repo: "", status: "done", error: "" },
      { id: "h2", name: "PowerToysUserSetup-0.86.0-x64.exe", path: "C:\\Users\\me\\Downloads\\LightMirror\\microsoft__PowerToys\\PowerToysUserSetup-0.86.0-x64.exe", size: 91823456, ts: now - 86400e3, mirror: "ghfast.top", repo: "microsoft/PowerToys", status: "done", error: "" },
      { id: "h3", name: "yt-dlp.exe", path: "C:\\Users\\me\\Downloads\\LightMirror\\yt-dlp.exe", size: 23 * 1048576, ts: now - 86400e3 * 2, mirror: "xget.xi-xu.me/gh", repo: "", status: "error", error: "下载中断，重试次数用完" },
    ],
    download_root: "C:\\Users\\me\\Downloads\\LightMirror",
    config_path: "C:\\Users\\me\\AppData\\Roaming\\LightMirror\\state.json",
    log_path: "C:\\LightMirror\\lightmirror.log",
    format_menu: [["exe", "EXE"], ["msi", "MSI"], ["zip", "ZIP"], ["rar", "RAR"], ["pdf", "PDF"], ["7z", "7Z"]],
    rules: [["replace", "直接替换"], ["rename", "自动改名"], ["skip", "跳过不下载"], ["ask", "每次询问"]],
    default_formats: ["exe", "msi", "zip", "rar", "pdf"],
    builtin: [["exe", "EXE"], ["msi", "MSI"], ["zip", "ZIP"], ["rar", "RAR"], ["pdf", "PDF"], ["7z", "7Z"]],
  };

  const qs = new URLSearchParams(location.search);
  if (qs.get("theme")) boot.config.theme = qs.get("theme");
  if (qs.get("plat")) boot.platform = qs.get("plat");

  const tasks = [
    {
      id: "t1", filename: "PowerToysUserSetup-0.87.0-x64.exe",
      path: "C:\\Users\\me\\Downloads\\LightMirror\\microsoft__PowerToys\\PowerToysUserSetup-0.87.0-x64.exe",
      repo: "microsoft/PowerToys", tag: "v0.87.0", status: "running",
      done: 137363456, total: 268435456, speed: 9.2, error: "",
      mirror: "ghfast.top", segments: 16, elapsed: 21, eta: 13, exists: false,
    },
    {
      id: "t2", filename: "Obsidian-1.8.2.exe",
      path: "D:\\Installers\\Obsidian\\Obsidian-1.8.2.exe",
      repo: "obsidianmd/obsidian-releases", tag: "v1.8.2", status: "paused",
      done: 17616077, total: 91226112, speed: 0, error: "",
      mirror: "xget.xi-xu.me/gh", segments: 8, elapsed: 9, eta: -1, exists: false,
    },
    {
      id: "t3", filename: "Everything-1.4.1.1026.x64.zip",
      path: "C:\\Users\\me\\Downloads\\LightMirror\\Everything-1.4.1.1026.x64.zip",
      repo: "", tag: "", status: "done",
      done: 18350080, total: 18350080, speed: 0, error: "",
      mirror: "gh-proxy.com", segments: 4, elapsed: 32, eta: 0, exists: true,
    },
  ];

  let cfg = boot.config;

  window.__MOCK__ = {
    tasks,
    releases: [latest, older],
    async call(action, args = {}) {
      await new Promise((r) => setTimeout(r, 30));
      if (action === "bootstrap") return JSON.parse(JSON.stringify(boot));
      if (action === "config.save") { cfg = args.config; return cfg; }
      if (action === "link.parse") {
        const t = (args.text || "").trim();
        if (/^[\w.\-]+\/[\w.\-]+$/.test(t)) {
          return { kind: "repo", owner: t.split("/")[0], repo: t.split("/")[1], filename: "", url: "https://github.com/" + t, tag: "", slug: t, label: "仓库地址" };
        }
        if (t.startsWith("http")) {
          const n = t.split("?")[0].split("/").pop();
          return { kind: "direct", owner: "", repo: "", filename: n, url: t, tag: "", slug: "", label: "普通直链" };
        }
        return null;
      }
      if (action === "releases.list") return JSON.parse(JSON.stringify([latest, older]));
      if (action === "watch.add") {
        const w = JSON.parse(JSON.stringify(boot.watch[0]));
        w.repo = args.repo;
        return w;
      }
      if (action === "speed.start") {
        setTimeout(() => {
          boot.mirrors.forEach((m, i) => {
            if (m.speed > 0) {
              window.__TAURI_MOCK_EMIT__ && window.__TAURI_MOCK_EMIT__("speed:result", { name: m.name, ok: true, latency: m.latency, speed: m.speed });
            }
          });
        }, 400);
        return { started: true };
      }
      if (action === "task.snapshot") return tasks;
      return { ok: true };
    },
  };
})();
