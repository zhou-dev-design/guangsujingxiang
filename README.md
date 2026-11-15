# 公众号合集 → EPUB

把微信公众号「合集」里的图文批量抓下来、清洗干净，合成一本带封面、书名页、书籍信息扉页与目录的 EPUB3。微信读书、Apple Books、腾讯文档、Kindle 都能直接打开。

- 纯本地运行，不上传任何抓取内容
- 桌面端：Python 标准库 + 浏览器控制台
- 安卓端：纯 Java 原生实现，不打包 Python 运行时
- 连载合集可追踪更新，增量抓取后原位替换

> 本仓库的 `wxalbum` 分支专放这个工具；`main` 分支是另一个项目「光速镜像」。

## 三个版本

| 目录 / 产物 | 平台 | 说明 | 状态 |
| --- | --- | --- | --- |
| `wxalbum-desktop/` | Windows / macOS / Linux | Python 3.9+，浏览器界面，也可命令行 | 当前主维护 |
| `wxalbum-native/` → **v1.3** | Android 7.0+（API 24） | 纯 Java，APK ≈ 100 KB，包名 `com.wxalbum.nativeapp` | **推荐** |
| `wxalbum-android/` → v1.5 | Android 10+（API 29） | WebView + Chaquopy，APK ≈ 11 MB，包名 `com.wxalbum.app` | 旧版，保留 |

两个安卓包名不同，可以**同时安装**；版本号各自独立，原生版 1.3 比旧版 1.5 更新。

直接下载 APK（GitHub Release）：

- 原生版：https://github.com/zhou-dev-design/guangsujingxiang/releases/download/wxalbum-native-latest/wxalbum-native.apk
- 旧版：https://github.com/zhou-dev-design/guangsujingxiang/releases/download/wxalbum-apk-latest/app-debug.apk

## 功能

**抓取与清洗**

- 粘贴合集链接，或粘贴**任意一篇文章**自动反查它所属的合集
- 走 `getalbum` 接口翻页取完整目录，发布时间精确到分
- 自动剔除微信浮层提示、PS 推广段、编辑署名、文末书名、首尾交叉标题、模板头图
- 标题来源可选「自动识别 / 正文第一行 / 文章标题」，可给章节加序号
- 插图下载默认关、书籍信息扉页默认开，均可切换

**EPUB**

- 结构：封面 → 书名页 → 书籍信息扉页 → 正文，附 `nav.xhtml` 与 `toc.ncx` 目录
- 扉页含书名 / 作者 / 字数 / 章节数 / 书籍 ID / 合集链接 / 首篇与最新篇时间
- 并发抓取（桌面 1–50，推荐 4–8；安卓 1–10）、章节缓存、每篇间隔可调
- 原位替换安全策略：先写临时文件并自检，**生成成功不留旧版，生成失败则原书分毫不动**

**管理**

- 书架：记录合集章节 key，检查更新 → 只抓新增章节 → 整本重打包覆盖原文件
- 历史：多选后「只删记录 / 只删文件 / 两者都删」
- 设置：保存位置、并发数、默认选项、主题

**墨水屏（安卓）**

- 严格 1bit：**只有纯黑与纯白**，主题、图标、禁用态、对话框、选中态都没有灰阶或半透明；选中态靠反白区分
- 「墨水屏 6″」专用模式：针对 1072×1448 / 300dpi / 6 英寸设备放大字号与点击区、2dp 黑描边、降低刷新频率；识别到该类设备时普通「墨水屏」会自动套用专用布局
- 五个主题：深色 / 浅色 / 墨水屏 / 墨水屏 6″ / 自动

## 快速开始 · 电脑端

需要 Python 3.9+，只依赖标准库；装了 Pillow 会额外生成封面并压缩插图。

```bash
cd wxalbum-desktop
python app.py                 # 自动打开 http://127.0.0.1:8765
```

Windows 也可以双击 `start.bat`。重复启动会复用已有实例，不会开出第二个服务。

命令行直接生成：

```bash
python app.py --cli "https://mp.weixin.qq.com/s/..." --out D:\Books --title "书名"
```

| 参数 | 作用 |
| --- | --- |
| `--cli URL` | 命令行模式，抓取合集或文章链接 |
| `--limit N` | 只抓前 N 篇（调试用） |
| `--delay 秒` | 每篇间隔，默认 `0.8` |
| `--no-images` | 不下载正文插图 |
| `--no-expand` | 文章链接不自动展开成整本合集 |
| `--no-bookinfo` | 不生成书籍信息扉页 |
| `--out 目录` | 输出位置 |
| `--title` / `--author` | 自定义书名与作者 |
| `--port N` / `--no-browser` | 指定端口 / 不自动开浏览器 |

界面有四个标签页：**下载 / 书架 / 历史 / 设置**。保存位置在「设置 → 保存位置」，可以点「浏览…」选目录，也能直接粘贴路径后回车或点「应用」；会自动创建目录并校验可写性，非法路径不会破坏原有设置。改动即时生效，无需重启。

首次运行会在 `wxalbum-desktop/` 下生成这些**运行时数据（不入库）**：

| 路径 | 内容 |
| --- | --- |
| `config.json` | 保存位置、并发数 |
| `watch.json` | 书架（合集与已抓章节 key） |
| `history.json` | 生成历史 |
| `cache/` | 章节正文缓存，删掉不影响成品书，只是增量更新要重新联网抓 |
| `output/` | 生成的 EPUB |

`assets/app_icon.ico` 与 `make_icon.py` 是应用图标及其生成脚本（需 Pillow）。

## 快速开始 · 安卓端

安装 APK 后是底部四导航：下载 / 书架 / 历史 / 设置。

- **保存位置**：设置 → 「更改」用系统目录选择器授权；生成完成后点「导出」写入该目录。未设置时导出到系统「下载 / 公众号EPUB」。设置页会显示当前目录名，点它可看完整路径。
- **并发**：1–10，建议 4–8，过高容易被微信限流。
- **主题**：见上文墨水屏说明。
- **书架**：点「更新」检查并增量下载新章节，覆盖原文件。

## 目录结构

```
.github/workflows/        两条 CI：原生版 / 旧版 APK
wxalbum-desktop/          电脑端
  app.py                  后端：抓取、清洗、EPUB、HTTP 服务
  web/                    前端三件套 + legacy.html
  assets/                 应用图标与生成脚本
  _test/                  安全替换 / 保存位置回归测试
  start.bat / validate.py  Windows 启动脚本 / EPUB 校验
wxalbum-native/           安卓原生版（纯 Java）
  app/src/main/java/      Fetcher、Cleaner、EpubBuilder、Engine、Ui …
  app/src/main/res/       主题样式（含 1bit 墨水屏）、布局、图标
  _test/                  替换逻辑、设备识别、纯黑白静态校验
wxalbum-android/          安卓旧版（WebView + Chaquopy）
```

## 构建

两个安卓工程都是 Gradle 工程，需要 **JDK 17 + Android SDK 34 + Gradle 8.7**：

```bash
cd wxalbum-native  && gradle assembleRelease   # 推荐版本
cd wxalbum-android && gradle assembleDebug     # 旧版
```

产物在各自 `app/build/outputs/apk/`。

**签名**：证书是 PKCS12 文件 `wxalbum-native/keystore/wxalbum.p12`，已被 `.gitignore` 排除，仓库里没有；密码由环境变量 `WXALBUM_KEYSTORE_PASSWORD` 提供。CI 不依赖本地文件，而是在仓库 Settings → Secrets 里配置：

| Secret | 内容 |
| --- | --- |
| `KEYSTORE_BASE64` | 证书文件的 base64 编码 |
| `KEYSTORE_PASSWORD` | 证书密码 |

两个 workflow 用同一张证书，SHA-256 为
`0A:D1:5B:B7:60:2B:31:17:58:28:13:52:48:C1:6F:25:05:96:CF:04:A3:27:CD:8D:DB:54:EE:EC:91:B6:03:0E`。
覆盖安装新版本必须使用同一张证书，否则系统拒绝安装。

## 自动构建

| Workflow | 触发路径 | Release tag | 资产 |
| --- | --- | --- | --- |
| `wxalbum-native.yml` | `wxalbum-native/**` | `wxalbum-native-latest` | `wxalbum-native.apk` |
| `wxalbum-apk.yml` | `wxalbum-android/**` | `wxalbum-apk-latest` | `app-debug.apk` |

只有推送到 **`wxalbum` 分支**才会发布 Release；也支持在 Actions 页面手动 `workflow_dispatch`。编译失败时日志会推到 `ci-logs` 分支。桌面端改动**不会**触发 APK 构建。

## 测试

```bash
# 桌面端：安全替换、保存位置、build_epub 端到端
cd wxalbum-desktop && python -m unittest discover -s _test -v

# 安卓端：1bit 主题静态校验（纯 Python）
cd wxalbum-native && python _test/check_1bit.py
```

安卓端另有 `TestReplace.java`（替换逻辑 14 项）和 `TestEink6.java`（6 英寸设备识别 8 项），可配合 JDK 与 `android.jar` 单独编译运行。

## 说明与注意

- 抓取是 IO 密集型，瓶颈在微信限流而非 CPU；线程拉满反而更慢，桌面推荐 4–8、手机 4–8。
- 只对公开可见内容做本地处理；生成的 EPUB 版权归原作者，请仅用于个人阅读。
- 更新书籍时若新版本生成出问题（体积异常、缺 `mimetype`、章节数不足、ZIP 损坏），旧文件会原样保留并给出提示；成功替换则不产生历史副本。
- 桌面端修改源码后需重启服务；只改设置项则即时生效。
