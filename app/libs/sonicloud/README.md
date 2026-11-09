# Sonicloud Android SDK

普通款 A；固定制品 `pnote_20260827161403.aar`，193310 字节，SHA-256：
`1fb794ac2631aa2278eca54ea5c6c3afec8efd7818a679398c4bbc4501f8f49e`。

[厂商公开源码与制品](https://github.com/SonicloudTech/sonicloud_opensdk/tree/fa63976f3fe37c889fd084acc75bd66b87e2c83a/pnote-android-sdk)固定在提交 `fa63976f3fe37c889fd084acc75bd66b87e2c83a`，不跟随 master 更新。Gradle 的 prepareSonicloudSdk 首次构建下载至模块 build/sonicloud，验证大小和 SHA-256 后才交给 Android 插件；缓存被修改也会重新验证。无需外部本机目录或 init 脚本。下载需要访问 raw.githubusercontent.com。

本目录 LICENSE.txt 原样保留[同一厂商提交的 MIT 许可](https://github.com/SonicloudTech/sonicloud_opensdk/blob/fa63976f3fe37c889fd084acc75bd66b87e2c83a/LICENSE)。依赖沿用厂商 Android 示例，显式固定于 app/build.gradle.kts；它们保留各自许可。SDK 不提供 Opus 解码，应用通过 Android MediaCodec 解码。保持既有 OkHttp 4.12.0，不启用 SDK 调试日志；合并 Manifest 移除演示 Activity 与无关存储权限。

[PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
