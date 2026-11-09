# sonicloud/

普通款 A、单设备；只有本包调用 PNote。通过 RecorderAudioSource 向既有采集链路输出 PCM，通过 RecorderTransfer 将完整文件交给 AndroidAudioImporter，不创建第二条转写管线。

RecorderContract.kt：设备、目录与传输状态的界面契约。
SonicloudRecorder.kt：串行控制与回调处理，失联保持录音卡选择，自动扫描后重连。
RecorderSdkBinding.kt：等待厂商服务和监听器就绪，失败允许重试。
RecorderService.kt：connectedDevice 前台连接生命周期。
RecorderPermissions.kt：界面与服务共用按需 BLE / Wi-Fi 权限。
RecorderWifi.kt：热点请求、系统连接与 TCP 就绪状态机；失败释放网络后中止下载。
AndroidRecorderWifiNetwork.kt：精确 SSID 请求，仅 TCP 建连时绑定进程，随后恢复原路由。
RecorderAudioSource.kt：码流订阅与 PCM 输出，取消释放解码器。
RecorderOpus.kt：实时和离线共用的普通款 Opus 分帧与解码。
RecorderTransfer.kt：私有目录续传、完整性检查与幂等导入。
RecorderFileList.kt：目录分片校验、日期/星期分组和每页 40 条的展示切片。
RecorderDeletion.kt：逐文件删除回执后重读目录复核，失败停止并要求重连。

调用方与契约改动见 docs/任务卡/sonicloud-ble-交付说明.md；文件管理见 docs/任务卡/sonicloud-file-management-交付说明.md；父项目架构见根 README.md。

[PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
