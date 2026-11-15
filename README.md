# 公众号合集 → EPUB

把微信公众号「合集」链接里的图文批量抓下来，合成一本带封面和目录的 EPUB，微信读书、Apple Books、腾讯文档、Kindle 都能直接读。

本仓库的 `wxalbum` 分支专放这个工具；`main` 分支是另一个项目「光速镜像」（GitHub 下载加速器）。

## 三个版本

| 目录 | 形态 | 说明 |
| --- | --- | --- |
| `wxalbum-desktop/` | 电脑 | 纯 Python 标准库，启动后在浏览器里操作；也可命令行跑 |
| `wxalbum-android/` | 安卓 | WebView 套壳 + Chaquopy，复用同一套 Python 逻辑（`com.wxalbum.app`） |
| `wxalbum-native/` | 安卓 | 纯 Java 重写，不打包 Python 运行时，体积小（`com.wxalbum.nativeapp`） |

两个安卓包名不同，可以同时安装。

## 用法

电脑端：

```bash
cd wxalbum-desktop
python app.py                 # 打开 http://127.0.0.1:8765 的界面
python app.py --cli <合集链接>  # 命令行直接生成
```

常用参数：`--limit N` 只抓前 N 篇、`--delay 0.8` 每篇间隔秒数、`--no-images` 不下载插图、
`--out 目录` 输出位置、`--title` / `--author` 自定义书名与作者。

只依赖 Python 标准库；装了 Pillow 会额外生成封面并压缩插图。

## 构建

两个安卓工程都是 Gradle 工程（JDK 17 + Android SDK 34）：

```bash
cd wxalbum-android && gradle assembleDebug     # 调试包，产物在 app/build/outputs/apk/
cd wxalbum-native  && gradle assembleRelease   # 发布包，需要签名证书
```

签名证书从环境变量或 `keystore/wxalbum.p12` 读取（`WXALBUM_KEYSTORE_PASSWORD` 提供密码）。

## 自动构建

推到本分支且改动落在 `wxalbum-android/**` 或 `wxalbum-native/**` 时，GitHub Actions 会自动打包，
产物发到 Release：`wxalbum-apk-latest`（WebView 版）、`wxalbum-native-latest`（原生版）。
编译失败时日志会推到 `ci-logs` 分支。
