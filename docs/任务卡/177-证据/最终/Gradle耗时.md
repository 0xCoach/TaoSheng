# Gradle 调用耗时

每行对应实际一轮调用；失败轮保留，不与后续通过结果混淆。所有命令使用 JDK 21、卡177 registry、skipSigningSecrets；没有执行 --stop。

| 日志 | 秒 | 结果 |
|---|---:|---|
| A-EnvSound/iOS.log | 25.62 | 通过 |
| A-EnvSound/适配验证.log | 21.89 | 通过 |
| A-EnvSound/验证.log | 67.57 | 失败，已在后续轮修正 |
| A-Import/iOS.log | 27.47 | 通过 |
| A-Import/验证.log | 70.58 | 通过 |
| A-Memory/API适配编译.log | 20.10 | 失败，已在后续轮修正 |
| A-Memory/iOS.log | 27.93 | 通过 |
| A-Memory/定向测试.log | 22.16 | 通过 |
| A-Memory/最终编译.log | 28.91 | 通过 |
| A-Memory/首次编译.log | 55.48 | 失败，已在后续轮修正 |
| A-Quiet/iOS.log | 25.17 | 通过 |
| A-Quiet/适配验证.log | 53.54 | 通过 |
| A-Quiet/验证.log | 56.58 | 失败，已在后续轮修正 |
| A-Rhythm/iOS.log | 30.99 | 通过 |
| A-Rhythm/验证.log | 67.16 | 通过 |
| A-Transcribe/iOS.log | 27.40 | 通过 |
| A-Transcribe/适配验证.log | 34.80 | 通过 |
| A-Transcribe/验证.log | 63.50 | 失败，已在后续轮修正 |
| A-门槛/验证.log | 178.14 | 通过 |
| B-Things/iOS.log | 27.99 | 通过 |
| B-Things/搬前录制.log | 14.96 | 通过 |
| B-Things/搬后重放.log | 63.49 | 通过 |
| B-Topic/iOS.log | 27.84 | 通过 |
| B-Topic/搬前录制.log | 9.42 | 失败，已在后续轮修正 |
| B-Topic/搬前录制修正.log | 14.47 | 通过 |
| B-Topic/搬后重放.log | 70.73 | 通过 |
| B-Usage/iOS.log | 25.29 | 通过 |
| B-Usage/搬前录制.log | 9.94 | 失败，已在后续轮修正 |
| B-Usage/搬前录制修正.log | 14.32 | 通过 |
| B-Usage/搬后及最终打包.log | 135.72 | 通过 |
| C/iOS.log | 30.67 | 通过 |
| C/定向测试.log | 36.92 | 失败，已在后续轮修正 |
| C/导出调用编译.log | 53.83 | 失败，已在后续轮修正 |
| C/挂起适配编译.log | 24.88 | 失败，已在后续轮修正 |
| C/最终编译.log | 10.99 | 通过 |
| C/测试适配编译.log | 30.23 | 失败，已在后续轮修正 |
| C/订阅复测及发布包.log | 69.31 | 通过 |
| C/首次编译.log | 55.50 | 失败，已在后续轮修正 |
| 基线/截图提权.log | 104.29 | 通过 |
| 最终/快照调用补验.log | 11.01 | 通过 |

另有最初沙箱内截图启动尝试，工具回执 0.11 秒即因缓存锁权限失败（基线/截图.log），未启动测试。含该次合计 41 轮，1746.90 秒。每轮均小于 10 分钟；该合计含编译、打包、截图与 iOS，不是手机测试耗时。
