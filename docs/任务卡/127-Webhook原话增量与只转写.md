# 卡 127 · Webhook 原话按批增量推送 + 不自动整理（议题 #22）

- 工作目录 `~/Developer/TaoSheng-127`，分支 `codex/127-webhook-lines`（从 `plan/0221` 拉出）。**不改 `ui/` 和 `MainActivity.kt`**，界面由 Claude 做。
- 版本：进 0.2.21。先读议题：`gh issue view 22`。

## 背景

议题 #22：用户在自己服务器跑 AI 助手，希望涛声只做收音、转写、认人，每批转写完把**纯文字 JSON** POST 到自填地址（可带 Bearer），由对方整理；可选“只转写、不整理”省大模型费用。

现状已基本覆盖：推送通道“自定义接口（Webhook）”可填 URL 和 Authorization；订阅只选“原话：全部”；`transcript_lines` 变化即触发（不依赖整理）；“这段别记”已排除；失败重试和最近状态都有。差距：

1. 原话每次推**当天全部**（`DailyMemory.kt` 里 `diaryLines(当天)`），没有按批增量；`PushSubscription.incremental` 目前只作用于整理结果。
2. 时间只有 `at`（毫秒），没有 ISO 时间、没有每句结束时间、没有批次编号。
3. “有新内容就推”最短间隔 15 分钟。
4. “只转写不整理”只能靠不填密钥或时间点留空，不直观。

## 约定（Claude 已改，签名不要动）

- `digest/DigestTimingContract.kt`：`DigestMode` 新增 `Off`（注释即需求）。

## 要做

### A. Webhook 原话按批增量
- 对 **Webhook 通道** 且订阅 `incremental = true` 时，原话只推**上次推送成功之后新增**的句子（按句子 id 或入库时间做水位线；推送成功才前移水位线，失败下次补发）。没有新句子就不推原话部分。
- 这一批在 JSON 里另加（不删除现有字段，保持兼容）：
  - `batch_id`：本批唯一、可重复推送时不变（同一批重试用同一个 id，便于对方去重）；
  - `lines_batch`：`{ "batch_id", "start_time", "end_time", "segments": [ { "id", "start", "end", "speaker", "text" } ] }`，时间为带时区的 ISO 8601（如 `2026-11-10T09:01:12+08:00`），`end` 用该句结束时间；`speaker` 沿用现有称呼规则（“我”、已标注人名、未知说话人）。
- 被“这段别记”删掉、或标为媒体且订阅排除媒体的句子不进批次（与现有规则一致）。
- `incremental = false` 或非 Webhook 通道：行为不变。
- 交付说明里写清 JSON 示例（议题作者会照着接）。

### B. 更短的推送间隔
- `RecordTrigger.OnChange.minGapMinutes` 允许 5 分钟（底层按任意正整数分钟处理即可，确认 5 分钟时调度正确、不刷屏）。

### C. 不自动整理（`DigestMode.Off`）
- `Off` 时：间隔整理、时间点整理、回顾前的整理都不安排、不运行；转写（含实时转写、卡 126 攒批）照常；用户手动“现在整理”照常可用。
- 每日回顾、每周回顾在 `Off` 时：不为它先跑整理；有现成整理结果就照常生成，没有就跳过（不报错）。
- 从 `Off` 切回其他模式时恢复原有调度。`nextDigestAt` 在 `Off` 时为 null。
- 设置跟着备份走（与现有整理设置同一处）。
- 所有 `when (mode)` 补齐分支。

## 必测（6～12 个用例）

- Webhook 增量：第一次推当天已有新句、第二次只推新增；失败后重推同一 `batch_id`；成功后水位线前移；“这段别记”的句子不出现。
- ISO 时间带时区、`end` 正确。
- 非 Webhook / 全量模式行为不变（1 个用例）。
- OnChange 5 分钟调度。
- `DigestMode.Off`：不安排整理、回顾前不整理、手动整理可用、切回恢复。

## 交付

`docs/任务卡/127-交付说明.md`：JSON 示例、界面要接的地方（Off 选项文案、5 分钟选项、Webhook 增量说明）、遗留问题。中文提交，不推送远端。本机编译 `export JAVA_HOME=/opt/homebrew/opt/openjdk@21`，工作区已有 `local.properties`。
