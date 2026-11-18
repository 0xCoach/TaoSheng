# 最终验收报告（M2+ FINAL-ACCEPTANCE-R1 + REAL-PAIN 深化首轮）

2026-11-18 · 基线 a0a113d6 → 本轮提交 **2a94901d** · OPPO Find N2

## 结论

本轮**修复了验收中发现的 7 个真实缺口**并完成可执行的设备验证；不可执行项如实标 NOT_TESTED。
同时按新任务书完成七项 M2+ 功能的文件级审查（`M2Plus-深化审查-差距清单.md`），并落地 R1/R2/R3 第一批 5 个补强。

## 一、验收中发现并修复的实际故障

| # | 故障 | 修复 |
|---|---|---|
| 1 | 26 个金色测试全挂 | 归因 CRLF/LF（Windows 检出换行转换），3 个测试类读取处归一化；248/248 全绿 |
| 2 | MCP 中文 body 截断 | HTTP Content-Length 按字节但按字符读 → 改 ISO-8859-1 读头+按字节数读体；设备测试过 |
| 3 | `decision_list` 只见历史不见生效 | 选择逻辑修正，PC 端实测返回生效决策 |
| 4 | 图片分享无去重 | 内容 sha256 定名 + `imageAt()` 查重；二次分享实测无新行 |
| 5 | 「原图回看」不存在 | CollectSheet 改渲染真实位图 |
| 6 | OCR 无重试入口 | `retryOcr(id)` + UI 按钮；原图始终保留 |
| 7 | 文字分享误报"无法接收录音" | （前轮已修）收集箱先判 accepts |

## 二、REAL-PAIN 首轮补强（R1/R2/R3）

| 项 | 内容 | 文件 |
|---|---|---|
| R3-1 跟进回写 | `markDone` 回写源待办（setItemStatus Done）/源灵感（Idea Done）；dismiss 只关提醒不动源 | FollowupEngine.kt |
| R2-3 提取分层 | `capture_items.extracted`：Saved/Extracted/Analyzed；迁移 36→37 含回填 | CollectData.kt / CaptureDatabase.kt |
| R1-5 决策证据门禁 | 无原话→强制"随口"；文本判定只降不升（"考虑一下"不入正式决定） | DecisionRepository.kt |
| R1-2 说话人留痕 | 改/清归属在同事务写 memory_revisions（targetKind=line） | LocalVoiceprintRepository.kt |
| R2-4 回忆式检索 | QaRules 解析"上周/还没验证/否决过"→时间窗+状态过滤+模型知情 | QaHints.kt(新)/GroundedQA.kt |

## 三、MCP 真实联调（PASS）

adb forward 后 PC 端实测：无令牌 401、坏令牌拒、tools/list 九工具、decision_list/capture_list/memory_search 中文正常、锁屏/后台/进程重启均存活、仅绑回环。外部 MCP 客户端（Claude Desktop）**NOT_TESTED**（协议面已验，见 MCP_CLIENT_SETUP.md）。

## 四、OCR（PASS）

捆绑模型在 APK 内（assets+so 实证）；真实截图分享→私有目录→中文 OCR 全文→可搜索→去重→失败留原图可重试。严格断网复跑**待设备回连补测**（模型离线性已由打包证据保证）。

## 五、七项功能 E2E 状态

| 链路 | 状态 |
|---|---|
| 决定→冲突→替代→历史 | 代码+单测 PASS；真机逐步操作 **部分验证**（decision_list 返回真实数据） |
| 承诺→通知→退避→完成 | 引擎+限频规则 PASS；通知实发 **NOT_TESTED**（需等待周期） |
| 分享→收集→关联 | PASS（真机：URL/图片入库、OCR、去重、可挂项目） |
| 规则→待确认→Agent | 门禁 PASS（NeedsConfirm 不出网）；真实派发 **NOT_TESTED** |
| 跨项目问答→引用→跳原话 | 机制 PASS；真机问答 **部分验证**（无模型配置时如实返回命中+等待提示） |
| 记忆修改→审计→撤销 | MemoryCorrector 全留痕+undo PASS（代码级） |
| MCP→授权→项目隔离 | PASS |

## 六、离线转写与稳定性

- 本地 SenseVoice：默认路径即本地，无云 ASR 静默回退；断网实测 **待补**。
- 杀进程恢复/17 分钟连续采集/让麦检测：已验证或机制在（详 ANDROID_24H_STABILITY.md）。
- 8h/24h/电话切换/备份重装恢复：**NOT_TESTED**（需真人值守或破坏当前数据）。

## 七、产物

- APK：`app/build/outputs/apk/debug/app-debug.apk`
- SHA256：`F0E41CB615E119AC4C214B45A5DF7FB957721908BE7BF98FAA856D49A4B069B9`
- 审查文档：`M2Plus-深化审查-差距清单.md`
- 未跑：`check_shared.py`/`check_ios.sh`（无 Python/zsh）
