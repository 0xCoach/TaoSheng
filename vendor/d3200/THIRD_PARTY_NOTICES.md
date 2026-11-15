# D3200 third-party notices / 第三方来源与许可

本说明适用于涛声 D3200 插件的协议参考、改编部分及其 Android 传输适配。它记录第三方来源，不将第三方作品声明为贡献者独立原创，也不覆盖或替代以下原有许可。许可证全文在本目录；Android APK 同时包含于 assets/licenses/d3200/。

## 来源范围

| 来源 | 权利人及许可 | 本项目涉及范围 | 核对版本 |
|---|---|---|---|
| [AllDayRec](https://github.com/suyiiyii/alldayrec) | Copyright (c) 2026 suyiiyii；MIT，见 LICENSE-AllDayRec | Bean.kt 的灯控及 D3200 协议参考/改编，BeanLink.kt 的连接、补传生命周期研究；不是整体移植 App 或服务端 | 7725bce87c6cd32d191b340445529a10797abc0e；清理行为另对照 47223be37ee87d5699c18b1c8ed1acdf05a607d4 |
| [tacshi/Soundcore](https://github.com/tacshi/Soundcore) | Copyright (c) 2026 Rocky Shi；MIT，见 LICENSE-Soundcore | PROTOCOL.md、lib/protocol/models.dart、lib/wifi/wifi_export_service.dart 的字段布局、Wi-Fi 握手/交接、UDP 保活和请求格式研究及据此实现；不是引入其 SDK、二进制或产品图片 | 早期研究 f01a19c227aac0cee9a7c851b3f6b69cf5540a24；本轮以已附 MIT 的 d296a1c5c0645797ff7cd3b55e93477a99820857 核对 |
| [ws/soundcore](https://github.com/ws/soundcore) | Copyright (C) 2026 Will Smidlein；0BSD，见 LICENSE-ws-soundcore | AllDayRec 声明的间接协议来源：crates/soundcore-d3200-protocol 的帧、密钥、解密和解析，以及 Wi-Fi 协议研究；保留其来源与许可 | b5517696f65c4cde87fa14d32c6921183bce4786 |
| [Eliqi797/open-feishu-recording-bean](https://github.com/Eliqi797/open-feishu-recording-bean) | Copyright (c) 2026 Eliqi797；MIT，见 LICENSE-open-feishu-recording-bean | AllDayRec 声明的间接协议来源：D3200Protocol.kt、DeviceCrypto.kt、PinnedRecorderLink.swift；保留其来源与许可 | 8084d5e2c12bc64296b11730361370f1fc939e3e |

间接来源依据：[AllDayRec THIRD_PARTY_NOTICES.md](https://github.com/suyiiyii/alldayrec/blob/47223be37ee87d5699c18b1c8ed1acdf05a607d4/THIRD_PARTY_NOTICES.md) 及 Bean.kt 文件头。tacshi/Soundcore 与 ws/soundcore 是两个不同的仓库。前者于 0e100df287ef44f8c343935ada8093b61da1f207 增加 MIT；早期参考快照没有根 LICENSE，故本轮同时核对其已授权版本的相关参考材料，不能仅将旧快照称作“自始已附 MIT”。

## 对应实现

- devices/src/commonMain/kotlin/app/resound/devices/plugins/d3200/BeanLight.kt：灯控、帧解码及经典蓝牙启用；原有 AllDayRec 改编署名保留。
- 同目录 D3200Protocol.kt（含 D3200Crypto）、D3200DeviceInfo.kt、D3200HistoryCore.kt：命令、字段、密钥/音频解封和文件/序号语义。协议事实和对参考实现的改编分别看待，不以更换语言声称没有改编。
- 同目录 D3200WifiTransfer.kt，以及 app/src/main/java/app/resound/devices/android/ 下 AndroidLocalFileNetwork.kt、DeviceFileWebSocketProfile.kt、DeviceFilePeerTrust.kt：依据协议研究实现平台网络交接及设备端兼容，保留上述研究来源。
- 涛声既有通用设备接口、完整归档、独立备份读回、清理资格、持久化队列及系统调度由本工程实现；参考产品的功能和行为不等于复制其整个实现。其他已有第三方库仍按工程各自许可处理。

上述范围按参考及改编来源保守披露，不声称所有列出的文件逐行复制，也不声称所有代码为提交者独立原创。没有从这些项目迁入其固件、声纹模型、图标或厂商反编译 SDK 文件；本说明不对那些材料作重新许可。

## 许可和 CLA 范围

第三方部分保留各自 MIT/0BSD 许可及版权。MIT 要求在副本或实质部分中保留版权及许可全文，二进制分发时亦保留；0BSD 的全文和来源在此一并记录。主工程采用 FSL-1.1-MIT 或贡献者签署 CLA 不会取消这些第三方许可义务，也不会把第三方著作权变成贡献者所有。

贡献者只能对自己有权许可的部分授予 CLA 约定的权利，不能代表第三方另授其无权授予的著作权或专利权。此处披露既有许可和来源，没有替贡献者签署 CLA，也没有修改主工程 CLA、FSL 或旧 MIT 贡献的许可。

## 分发核对

源码保留 vendor/d3200/ 的四份许可证和本说明。Android 默认 assets 目录包含相同五份文件；交付时核对字节一致，并打开实际 APK ZIP 核对 assets/licenses/d3200/ 的完整内容。后续更新这些文件时需同步两处。iOS 若未来发布该驱动的二进制，也需随相应分发保留这些许可。
