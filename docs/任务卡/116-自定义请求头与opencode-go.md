# 卡 116 · 自定义请求头（大模型 + 转写）与 opencode go 预设

- 工作目录 `~/Developer/TaoSheng-116`，分支 `codex/116-custom-headers`（从 `plan/0220` 拉出）。**不改 `ui/`**，设置页由 Claude 做。
- 版本：进 0.2.20。

## 背景

用户想接 opencode go 等服务，对方要求或建议带特定请求头，现在只能带 `Authorization: Bearer`。负责人已定：
- 大模型和转写服务**都支持**自定义请求头（可填多组“名称 = 值”）；
- 请求头可能含密钥：存进系统安全区，**随备份一起备份、恢复**；界面用星号遮住（遮挡由界面做）；
- 加 opencode go 预设，用户只填密钥。

## 要做

### 1. 自定义请求头
- 数据：`data class HeaderEntry(val name: String, val value: String)`，放进 `digest/MemoryContract.kt` 末尾（约定文件，本卡允许**只新增**这一个类型和下面两个接口方法，不改已有签名）。
- `DigestSettings` 新增（带默认实现，避免假实现编译失败）：
  - `fun headers(presetId: String): List<HeaderEntry> = emptyList()`
  - `fun saveHeaders(presetId: String, headers: List<HeaderEntry>) {}`

  按大模型服务分别保存（每个预设/自定义服务各一组）。
- 转写：在 `TranscribeSettings` 加同名两个方法，按转写服务 id 保存（看现有代码里转写服务怎么区分）。
- 校验：名称只允许 HTTP 标准的 token 字符；值不许有回车换行；不许覆盖 `Host`、`Content-Length`、`Content-Type`、`Transfer-Encoding`、`Connection`；允许覆盖 `Authorization`（有的服务用别的认证写法），覆盖时不再自动加 Bearer。保存时不合法直接抛出带人话说明的异常，界面显示。
- **所有**用到该服务的请求都要带上：整理（普通与流式）、问问、模型检测、备用模型切换（卡 103）、转写（含完整请求地址模式、声纹等，凡用该服务地址发请求的地方）。用 grep 把发请求的地方找全，交付说明里列出清单。
- 日志、问题记录、诊断导出里**不得出现请求头的值**（只能出现名称）。

### 2. opencode go 预设
- 官方文档：https://opencode.ai/docs/go/ 。地址 `https://opencode.ai/inference/go/openai/v1`（走 `/chat/completions`）。先用 `curl https://opencode.ai/inference/go/v1/models` 核对能否访问、有哪些模型；若地址与文档不一致，以实测为准并在交付说明写明。
- 预设的默认模型选一个走 chat/completions 的便宜快速模型（参照现有预设的做法），交付说明写理由。
- 会话头：官方建议每个会话带 `x-opencode-session`（固定不变的 id，用于分配与提示词缓存）。该预设自动带上：首次使用时生成一个随机 id 存下来，之后一直用同一个；**不出现在用户的自定义请求头列表里**；随备份一起恢复。用户自定义了同名头时以用户的为准。
- 没有密钥无法实测调用，只做到请求组装正确（单测断言地址和请求头）。

## 必测（5～10 个用例即可）

- 自定义头出现在整理请求和转写请求里；Authorization 覆盖时不再加 Bearer。
- 非法名称/值、禁止的名称被拒绝。
- opencode go 请求带 `x-opencode-session`，两次请求值相同；用户同名头优先。
- 备份 → 恢复后请求头还在。
- 日志里没有请求头的值（1 个用例）。

## 交付

`docs/任务卡/116-交付说明.md`：带上请求头的请求清单、opencode 实测结果、界面需要接的方法、遗留问题。中文提交，不推送远端。
