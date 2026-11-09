# 录音卡 Wi-Fi 文件快传（草稿）

依赖 BLE 接入 #14 和文件管理 #15。使用同一 RecorderTransfer 续传与导入链路，Wi-Fi 仅用于停止录音后的文件下载，BLE 继续负责控制。与录音、清理互斥。

连接分为开启卡热点、系统确认加入精确 SSID、SDK TCP 建连三个阶段；仅收到 TCP 就绪事件才允许快传，其他手机加入热点不算就绪。按需申请 Wi-Fi 权限，取消、超时、断连和异常时释放网络。SDK 缺少 SocketFactory 注入，因此只在 TCP 建连期间临时绑定进程，TCP 就绪后恢复原路由；使用固定版 SDK 内部 LongSocketClient 禁止后台重连，升级 SDK 时需复核兼容性。

验证：Windows/JDK 21，assembleDebug 成功；RecorderWifiTest、RecorderWifiUiTest、RecorderInputUiTest、RecorderFilesUiTest、RecorderConnectionTest 定向测试全部通过，构建与测试耗时 1 分 28 秒。两路静态审查未发现拆分遗漏或新增阻断项。用户说整合版本功能基本验证通过，但没有单独确认短/大文件都实际走 TCP 并完整播放/转写，也未提供 VPN 对照；这些不能视作本 PR 已完成的真机验收。保持 Draft。

真机仍需确认：热点授权与 Android 权限分支、短/大文件完整性、取消后续传、断网恢复和 BLE 回退；VPN 开/关或应用排除对照应由用户知情操作。不会静默关闭 VPN。系统/VPN 可能拒绝 bindProcessToNetwork；进程临时绑定期间其他新建云端连接可能受影响，应先结束快传再重试云端请求。状态机测试不能替代上述验证。
