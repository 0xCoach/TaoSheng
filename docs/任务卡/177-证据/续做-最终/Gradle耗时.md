# 续做 Gradle 耗时

从 0126588 续做后的逐次日志，包括搬前失败、搬后编译/测试、iOS 和最后打包。已通过且未受影响的用例未作全量重复。

| 日志 | 秒 | 结果 |
|---|---:|---|
| 续做-Ask/iOS.log | 27.61 | passed |
| 续做-Ask/工厂时钟注入.log | 19.71 | passed |
| 续做-Ask/搬前录制.log | 16.86 | passed |
| 续做-Ask/搬后重放.log | 60.44 | failed |
| 续做-Ask/补齐装配后重放.log | 41.99 | passed |
| 续做-CaptureStats/iOS.log | 27.20 | passed |
| 续做-CaptureStats/搬前录制.log | 16.14 | passed |
| 续做-CaptureStats/搬后重放.log | 57.14 | passed |
| 续做-Diary/iOS.log | 27.81 | passed |
| 续做-Diary/搬前录制.log | 10.14 | passed |
| 续做-Diary/搬后重放.log | 58.40 | passed |
| 续做-DigestSettings/iOS.log | 27.54 | passed |
| 续做-DigestSettings/搬前录制.log | 12.47 | passed |
| 续做-DigestSettings/搬后重放.log | 62.25 | passed |
| 续做-EnvSound/iOS.log | 28.38 | passed |
| 续做-EnvSound/搬前录制.log | 14.18 | passed |
| 续做-EnvSound/搬后重放.log | 14.14 | failed |
| 续做-EnvSound/文件门面拆名后重放.log | 74.35 | passed |
| 续做-EnvSound/时间API声明补齐后重放.log | 58.81 | failed |
| 续做-Memory/iOS.log | 33.71 | passed |
| 续做-Memory/搬前录制.log | 16.80 | passed |
| 续做-Memory/搬后重放.log | 74.30 | passed |
| 续做-RecordingSources/iOS.log | 26.76 | passed |
| 续做-RecordingSources/搬前录制.log | 13.06 | passed |
| 续做-RecordingSources/搬后重放.log | 60.18 | passed |
| 续做-Review/iOS.log | 28.91 | passed |
| 续做-Review/搬前录制.log | 16.81 | passed |
| 续做-Review/搬后重放.log | 14.69 | failed |
| 续做-Review/日期机械适配修正后重放.log | 60.43 | passed |
| 续做-Situation/iOS.log | 52.09 | passed |
| 续做-Situation/搬前录制.log | 13.27 | passed |
| 续做-Situation/搬后重放.log | 34.35 | failed |
| 续做-Situation/补齐共用依赖后重放.log | 91.49 | passed |
| 续做-Skill/iOS.log | 26.59 | passed |
| 续做-Skill/名称读取时机-iOS.log | 26.27 | passed |
| 续做-Skill/名称读取时机复核.log | 56.55 | passed |
| 续做-Skill/搬前录制.log | 13.85 | passed |
| 续做-Skill/搬后重放.log | 13.80 | failed |
| 续做-Skill/格式端口补齐后重放.log | 59.95 | passed |
| 续做-TranscribeSettings/iOS.log | 27.05 | passed |
| 续做-TranscribeSettings/字符串适配修正后重放.log | 69.27 | passed |
| 续做-TranscribeSettings/搬前录制.log | 12.55 | passed |
| 续做-TranscribeSettings/搬后重放.log | 14.14 | failed |
| 续做-Transcript/iOS.log | 31.38 | passed |
| 续做-Transcript/基线诊断.log | 19.59 | failed |
| 续做-Transcript/搬前命令基线.log | 16.91 | passed |
| 续做-Transcript/搬前完整基线.log | 21.01 | failed |
| 续做-Transcript/搬前录制.log | 0.07 | failed |
| 续做-Transcript/搬前录制提权.log | 25.59 | failed |
| 续做-Transcript/搬后重放.log | 75.75 | passed |
| 续做-Usage/iOS.log | 27.51 | passed |
| 续做-Usage/搬前录制.log | 16.82 | passed |
| 续做-Usage/搬后重放.log | 57.79 | passed |
| 续做-最终/发布包构建.log | 128.23 | passed |

共 54 次有 real 计时，累计 1933.08 秒，最长 128.23 秒。
Skill 首次未引号通配符被 zsh 拦截，Gradle 未启动、无 real 计时；不是审批拒绝。
旧阶段 40 次日志和 1 次沙箱失败的 1746.90 秒另见 最终/Gradle耗时.md；主会话试听修复另见 续做试听修复，不混入本轮计数。
