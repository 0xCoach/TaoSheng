# R4 MCP 项目范围回归

## 结论：`MCP_SECURITY_GO`（本次修复两处越权 + 一个语义不一致）

## 全入口审计结果（tools/call 每条路径）

| 入口 | scope 判定 | 结论 |
|---|---|---|
| `memory_search` / `transcript_search` | 原话/事件为全局（无 projectId），ideas/decisions 命中走 `inScope` | 正确 |
| `decision_list` | 原实现对限定客户端漏掉全局决策（projectId=null），与 `inScope` 语义不符 | **已修**：统一 `observeActive+observeHistory` → `inScope` |
| `project_list` / `project_overview` | `inScope(it.id)` | 正确 |
| `project_marks` | 限定客户端恒走自身 scopeId，忽略传入 projectId | 正确（负向用例验证传他人 id 无效） |
| `idea_list` | `inScope(it.projectId)` | 正确 |
| `followup_list` | **原实现完全不过滤**；followups 表无 projectId，但 refKind/refId 可归因 idea/decision/capture | **已修**：ref 指向越权项目的跟进不再返回 |
| `capture_list` | `inScope(it.projectId)` | 正确 |
| `record_get` | `takeIf { inScope }`（上轮已修） | 正确，越权返回"不存在"不泄露存在性 |

## 认证层

- 仅 `127.0.0.1:7391`，`POST /mcp`；无 Authorization / 无效 token / 停用客户端 → 401（均有用例）。
- token 本体存 SecretStore，库内只留 hint；`enabled()` 是认证前唯一取数入口，停用即时生效。
- `toolsJson` 白名单在调用前拦截；只读工具集与 `McpTools.readOnly` 完全一致，无写工具外放。

## 新增负向回归测试

`app/src/test/java/app/resound/mcp/McpScopeTest.kt`（Robolectric + 真实 Socket + 真实库）：

- `missingOrInvalidTokenRejected`、`disabledClientTokenRejected`
- `recordGetCrossProjectIdGuessFails`：跨项目 ID 猜测返回"不存在"，本项+全局可读，全局客户端不受限
- `ideaListAndSearchRespectScope`、`decisionListShowsOwnAndGlobalNotForeign`、`captureListRespectsScope`
- `followupListHidesForeignRefs`：ref 归因过滤（本次新修路径）
- `projectListAndMarksRespectScope`、`toolWhitelistEnforced`、`noWriteToolsExposed`

结果：**10/10 通过**。附注：测试客户端须按 UTF-8 字节写请求（Content-Length 按字节算；ISO_8859_1 写汉字会造成服务端等字节死锁——服务端本身行为正确）。

## 遗留

- MCP 无 token 过期/吊销 TTL（停用即失效已足够当前形态）。
- 无限速；localhost 单机场景可接受。
