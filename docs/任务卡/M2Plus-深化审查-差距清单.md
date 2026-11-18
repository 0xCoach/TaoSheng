# M2+ 深化审查：文件级差距清单

2026-11-18 · 基于 TAOSHENG-M2PLUS-REAL-PAIN-R1-R3 任务书
审查方法：逐文件阅读现有实现，对照用户痛点的 R1/R2/R3 要求分级为【已有】【部分】【未实现】。
结论先行：**底层比预期厚（热词、说话人、修订留痕、周报都已在），真正的缺口在"闭环"——纠错不回流、反馈不回写、来源不分层。**

---

## 一、七项 M2+ 功能现状盘点

### 1. 决策记忆 —— 完成度高，缺一个联动

- `decision/DecisionRepository.kt`：四态（Active/Superseded/Rejected/Withdrawn）、同主题冲突检测、不静默覆盖、替代/否决/撤回全留痕、sourceText 原话快照 + 回查兜底。【已有】
- `shared/collect/../decision` 表 + DAO + UI（DecisionScreen）。【已有】
- 【部分】`importFromItem`/`syncFromDigest` 只导整理产物里 category="决定/约定" 的条目，**没有证据门禁**——LLM 说"决定"就进，与原话校验弱（R1-5）。
- 【未实现】修改说话人归属或重转写后，**不提示重新评估受影响的决策/待办**（R1-4 联动）。

### 2. 主动跟进 —— 提醒链完整，反馈不回写

- `followup/FollowupEngine.kt`：Commitment/Todo/Idea/Suggestion 四 kind 分离、按 ref 去重、每日上限+渐进退避（`shared FollowupRules`）、markDone/dismiss/snooze。【已有】
- `followup/FollowupWork.kt`：WorkManager 日扫。【已有】
- 【部分】`markDone/dismiss` **只改 followup 行本身，不回写源对象**——待办没标完成、灵感没推进、项目状态不动（R3-1 核心缺口）。
- 【未实现】"周复盘：连续出现却从未验证的想法"专项；`review/` 包存在但是通用周报，无未推进项聚合（R3-2）。

### 3. 统一收集箱 —— 入库可靠，缺"提取状态"分层

- `collect/CaptureInbox.kt` + `shared CollectData.kt`：text/url/image 分享入库、URL 分类、域名标题、私有目录存图、ML Kit 中文 OCR（捆绑模型，APK 内已验证 assets/mlkit-google-ocr-models/）、内容指纹去重（sha256 文件名）、OCR 失败留原图+可重试。【已有，验收中补齐】
- 【部分】收集条目只有 Inbox/Linked/Archived 三态，**没有"已保存/已提取/已分析"分层**——URL 只有元数据，是否读到正文不可知（R2-3，也是"不假装抓过"的诚信要求）。
- 【未实现】OCR/提取结果转"可查询知识条目"的状态标记。

### 4. 工作流规则 —— 门禁正确，执行结果不回流

- `rules/WorkflowEngine.kt` + `WorkflowWork.kt`：手动/定时/事件触发、按天幂等、**AgentTask 只建 Pending+NeedsConfirm 不出网**（85-90 行确认）。【已有】
- 【部分】Agent 任务确认后**真实执行与结果回写链未验收**——执行了没有、结果写回任务/项目没有（R3-4）。
- 【未实现】"灵感→研究任务"一键转化（R3-3）。

### 5. 带出处问答 —— 机制在，召回维度单一

- `qa/GroundedQA.kt`：本地 FTS 取证→编号喂模型→`[#n]` 引用→sources 结构携带 kind/id/at，无模型时如实"等待配置服务"+仍返回命中。【已有】
- 【部分】检索只有 FTS 一路；**无时间/人物/项目/状态过滤组合**，"上周说过的""为什么否决过"这类问法只能靠全文碰运气（R2-4/5）。
- 【部分】UI 有出处列表；**跳原话定位**未验证。

### 6. 记忆纠错 —— 机制完整，覆盖面只到提取项

- `revise/MemoryCorrector.kt`：edit/delete/merge/split/undo、前后快照 JSON、`memory_revisions` 留痕、Undo 生成逆向日志。【已有】
- 【部分】纠错只作用于 `memory_items`；**转写句（transcript_lines）改字、说话人改归属不走修订**——`nameAndAssign`（voiceprint）直接改行不留痕（R1-2 缺口）。
- 【未实现】人工纠错→个人热词表沉淀（纠过的错字自动进词典）。

### 7. 只读 MCP —— 安全面达标，验收剩外部客户端

- `mcp/McpServer.kt`：127.0.0.1 绑定、Bearer 令牌、工具白名单只读、project_scope 服务端限权、令牌本体进 Keystore 加密 prefs。【已有，真机联调通过】
- 【部分】固定字节数读 body 已修（UTF-8 中文不再截断）；`decision_list` 已返回生效+历史。
- 【未实现】Claude Desktop 等真实外部 MCP 客户端联调（直连 JSON-RPC 已验，外部客户端标记 NOT_TESTED）。

---

## 二、对照 R1/R2/R3 的缺口汇总

### R1 可靠记录

| 要求 | 现状 | 文件 |
|---|---|---|
| 个人热词 | 【已有】`transcribe/HotwordContract.kt`、`LocalHotwordCorrection.kt`、`ui/transcript/HotwordCard.kt`、`DigestPrompt.withHotwords` 注入"只纠正这些词的同音错字" | 需验证真实生效路径 |
| 纠错沉淀词表 | 【未实现】改过的错字不进热词 | 新增 |
| 说话人归属编辑 | 【已有】`voiceprint/LocalVoiceprintRepository.nameAndAssign(lineId,name)`、`ui PeopleDialog` 人物命名 | 缺：改归属后联动重估决策/待办 + 留痕 |
| 关键数字/日期复核 | 【未实现】无"金额/日期/电话待复核"标记 | 新增（整理提取时标注） |
| 原始/整理分离 | 【已有】"原话不动"是既有原则，digest 层可编辑 | — |
| 决策证据门禁 | 【部分】syncFromDigest 无校验 | DecisionRepository |
| 音频完整性 | 【已有】`audio/CaptureHealth.kt`、CaptureWatchdog、断档检测；【未实现】**可视化仪表盘**（积压、失败、耗电、存储一页看全） | 新增 UI 聚合已有数据 |
| 失败恢复 | 【已有】转写队列可重试；【部分】批量重试入口 | — |

### R2 高效找回

| 要求 | 现状 |
|---|---|
| 短灵感分流 | 【未实现】无 ≤30s 轻量处理路径 |
| 来源提取状态 | 【未实现】收集箱无 已保存/已提取/已分析 分层 |
| 多维检索 | 【部分】FTS 有，时间/人物/项目/状态组合过滤无 |
| 历史决策过滤 | 【部分】决策有 Active/历史区分，搜索不区分 |
| 记忆关联/原话定位 | 【部分】QaSource 带 kind/id，跳转未验证 |

### R3 实际产出

| 要求 | 现状 |
|---|---|
| 跟进反馈回写 | 【未实现】markDone/dismiss 不回写源对象 |
| 周度未推进复盘 | 【未实现】review/ 是通用周报，无未验证想法聚合 |
| 灵感→研究任务 | 【未实现】无一键转化 |
| Agent 结果回写 | 【未验证】AgentDispatchWork 存在，端到端回写未验 |
| 对外发布确认 | 【已有】AgentTask NeedsConfirm 门禁 |

---

## 三、实施建议顺序（按痛点权重）

1. **反馈闭环小步快跑**（R3-1 + R2-3）：`markDone/dismiss` 回写源对象 + 收集箱加 `extracted` 字段分层——改动小、直接命中"结果闭环"与"不假装抓到"诚信线。
2. **纠错留痕+联动**（R1-2/4）：说话人改归属走 MemoryCorrector，改后提示重估关联决策。
3. **决策证据门禁**（R1-5）：syncFromDigest 导入时校验原话片段存在。
4. **多维检索**（R2-4）：QA/搜索加时间窗+项目+状态过滤参数。
5. **完整性仪表盘**（R1-6）：聚合 CaptureHealth/积压/耗电已有数据一页展示。
6. **短灵感分流**（R2-1）：按时长走轻量摘要。
7. **周复盘未推进项**（R3-2）：在 review/ 上加未验证想法聚合。
