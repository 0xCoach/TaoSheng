# Sonicloud 普通款 A：BLE 输入与文件导入

支持单设备扫描、连接、已保存卡自动扫描后重连；通过现有 AudioSource 输出 PCM，卡内文件经完整性检查后进入既有导入确认与转写队列。录音卡仅连接后出现在输入源，失联等待录音卡，不自动切回手机。手机、系统 USB/耳机输入沿用原策略。

契约新增 InputChoice.Recorder、InputKind.Recorder、CaptureState.SourceUnavailable；AppGraph、采集服务、通知、快捷入口与所有界面 when 调用方一并适配，保持编译完整。提供最小录音卡管理入口和 BLE 文件列表，供维护者审核 UI/接口边界；尚未取得维护者接纳硬件扩展的意见。无数据库迁移，不改应用版本、包名或签名。文件清理、日期分页及 Wi-Fi 不包含在本 PR。

SDK 来自厂商公开仓库固定提交，Gradle 下载并校验大小及 SHA-256；无需私人路径或 init 脚本，来源和许可见 app/libs/sonicloud/README.md。新增依赖显式列在 app/build.gradle.kts，首次构建需要网络。

验证：隔离工作区 JDK 21 执行 :app:assembleDebug :app:testDebugUnitTest --tests app.resound.sonicloud.* --tests *Input045Test，通过 27 项测试（输入路由 8、协议/导入 11、重连 3、目录 2、输入 UI 3），APK 构建成功。实际通过公开 URL 获取 SDK，未使用本机 AAR/init 脚本；合并 Manifest 已核对没有 Wi-Fi/存储权限及厂商演示 Activity。Windows 暂通过本机 JDK 参数覆盖上游的 Homebrew 路径，通用修复见独立 PR #13。用户反馈整合版本在本人手机上基本验证通过；拆分后的 APK 未重新安装，固件与手机型号、PCM 长时音质、权限撤销、无卡启动及断连恢复仍需补充设备记录。自动测试不能替代真实硬件验收。

