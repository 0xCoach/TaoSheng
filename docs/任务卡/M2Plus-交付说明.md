# M2+ 竞品对标七能力——交付说明

任务：soundsound-M2-PLUS-COMPETITOR-UPGRADE（在 M0–M2 基础上增量实现，非新建项目）。
基线：soundsound v0.4.0，分支 `dev/m0-m1-m2`，数据库 35→36。

## 一、做了什么

### 1. 决策记忆 DecisionMemory（P0-1）
- 新表 `decisions`：topic + body、状态（生效/被替代/被否决/已撤回）、置信度、来源（手动/整理提取）、原话证据（转写行 + 原文快照）、项目关联。
- `DecisionRules.activeSameTopic` 同主题冲突检测：新建时列出冲突项，用户选「替代/共存」，不静默覆盖。
- `DecisionRepository` 从每日整理的「决定/约定」类提取项自动入库（同步提取按钮手动触发）；支持否决（记原因）、撤回、编辑、删除——全部经 `memory_revisions` 留痕。
- 验收语义：被替代/否决/撤回的旧决定只在「历史」里可见，不计入生效列表。

### 2. 主动跟进 ProactiveAssistant（P0-2）
- 新表 `followups`：kind 区分承诺/待办/灵感/AI建议——AI 建议永不冒充用户承诺。
- `FollowupEngine.scan`：扫描长期未跟进的已接受记忆项和旧灵感，`openByRef` 按引用去重不重复建。
- `FollowupRules.canRemind`：每日限 3 条、同项 1→3→7 天渐进退避；支持完成/忽略/稍后/重开。
- `FollowupWork`（WorkManager 周期任务）每天扫一次并发本地通知，深链回助手·跟进页。

### 3. 统一收集箱 UnifiedCaptureInbox（P0-3）
- `AndroidManifest` 新增 `text/*`、`image/*` 分享接收（audio 原有通道保留）。
- `MainActivity.handleIntent` 先路由：文字/链接/图片→收集箱；音频→原有导入。修了一个真机 bug：原录音导入会把 action 置空并误报「无法接收录音」。
- `CaptureInbox`：URL/文字自动分类、域名做标题、备注分离、同内容去重；图片复制进 `filesDir/inbox` 私有目录后跑 ML Kit 中文本地识字，ocrText 单独存，失败不阻塞入库。来源 App 经 Activity referrer 记录。

### 4. 工作流规则 WorkflowRules（P0-4）
- 新表 `workflow_rules` + `rule_runs`：触发（手动/定时/事件）× 动作（通知/建待办/跑分析/派 Agent）。
- `WorkflowEngine`：`lastFiredDay` 按天幂等、事件 `emit` 精确匹配；Agent 动作只建 Pending 任务，必须人工确认才出网。
- `WorkflowWork` 周期任务触发到点规则。
- 事件来源：`capture_arrived`（收集箱入库）、`followup_due`（跟进到期）、`idea_created`（灵感新建）。

### 5. 有出处问答 GroundedMemoryQA（P0-5）
- `GroundedQA`：复用 `RecordSearch` 跨项目 FTS/LIKE 检索 → 本地编号 [#1..#n] → 交给已配置模型。
- 两模式：「仅查我的记录」严格引用、无证据明说；「外部知识」允许补充但必须标「（补充）」前缀。
- 未配置模型时不调网，如实返回提示。

### 6. 记忆纠错 MemoryCorrection（P0-6）
- 新表 `memory_revisions`：编辑/删除/合并/拆分/恢复/撤销统一留痕，前后快照 JSON 保存。
- `MemoryCorrector`：undo 用快照回滚；删除走墓碑不真删；merge 保留原出处链。
- `MemoryStoreRepository` 挂可选修订钩子（不改 Contract 签名），记忆页编辑/删除自动留痕。

### 7. 记忆 MCP MemoryMCP（P0-7）
- 新表 `mcp_clients`：只存 token 指纹/提示，token 本体进 `SecretStore`。
- `McpServer`：绑定 127.0.0.1 不公网暴露；JSON-RPC 风格 tools/list + tools/call；令牌认证；工具白名单只读（memory.search / memory.item / decisions.list / followups.open / project.overview）；`project_scope` 限定后跨项目查询直接拒绝。
- 配对流程：MCP 页生成一次性令牌给用户贴到电脑端客户端。

## 二、数据层

DB v36，迁移 `MIGRATION_35_36` 建六张新表（decisions/followups/capture_items/workflow_rules+rule_runs/memory_revisions/mcp_clients），全部走 `CaptureDatabase.MIGRATIONS` 统一清单。

## 三、怎么验证的

- `:shared:jvmTest` 新增 `M2PlusRulesTest` **11/11 通过**（决策冲突、跟进限频退避、收集分类/URL 提取/去重、规则事件匹配、MCP 白名单、修订快照）。
- `:app:testDebugUnitTest` `M2PlusMigrationTest` **4/4 通过**（35→36 迁移建表、幂等重跑、`CaptureDatabase.VERSION` 断言）。
- `assembleDebug` 成功，APK 装到 OPPO Find N2（PGU110，Android 16）实测：
  - 启动无崩溃；助手页 10 个入口全部渲染（灵感/项目/分析/任务/问答/决策/跟进/收集箱/规则/MCP）。
  - 分享 `text/plain`（链接+中文备注）→ 收集箱正确入库：识别为「链接」、标题取域名、中文进备注、自动跳助手页。
  - 决策页/收集箱页打开正常；全天录音+实时整理未被影响（实测 2 分钟人声 4 事件正常出）。
- 存量 26 个日期敏感金色测试失败为上游既有问题（期望「今天」写死），与本次无关。

## 四、真机要注意

- WorkManager 两个周期任务依赖系统调度，OPPO 杀后台严格时可能延迟触发；不影响手动扫描。
- ML Kit 中文识字首次使用要下载识别模型（系统组件管理），离线可用但首次需联网备齐。
- MCP 只绑 127.0.0.1，电脑端访问需 `adb forward` 或同网段局域网方案；令牌只显示一次。

## 五、遗留问题 / 未实测

- MCP 真实客户端（Claude Desktop 等）联调未测——协议面已就绪，待用户配对验证。
- 图片分享 OCR 端到端未在真机跑过（需用户从相册/浏览器真实分享一次）。
- `digest_done` 事件暂无发射点，规则里已不列出；后续可在整理完成处 emit。
- 决策「同步提取」依赖每日整理已产生「决定/约定」类条目，新装库为空属正常。
- P1 预留未做：语音问答 TTS、全局离线语音输入、人物关系、日历关联、跨设备同步。
- check_shared.py / check_ios.sh 本机无 Python/zsh 未跑；shared 代码无 android/java 依赖，纯 Kotlin+kotlinx.serialization 合规。
