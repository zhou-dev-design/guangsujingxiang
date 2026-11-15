# 公众号合集 → EPUB

把微信公众号「合集」批量抓下来、清洗干净，合成带封面、书名页、书籍信息扉页与目录的 EPUB3。纯本地运行，不上传任何抓取内容。

> 本分支 `wechat-epub-downloader` 专放这个工具；`main` 分支是另一个项目「光速镜像」。

## 下载与安装

| 版本 | 平台 | 说明 |
| --- | --- | --- |
| [原生版 v1.3 APK](https://github.com/zhou-dev-design/guangsujingxiang/releases/download/wxalbum-native-latest/wxalbum-native.apk) | Android 7.0+ | **推荐**，纯 Java，≈100 KB，包名 `com.wxalbum.nativeapp` |
| [旧版 APK](https://github.com/zhou-dev-design/guangsujingxiang/releases/download/wxalbum-apk-latest/app-debug.apk) | Android 10+ | WebView + Chaquopy，≈11 MB，包名 `com.wxalbum.app`，可与原生版共存 |
| 桌面端 | Win / macOS / Linux | 源码 `wxalbum-desktop/`，Python 3.9+，浏览器界面 |

两个安卓版本号各自独立；覆盖安装必须使用同一张证书（见「构建与签名」）。

## 快速开始

桌面端只依赖标准库，装了 Pillow 会额外生成封面并压缩插图：

```bash
cd wxalbum-desktop
python app.py                 # 自动打开 http://127.0.0.1:8765
python app.py --cli "https://mp.weixin.qq.com/s/..." --out D:\Books --title "书名"
python app.py --help          # 全部参数：--limit / --delay / --no-images / --no-expand / --no-bookinfo …
```

Windows 也可双击 `start.bat`；重复启动会复用已有实例。界面四个标签页：下载 / 书架 / 历史 / 设置。保存位置在「设置 → 保存位置」，可浏览选择或直接粘贴路径，自动建目录并校验可写性，改动即时生效。

安卓端装好 APK 后是底部四导航；在设置里用系统目录选择器授权保存位置，生成完点「导出」写入。并发 1–10，建议 4–8。

运行时数据（不入库）都在 `wxalbum-desktop/` 下：`config.json`、`watch.json`、`history.json`、`cache/`、`output/`。删掉 `cache/` 不影响成品书，只是增量更新要重新联网抓。

## 功能

- **抓取**：合集链接，或粘贴任意一篇文章自动反查所属合集；走 `getalbum` 接口翻页取完整目录，发布时间精确到分
- **清洗**：剔除微信浮层提示、PS 推广段、编辑署名、文末书名、首尾交叉标题、模板头图；标题来源可选「自动识别 / 正文第一行 / 文章标题」，可加序号
- **EPUB**：封面 → 书名页 → 书籍信息扉页（书名 / 作者 / 字数 / 章节数 / 书籍 ID / 合集链接 / 首篇与最新篇时间）→ 正文，含 `nav.xhtml` 与 `toc.ncx` 目录
- **选项**：插图默认关、扉页默认开；并发（桌面 1–50，安卓 1–10）、章节缓存、每篇间隔可调
- **连载追踪**：书架检查更新 → 只抓新增章节 → 整本重打包并原位替换；**生成成功不留旧版，生成失败则原书分毫不动**（先写临时文件并自检体积、`mimetype`、`content.opf`、章节数、CRC）
- **墨水屏**：严格 1bit，只有纯黑与纯白，选中态靠反白区分；「墨水屏 6″」专用模式针对 1072×1448 / 300dpi / 6 英寸设备放大字号与点击区、降低刷新频率，识别到该类设备会自动套用
- **历史**：多选后「只删记录 / 只删文件 / 两者都删」

## 目录结构

```
.github/workflows/   两条 CI（原生版 / 旧版 APK）
wxalbum-desktop/     app.py 后端 · web/ 前端 · assets/ 图标 · _test/ 回归测试 · start.bat · validate.py
wxalbum-native/      纯 Java 源码（Fetcher/Cleaner/EpubBuilder/Engine/Ui）+ res/ 主题 + _test/
wxalbum-android/     旧版 WebView + Chaquopy
```

## 构建与签名

需要 JDK 17 + Android SDK 34 + Gradle 8.7：

```bash
cd wxalbum-native  && gradle assembleRelease   # 推荐版本
cd wxalbum-android && gradle assembleDebug     # 旧版
```

签名证书 `*.p12` 不入库。CI 从仓库 Secrets 的 `KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` 还原同一张证书，SHA-256 为
`0A:D1:5B:B7:60:2B:31:17:58:28:13:52:48:C1:6F:25:05:96:CF:04:A3:27:CD:8D:DB:54:EE:EC:91:B6:03:0E`。
换证书会导致系统拒绝覆盖安装。

## 自动构建

| Workflow | 触发路径 | Release tag | 资产 |
| --- | --- | --- | --- |
| `wxalbum-native.yml` | `wxalbum-native/**` | `wxalbum-native-latest` | `wxalbum-native.apk` |
| `wxalbum-apk.yml` | `wxalbum-android/**` | `wxalbum-apk-latest` | `app-debug.apk` |

只有推送到 **`wechat-epub-downloader`** 分支才会构建并发布，也支持 Actions 页面手动 `workflow_dispatch`；桌面端改动不会触发 APK 构建。两条 workflow 都设了 `make_latest: false`。

GitHub 的 Release 属于仓库、绑在 tag 上，所以与 `main` 的发布同列一个页面：`main` 发版只清理自己的 `v*` 旧 Release，本分支只操作 `wxalbum-*` 两个 tag，两边互不干扰。要彻底分成两个 Releases 页面只能拆仓库。

## 测试

```bash
cd wxalbum-desktop && python -m unittest discover -s _test -v
cd wxalbum-native  && python _test/check_1bit.py
```

安卓端另有 `TestReplace.java`（替换逻辑 14 项）与 `TestEink6.java`（6 英寸设备识别 8 项），需 JDK + `android.jar` 单独编译运行。

## 注意

- 瓶颈是微信限流而非 CPU，线程拉满反而更慢；桌面与手机都推荐 4–8。
- 只对公开可见内容做本地处理，生成 EPUB 的版权归原作者，请仅用于个人阅读。
- 修改桌面端源码后需重启服务；只改设置项即时生效。
