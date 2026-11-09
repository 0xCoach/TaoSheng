# sonicloud 测试

RecorderTest.kt：输入不回退、分帧、暂停恢复、断点下载与幂等交接。
RecorderConnectionTest.kt：真实控制器等待 SDK 绑定、自动扫描重连、失败后重试。
RecorderFileListTest.kt：目录合法性、录音时间解析、日期分组及分页收缩。
RecorderDeletionTest.kt：逐文件复核、失败停止、超时和停止剩余操作。
RecorderFilesUiTest.kt：删除确认、跨页选择及录音中禁删。
RecorderInputUiTest.kt：设备状态、电量、连接可见性与传输/清理时禁止切入。
RecorderWifiTest.kt：TCP 就绪、取消隔离、路由恢复及故障释放。
RecorderWifiUiTest.kt：快传入口、连接中取消和就绪提示。

这些测试不连接硬件。设备协议与音质需真机验证；实现地图见 app/src/main/java/app/resound/sonicloud/CLAUDE.md。

[PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
