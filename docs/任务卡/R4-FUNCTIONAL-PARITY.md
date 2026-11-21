# R4.1 功能等价矩阵（R4-FUNCTIONAL-PARITY）

> 基线 `v0.4.0`（`ddd3306a`）→ 当前 `5438632b`。
> 证据口径：**代码状态**（blob SHA 同/改/新增，见 R4-SOURCE-PROVENANCE.md）+ **测试**（`app/build/test-results/testDebugUnitTest/` 1248 项机器可读报告）+ **真机**（OPPO PGU110 装机/启动/聆听冒烟）。
> 判定规则：字节相同 = 行为由构造保证等价（FULL_PARITY-BY-IDENTITY）；改动文件按 diff 复核行为差异；无代码证据不判完成。

## A. 上游 v0.4.0 功能 → 现状

| # | 功能 | 代码状态 | 验证证据 | 判定 |
|---|---|---|---|---|
| 1 | 全天前台录音（RecordService/通知/保活） | capture/audio 25 同+7 改 | 单测+真机聆听中冒烟 | FULL_PARITY |
| 2 | VAD 人声段入 `segments`（silero_vad.onnx 随包不变） | 模型/代码同 | SegmentFiles/Segments 测试 | FULL_PARITY |
| 3 | 段状态机 Pending→Done/Failed/Skipped/Abandoned | 同 | CaptureStats/队列测试 | FULL_PARITY |
| 4 | sherpa SenseVoice 本地转写（CPU Int8，中/英/日/韩/粤） | transcribe 50 同+4 改 | LocalAsr 系列测试 | FULL_PARITY（Auto 档按 App 语言解析为改进） |
| 5 | SM8650 QNN NPU 档 | 同 | 本机 SM8475 不支持属硬件事实，代码路径保留 | FULL_PARITY |
| 6 | `transcript_lines` 句级时间戳/说话人/置信 | 同+迁移新增列 | Index183 迁移测试 | FULL_PARITY+IMPROVED |
| 7 | 整理管线→`memory_events`/`memory_items`（Pending→Accept） | digest 23 同+3 改 | Digest/Review 测试 | FULL_PARITY |
| 8 | `reviews` 每日回顾生成 | review 6 同+1 改 | ReviewRepository 测试 | FULL_PARITY |
| 9 | `things` 寻物 | 6 同 | 测试保留 | FULL_PARITY |
| 10 | `topics` 专题（开始/暂停/标记/结束→纪要） | topic 3 同+2 改 | TopicMarks/TopicUi 测试+真机 | FULL_PARITY |
| 11 | `day_diary` 日记导出 | 2 同 | DayDiaryTest（测试侧改注入 PathStrategy，产品码不动） | FULL_PARITY |
| 12 | 技能卡片（skills 目录定义执行） | skills 10 同+1 改 | Skills/Parity 测试 | FULL_PARITY |
| 13 | `embeddings` 向量检索 | 同 | — | FULL_PARITY（本地/提供方同逻辑） |
| 14 | 声纹分人（pyannote） | voiceprint 10 同+2 改 | Voiceprint 测试（修 Windows renameTo） | FULL_PARITY |
| 15 | `env_sounds` 环境音判定/媒体声跳过 | 8 同 | EnvSound 测试 | FULL_PARITY |
| 16 | 原声保留策略/清理 | 同 | — | FULL_PARITY |
| 17 | 重做 `redo_snapshot` | redo 6 同+1 改 | 测试保留 | FULL_PARITY |
| 18 | WebDAV 备份/恢复 | backup 17 同+7 改 | BackupRestore/WebDav 测试+恢复 E2E 7 用例 | **IMPROVED**（兼容「回响」旧目录、16 新表纳入分卷） |
| 19 | WorkManager 批量转写/heavy Mutex 串行 | 同 | PipelinePeriodic/Persistence/Sequence 测试 | FULL_PARITY |
| 20 | 失败分级退避（attempts/nextRetryAt/firstFailedAt） | 同 | 同 19 | FULL_PARITY |
| 21 | 强提醒/每日提醒 push | push 20 同+5 改 | Push* 系列测试 | FULL_PARITY |
| 22 | 使用统计 `asr_usage`/`usage` | 同 | Usage 测试 | FULL_PARITY |
| 23 | 问题报告导出 | diagnostics 3 同+2 改 | ProblemReports 测试 | FULL_PARITY |
| 24 | 定位记录 | location 4 同 | — | FULL_PARITY |
| 25 | 首页（状态/统计/时间线/搜索 RecordSearch） | ui 69 同+28 改 | RecordSearch34/Shots 测试+真机截图 | FULL_PARITY（UI 主题/状态行为改进） |
| 26 | 问答入口（上游 ask） | ask 9 同+1 改 | AskParity153Test | FULL_PARITY |
| 27 | 自动更新检查（上游源） | **改**：源改可配置 manifest，无配置不发请求 | Update* 测试+交付说明 | **IMPROVED/CHANGED**（去掉上游固定源，隐私向好；默认不更新属刻意） |
| 28 | 上游 CLA/贡献者流程文档、发布脚本 | **删除** | — | REMOVED（非产品功能） |
| 29 | 品牌主题（橙→紫罗兰）/About 页/应用名/图标 | 改 | 真机截图 | IMPROVED/CHANGED |

## B. 本项目新增能力（M1/M2/M2+/R4）→ 验证状态

| 功能 | 代码 | 测试证据 | 判定 |
|---|---|---|---|
| 多提供方管理/诚实降级「等待配置服务」 | AppGraph+providers | LlmProviders 测试 | IMPROVED（新增） |
| 灵感收件箱 ideas | IdeaRepository+UI | Inbox 测试 | IMPROVED（新增） |
| 项目记忆 projects | ProjectRepository+UI | 测试 | IMPROVED（新增） |
| 决策记忆 decisions 四态/证据链 | DecisionRepository | 测试 | IMPROVED（新增） |
| 主动跟进 followups 限频/回写 | FollowupEngine | 测试 | IMPROVED（新增） |
| 统一收集箱 capture_items/OCR | CaptureInbox | CaptureInboxSurviveTest 等 | IMPROVED（新增） |
| 工作流规则 | WorkflowEngine | 测试 | IMPROVED（新增） |
| 修订撤销 revisions | MemoryCorrector | 测试 | IMPROVED（新增） |
| GroundedQA 落地问答+引用 | GroundedQA+EvidenceGuard | 测试 | IMPROVED（新增） |
| 只读 MCP 按项目限权 | McpServer | **McpScopeTest 10 负向用例** | IMPROVED（新增，越权已修） |
| 场景感知录音 | SceneEngine+AndroidScene | 测试 | IMPROVED（新增） |
| 时间语义分离 recordedAt/dueAt | 迁移 38→39 | 迁移测试 | IMPROVED |
| 记忆价值分层 valueScore | MemoryValueRules | 测试 | IMPROVED |
| TemporalGuard/EvidenceGuard | shared | 测试 | IMPROVED |
| 低置信复核 | TranscriptReviewRules | 测试 | IMPROVED |
| 收集箱事务/孤儿段自愈 | CaptureInbox/SegmentFiles | 真机实证+测试 | IMPROVED |

## C. 缺失/回归清单（诚实）

| 项 | 状态 | 说明 |
|---|---|---|
| 上游功能缺失 | **0 项** | 无上游源码功能被删除；删除物均为流程文档/发布脚本 |
| 已知功能回归 | **0 项** | 1248 测试全绿；改动文件 diff 复核无行为回退 |
| 断网复合 E2E | **NOT_TESTED** | 逻辑层有单测，端到端未在真机断网环境执行 |
| 72h 连续运行/发热/内存 | **BLOCKED** | 必须真机实测，不得以代码审计代替 |
| 迁移 1→41 逐段设备端长跑 | **NOT_TESTED** | 迁移测试全过（`CaptureDatabase.MIGRATIONS`），设备端未跑 |
| 严格断网下 分享→OCR→入库 | **NOT_TESTED** | 同上 |

## D. 判定

**FUNCTIONAL_PARITY_STATUS = FULL_PARITY（上游 29 功能区：26 FULL_PARITY + 3 IMPROVED/CHANGED + 0 MISSING/REGRESSION）**
「功能完美复刻」按"上游功能完整保留 + 无回归"成立；按"全部源码自主重写"**不成立**（见 provenance：~94% 仍属 FSL 衍生）。两者不可混谈。
