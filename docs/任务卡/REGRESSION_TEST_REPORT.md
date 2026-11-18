# 回归测试报告

2026-11-18 · 本机 `:shared:jvmTest` 全量 + app 单测

## 26 个存量失败：归因与修复

**结论：全部修复，非业务逻辑问题。**

| 类 | 用例数 | 原失败数 | 归因 |
|---|---|---|---|
| `digest.core.PromptParity152Test` | 14 | 11 | 金色文件 CRLF vs 生成 LF |
| `ask.AskParity153Test` | 13 | 1 | 同上 |
| `skills.core.Parity160Test` | 20 | 14 | 同上 |
| **合计** | **47** | **26** | — |

**证据链**：诊断对比显示首差异在第 5 字节（标题后第一个换行）；期望资源字节含 `13,10`（CRLF），实际产出无 CR。测试时钟固定注入（`LocalDate(2026,10,28)`、`Instant.parse("2026-10-28T...Z")`），**不是日期炸弹**——是 Windows 检出把 LF 金色文件转成 CRLF 导致逐字节比较失败。

**修复方式**：三个测试类的资源读取处统一 `.replace("\r\n","\n")` 归一化后再断言。语义不变（只容跨平台换行差异），无测试被删除或跳过。

**当前**：`:shared:jvmTest` 全量 **248/248 通过，0 失败**。

## 本轮新增/变更测试

| 测试 | 结果 |
|---|---|
| `qa.QaRulesTest`（新增 7 例：时间窗/状态词/主题词剥离） | 7/7 PASS |
| `M2PlusMigrationTest`（含 36→37 `extracted` 列迁移） | 4/4 PASS（Robolectric） |
| `McpServerDeviceTest`（真机：无令牌/坏令牌/tools/list/中文 body/未知工具/白名单/项目越权） | 1/1 PASS |

## 未跑的检查

- `python3 tools/check_shared.py`：本机无 Python —— **NOT_RUN**。本轮 shared 改动均为 Room DAO/Kotlin 纯逻辑，无 `android.*`/`java.*` 新依赖（QaHints 用 `Regex`/kotlin 标准库；CollectData 新增字段+DAO 查询）。
- `zsh tools/check_ios.sh`：本机无 zsh/Xcode —— **NOT_RUN**。
- 全量 `:app:testDebugUnitTest`：仅跑了迁移相关类（Robolectric 全量耗时长，按约定只跑受影响项）。
