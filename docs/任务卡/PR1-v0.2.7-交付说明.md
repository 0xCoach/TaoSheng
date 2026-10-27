# PR #1 / v0.2.7 正式版交付

日期：2026-10-27。

## 改动

- 在贡献者 PR #1 的原始提交 97d5077 上追加修复，保留作者和协作历史，并合入现有 main（4e6c54a）。
- 将官方 onnxruntime-android 从 1.23.2 升级至 1.25.1，移除旧版本二进制补丁钩子和 scripts/patch_onnxruntime_sme2.py。
- 应用版本 0.2.7 / versionCode 21，包名 app.resound；数据库、模型、应用业务代码未改动。
- 中英文 README 更新下载地址、兼容性说明和贡献致谢；感谢 @ethansunqing 的定位、初始补丁和 Find N6 复测，感谢 @brookDeng 的小米 pandora 独立分析和复测。

## 验证

- 与公开测试标签 v0.2.6-ort1.25.1-test.1 比较，app/src 没有差异，app/build.gradle.kts 仅改变 versionCode / versionName。
- 复用同代码测试阶段的 33 项相关单测及 macOS CPU 模型对照结果，不重复运行未变化的测试。原始结果及首次离线依赖失败的处理说明见该测试标签的 docs/任务卡/ORT1251-交付说明.md 和 tools/ort-validation/results/。这些结果不等于 Android ARM 真机验证。
- 本轮在整合后的分支重新构建 assembleRelease，含 lintVitalRelease，通过（1 分 13 秒）。已有弃用提示不影响构建。
- APK ZIP 完整性、arm64-v8a ABI、app.resound 包名、0.2.7 / 21 版本检查通过。
- 正式签名与 0.2.6 相同；两项 ORT 原生库与官方 1.25.1 AAR 逐字节一致；全部 10 个原生库和 2 个模型与双方复测的测试 APK 逐字节一致，另 8 个原生库和 2 个模型与旧正式版也一致。
- 用户已反馈测试版使用正常；@ethansunqing 在 Find N6 上报告聆听、转写、整理、声纹匹配正常、数据保留；@brookDeng 在小米 pandora（25098PN5AC）上报告覆盖安装、数据保留、前后台聆听正常，无 SIGILL。这些是真机使用者反馈，本次没有连接手机再次安装正式包。

## 产物

- 文件：TaoSheng-v0.2.7-arm64.apk
- 大小：94913212 字节
- SHA-256：`a6187852873df0ed92c96ee7dc7d96ba97e7c2d281c1cb2b6f45aec0d3b8c60e`
- 证书 SHA-256：`7c11f9afc2cdfc8ba334a7bba69b005aecc609d81fcd12d01bcec2752f496b07`
- 计划发布地址：https://github.com/0xCoach/TaoSheng/releases/tag/v0.2.7

## 安装与边界

从旧正式版或 ORT 测试版直接覆盖安装；建议先在应用内备份，不需要卸载。不要通过卸载解决签名冲突。
本次处理的是已验证的 ORT / SIGILL 兼容问题，不代表所有机型、功耗或其他新反馈均已验证解决。偶发的大模型输出格式问题不在本次改动范围内。
