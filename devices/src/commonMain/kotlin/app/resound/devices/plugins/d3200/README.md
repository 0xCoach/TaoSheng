# D3200 试用驱动

- 维护人：xhp1211。
- 已验证的设备与固件（PR #30 的独立验证包）：realme + 录音豆主固件 04.92、盒固件 1.2.2。支持 BLE 实时音源、离线补传、录音控制、双电量/存储查询，以及 Android 经典蓝牙常灭灯控；仅 04.92 会发送灯控命令。
- 作者已在原验证包测试短句补传和回放、状态刷新、开始/暂停、手动暂停重连保持、常灭和部分充电状态切换。这些是原 PR 的记录，不是本次迁移后的真机结果。
- 待验证：本驱动官方构建的真机验收、长时间收音及全量历史、进程重启和长时间断连续传、其他固件、iOS；暖橙灯效未完成肉眼验收。盒外 USB 接电未能实时反映充电盒状态。
- 实时与补传两段 v1 录包、对应设备固件与真机报告：**待 xhp1211 补充**。仓库录包会公开。
- 协议参考/改编来源：[AllDayRec](https://github.com/suyiiyii/AllDayRec)（MIT）及 [tacshi/Soundcore](https://github.com/tacshi/Soundcore)（当前 MIT）；AllDayRec 声明的间接来源 ws/soundcore（0BSD）及 Eliqi797/open-feishu-recording-bean（MIT）一并保留。具体版本、对应实现范围及全部许可见仓库 `vendor/d3200/THIRD_PARTY_NOTICES.md`。Android APK 同步包含于 `assets/licenses/d3200/`。没有引入其 SDK 或二进制，不把第三方部分作为提交者独立原创，也不以主工程 CLA 取消第三方许可。
