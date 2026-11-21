# MCP 客户端接入指南（声声不息 → 电脑 AI）

2026-11-18 · 真机验证：OPPO Find N2

## 一句话原理

手机的 `127.0.0.1` 和电脑的 `127.0.0.1` 是两台设备。电脑要访问手机 MCP 服务，必须经过 **ADB 端口转发**（USB 或无线 ADB 均可）。服务本身只绑回环，不公网暴露。

## 服务端参数

| 项 | 值 |
|---|---|
| 端点 | `http://127.0.0.1:7391/mcp`（手机侧） |
| 转发后 | `http://127.0.0.1:7391/mcp`（电脑侧，经 adb forward） |
| 传输 | HTTP POST + JSON-RPC 2.0 |
| 认证 | `Authorization: Bearer sbx_<64hex>` |
| 绑定 | 仅 `127.0.0.1`（netstat 实测 `::ffff:127.0.0.1:7391 LISTEN`） |

## 启用步骤

1. 手机：助手 → MCP → 「开启」（按钮变「关闭」即监听中）。
2. 手机：同页「配对客户端」→ 填名字（如 `ClaudeTest`）→「生成令牌」→ **立即复制**（只显示一次，本体进 Keystore 加密存储，库里只存指纹）。
3. 电脑：
   ```
   adb forward tcp:7391 tcp:7391
   ```
   （无线 ADB 先 `adb connect <手机IP>:<端口>`）
4. 客户端把端点配为 `http://127.0.0.1:7391/mcp`，带 Bearer 令牌。

## 已验证行为（真机实测）

| 用例 | 结果 |
|---|---|
| 无令牌 POST | `401 缺少 Bearer 令牌` |
| 无效令牌 | `令牌无效` |
| `tools/list` | 9 个只读工具：memory_search / transcript_search / project_list / project_marks / idea_list / decision_list / followup_list / capture_list / record_get |
| `tools/call capture_list` | 返回真实收集条目 |
| `decision_list` | 返回生效中决策（含置信度/状态） |
| `memory_search` | 中文 UTF-8 body 正常（修复字节/字符长度混淆后） |
| 未知工具 | 拒绝 |
| 客户端白名单外工具 | 拒绝（androidTest 覆盖） |
| 跨 project_scope 查询 | 拒绝（androidTest 覆盖） |
| 锁屏/后台/进程重启后 | 均仍可应答（实测三次） |

## Claude Desktop 等标准 MCP 客户端

**NOT_TESTED。** 本服务是「HTTP POST + JSON-RPC」形态；Claude Desktop 的 remote MCP 走 Streamable HTTP/SSE。直连 JSON-RPC 已全量验证（上表），外部 MCP 客户端适配层（`initialize`/`notifications` 握手差异）未实测——接入时如遇握手差异，需要客户端侧桥接（如 `mcp-remote` 转发器），这是后续事项不是缺陷声明。

## 安全边界

- 令牌只显示一次；泄露就在 MCP 页「撤销」重新配对。
- `project_scope` 在服务端强制：限定项目的令牌查不到其他项目内容。
- 只读：没有任何写/执行类工具。Agent 派单不经过 MCP。
- `adb forward` 只绑电脑回环；不要把 `adb tcpip` 端口开到公网。
