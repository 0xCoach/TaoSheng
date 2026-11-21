# R4 发布门禁（终版）

日期：2026-11-19。HEAD：`f2620f38`。分支：`dev/m0-m1-m2`。

本卡记录 R4 发布阻塞项清零专项的最终判定。文档矩阵：
`R4-LICENSE-AUDIT.md`、`R4-MCP-SCOPE-REGRESSION.md`、`R4-BACKUP-RESTORE-E2E.md`、
`R4-37-FAILURES-TRIAGE.md`、`R4-发布门禁-清单.md`、`R4-隐私安全-交付说明.md`。

## 自动化门禁（全部可执行项已完成）

| 检查 | 结果 | 证据 |
|---|---|---|
| LICENSE / 第三方声明 | **PASS** | `7d187803`：LICENSE.md 取自 v0.4.0 tag（FSL-1.1-MIT 全文）；THIRD_PARTY_NOTICES.md 覆盖上游版权与全部依赖/二进制；`LicenseGateTest` 防再删 |
| MCP 权限全入口回归 | **PASS** | `549326aa`：修 `followup_list`/`decision_list` 两处越权；`McpScopeTest` 10 项负向用例覆盖按 ID/搜索/列表/跨项目/无效 token |
| 备份恢复完整性 | **PASS** | `5c51fa79`：`BackupRestoreE2ETest` 7 用例——全表逐字节、缺失/损坏/悬空外键、旧备份缺新表明确清单、密钥不入明文、加密口令往返、回滚不留混合态 |
| 37 项遗留测试 | **PASS（清零）** | `f2620f38`：26 PRODUCT（renameTo/AtomicFile 覆盖写、`/` 分隔符）、2 LEGACY（`回响` 前缀丢失）、5 TEST_BUG、4 ENV；全量 1248 测试 0 失败 |
| 编译 | PASS | `assembleDebug` BUILD SUCCESSFUL |
| 数据库升级/迁移 | PASS | 迁移测试（`CaptureDatabase.VERSION` 断言）+ `BackupPrompts029` v10→v41 迁移恢复 |
| 本地 ASR 离线 | PASS | `LocalAsr*` 系列单测全绿；真机离线 E2E 见下（NOT_TESTED） |
| 录音文件完整性 | PASS（单测层） | `SegmentFiles`/`SampleAudioStore` 等覆盖写原子性修复 |
| Agent 权限/幂等 | PASS | `AgentBridge`/`AgentDispatchWork` 既有用例全绿 |
| 更新包校验 | PASS | 签名 + sha256 校验用例全绿 |
| APK SHA256 | — | `app-debug.apk` `548CDC4B9F6722F89ADA639A7A8D66D2E94165596C0DA5BBFA14CCD2FDE15FDF` |

## 真机/长时门禁（未执行项如实标记）

| 检查 | 状态 |
|---|---|
| OPPO 装机冒烟 | PASS（此前 `1a690f4d` 记录：装机 + 服务自启动无崩溃） |
| 严格断网 E2E | **NOT_TESTED** |
| 长时间后台录音 | **NOT_TESTED** |
| 麦克风冲突恢复 | **NOT_TESTED** |
| 72h Soak / 发热电池内存 / 转写积压 | **BLOCKED**（需真机实测） |

## 最终判定字段

- `LICENSE_STATUS`：**GO**
- `MCP_SECURITY_STATUS`：**PASS**
- `BACKUP_RECOVERY_STATUS`：**PASS**（旧备份中 15 张新表不可恢复——属既定事实并已明示）
- `TEST_SUITE_STATUS`：**PASS（1248/0 失败）**
- `OFFLINE_E2E_STATUS`：**NOT_TESTED**
- `72H_SOAK_STATUS`：**BLOCKED**
- `R4_RELEASE_VERDICT`：**NO-GO（可转入真机长时验收）**

判定规则说明：全部"可通过代码与自动化完成"的硬门禁已 PASS；剩余 BLOCKED/NOT_TESTED
均为真机长时项，按规则不得以代码审计代替，故不宣布 R4 发布。当前状态已达
**"可以进入真机长时验收"** 的准入条件。
