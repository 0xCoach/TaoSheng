# 录音卡文件日期分页与逐项清理

依赖录音卡 BLE 基础接入 PR #14。目录按日期和星期显示，支持日期筛选、每页 40 条和跨页选择。SDK 仍读取完整目录，分页只减少界面渲染量，不减少蓝牙传输量。

删除前确认设备与文件快照，要求停止录音/下载并拿到完整目录；逐个发送删除命令，每次回执后重读完整目录确认文件消失才继续。失败、断连或目录异常立即停止，重连前不盲目重试。可停止后续删除，已发出的当前删除不可撤销。只删除卡内原件，不删除手机音频、转写或导入记录；“已交给导入队列”不代表已备份。所有确认和警告在界面保留。

复用 RecorderDeletion、RecorderFileList 和 RecorderFilesSection，控制器及输入源切换同步增加清理互斥。该层不包含 Wi-Fi。

验证：Windows/JDK 21 下 assembleDebug 成功，RecorderDeletionTest、RecorderFileListTest、RecorderFilesUiTest、RecorderInputUiTest 定向测试全部通过；本轮构建和测试耗时 1 分 42 秒。用户基本验证通过的是整合版本；本次拆分版未装手机，断连删除、取消和超过 40 个文件的跨页操作仍应使用可丢弃录音复核。
