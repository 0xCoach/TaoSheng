---
license: other
tags:
- android
- personal-memory
---

# 涛声 TaoSheng · Android 下载

**替你听，替你记，替你想。**

一部安卓手机就够：全天收音 → 自动转写 → 认出谁在说 → AI 整理成待办、约定和记忆。不用买硬件，不用注册账号，数据只在你自己手机里。

本仓库提供国内安装包镜像及 App 更新文件。源代码见 [GitHub](https://github.com/0xCoach/TaoSheng)，官网：[www.taosheng.app](https://www.taosheng.app)。

许可：自 0.4.0 起源码按 [FSL-1.1-MIT](https://github.com/0xCoach/TaoSheng/blob/main/LICENSE.md) 公开（个人与内部使用不受影响，每个版本两年后自动转为 MIT）；0.3.1 及之前为 MIT。

## 最新正式版：$version

发布时间：$date。版本号：$code。支持 Android 10 及以上，arm64-v8a。

- [点击直接下载 $version 正式版 APK](https://modelscope.cn/models/OpenRecode/TaoSheng-releases/resolve/master/$apk)
- [GitHub 正式发布](https://github.com/0xCoach/TaoSheng/releases/tag/v$version)
- [完整更新日志](https://modelscope.cn/models/OpenRecode/TaoSheng-releases/resolve/master/CHANGELOG.md)
- [SHA-256 校验清单](https://modelscope.cn/models/OpenRecode/TaoSheng-releases/resolve/master/SHA256SUMS)

$notes

安装包大小：$size 字节。SHA-256：

```text
$sha256
```

正式签名证书指纹（SHA-256）：`7c11f9afc2cdfc8ba334a7bba69b005aecc609d81fcd12d01bcec2752f496b07`。

可使用 App 内检查更新或下载 APK 覆盖安装。App 自动选择匹配的差异包，没有匹配时下载整包，无需手动处理 `.patch` 文件。从 0.4.0 起，App 发现新版本会在首页直接提示。

正式更新清单为 `update.json`，预览清单为 `preview/update-preview.json`。
