# 光速镜像 · GitHub 下载加速器

国内直连 GitHub 经常几十 KB/s、下到一半断流。这个工具把下载请求改走国内镜像：先给每个镜像发一小段数据测速，再只从最快的那条链路下载。支持多任务并发、单文件多线程、断点续传，另带一个「版本追踪」页，盯着项目的 release 只下你要的格式。

## 三个版本

| 文件 | 端 | 体积 | 说明 |
| --- | --- | --- | --- |
| `release/LightMirror-2.0.6.apk` | 安卓 | 176 KB | 原生 Java，minSdk 29 / targetSdk 36，装完直接用 |
| `release/LightMirror-Desktop-2.4.3.exe` | 电脑 | 35 MB | Python + PySide6 单文件，双击即用 |
| `release/LightMirror-Desktop-3.0.0-rust.exe` | 电脑 | 5.4 MB | Rust + Tauri 便携版，免安装 |

三端功能一致，版本号各自独立维护。仓库的 `release/` 和 GitHub 发布页各放一份，任取其一。

## 功能

### 下载

- 粘贴链接就能下：GitHub 文件直链、`releases/download/...`、仓库地址（自动解析成最新 release）都认。
- **先测速再下**：点「测速并下载」会先把启用的镜像跑一遍，测完按排名从最快的开始下。
- **多任务并发**：同时下几个文件可调（安卓默认 3、电脑默认 6，上限 10）。
- **单文件多线程**：按 `Range` 切成若干段并行拉，安卓默认 10 段、电脑默认 16 段，可关掉退回单连接。
- **断点续传**：分片落在 `<文件名>.part.parts/`，暂停、换镜像、关掉程序再开都能接着下；全部到齐才拼成 `.part` 并改名。
- **失败自动换镜像**：按测速排名顺延重试；镜像不支持 `Range` 时自动退回单线程，不会下坏文件。
- 同名文件可选直接替换 / 自动改名（`名字 (2)`）/ 跳过。

### 版本追踪

- 填 `owner/repo`、仓库链接或 releases 链接都能进；首页粘仓库地址会直接跳过来。
- **格式范围**在设置里统一勾选（可加任意自定义扩展名、可删），每个项目也能单独改，只列该项目最新版真实存在的格式。
- **标签区分差异**：同一版本里的多个文件会算出真正的不同点，显示成「ARM64 · APK」「便携版 · EXE」「x86_64 · APK」这样的标签；标签可多选，选中几个就只看这几个。
- **单个下载 / 批量勾选**两种模式：单个是点一行立刻下；批量是勾完一次性测速、按并发一起开跑，重复的自动跳过。
- 自动比对版本：没下过 → 可以下载；下过同版本 → 已是最新；版本变了 → 提示从哪个版本到哪个版本。
- 已下载过的文件在列表里标灰，「查看版本」可翻最近 15 个 release 任选一个下。

### 镜像与测速

- 内置 10 个镜像（`ghfast.top`、`xget.xi-xu.me/gh`、`gh-proxy.com`、`ghproxy.net`、`hub.gitmirror.com` 等），另有「GitHub 直连」兜底。
- 镜像页可**拖动排序**、开关、删除、恢复内置；自定义镜像能选拼接方式：整条链接前缀，或只拼 GitHub 路径。
- 测速方式是给每个镜像发一小段 `Range` 请求，量首字节时间和吞吐；评分 = 速度 70% + 延迟 30%。
- 测速分片大小（32–4096 KB）、单镜像超时（3–60 秒）都能调；测速结果可复用一个周期，也可以关掉每次重测。

### 记录与设置

- **下载记录**：完成 / 失败 / 取消都留一条，可多选删除，删除时可选「只删记录」或「连文件一起删」；显示条数可调。
- **设置**：并发数、线程数、分片大小、超时、保存位置、同名规则、下载格式、版本页模式、GitHub Token、代理、日志查看。
- 安卓端保存位置可选系统「下载」目录、应用私有目录或自定义路径；APK 下完可直接拉起安装。

### 主题与界面

- 三套主题：**深空**（深色）、**纸白**（浅色）、**黑白**（墨水屏），可跟随系统自动切换。
- 安卓端针对 4:3 墨水屏单独适配：字号加大、描边改实色、去渐变与动画、进度按整数百分比节流刷新，避免残影。
- 电脑端宽屏用左侧导航，窗口变窄自动切成底部页签，功能不变。

## 目录结构

```
app-source/    安卓源码（Java）+ build-apk.sh，不需要 Gradle
desktop/       Python 版源码、截图、build_exe.bat
prototype/     高保真原型（浏览器直接打开）
rust/          Rust + Tauri 版源码（ui/ 前端 + src-tauri/ 后端），只出 Windows 桌面版
release/       三个成品
build.sh       一键构建脚本
```

## 同一个仓库里还有

`wxalbum-*` 是另一个小工具：把公众号「合集」链接里的图文批量抓下来，合成一本带封面和目录的 EPUB（微信读书 / Apple Books / Kindle 都能读）。

| 目录 | 说明 |
| --- | --- |
| `wxalbum-desktop/` | 电脑端，纯 Python 标准库，启动后在浏览器里操作 |
| `wxalbum-android/` | 安卓端，WebView 套壳复用同一套逻辑 |
| `wxalbum-native/` | 安卓原生版，纯 Java 实现，额外做了墨水屏适配 |

## 构建

```bash
./build.sh          # 安卓 APK + Rust 桌面 exe，产物落到 release/
./build.sh apk      # 只出 APK（需要 ANDROID_HOME 指向 Android SDK）
./build.sh exe      # 只出 Rust 桌面 exe（需要 Node.js 20+ 与 Rust）
./build.sh py       # 只出 Python 桌面 exe（需要 Python 3 + PyInstaller）
```

Windows 上双击 `build.bat`，没配 Android SDK 时会自动只构建桌面 exe。打 tag 会触发 GitHub Actions，自动构建并发布三个成品。

版本号只在一处维护：安卓读 `app-source/AndroidManifest.xml` 的 `versionName`，Rust 读 `rust/src-tauri/tauri.conf.json` 的 `version`，Python 读 `desktop/lightmirror.py` 的 `APP_VERSION`。

APK 用固定 keystore（`app-source/keystore/lightmirror.jks`）签名，新版本可以直接覆盖安装。

## 已知限制

- 安卓端没有前台服务，App 被系统回收时下载会断（`.part` 还在，重进可续）。
- 自签名证书，安装需允许「未知来源」。
- 版本追踪只读公开仓库，私有仓库返回 404；未登录访问 GitHub API 限 60 次/小时。
- 公共镜像的额度由所有人共享，偶尔会集体限流（Cloudflare 1027 / HTTP 429），换一个镜像或等额度重置即可。

---

逐版本的改动记录见 [`CHANGELOG.md`](CHANGELOG.md)。
