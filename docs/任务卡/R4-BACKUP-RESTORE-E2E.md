# R4 备份恢复完整性验证

## 结论：`BACKUP_RECOVERY_GO`（DB 层）；真机文件层随 R4 门禁另验

## 覆盖范围

`app/src/test/.../backup/BackupRestoreE2ETest.kt`（Robolectric + 原生 SQLite，7/7 通过）：

| 用例 | 验证点 |
|---|---|
| `allClassifiedTablesRoundTripByteIdentical` | 造库覆盖 16 张补齐表 + segments/env_sounds，跨两天 → `DayVolumeRows.write` → `rebuild` → 逐表 COUNT 相等 → 对恢复库再分卷，**与原分卷逐字节相等**（主键/列值/UTF-8 全保真），录音路径等文件引用字段保留 |
| `missingDayVolumeLosesOnlyThatDay` | 删掉 2025-11-17 卷：该天 rows 缺失，其余天与全局卷（projects/links/templates/endpoints/rules/clients）无损 |
| `corruptedVolumeFailsRestoreAndLeavesNoMixedState` | 破坏 JSONL 行 → rebuild 抛错 → 恢复库零残留行（事务回滚，不留混合态）；原库行数不变 |
| `danglingForeignKeyRollsBackWholeRestore` | 注入 env_sounds→segments 悬空外键 → `foreign_key_check` 拦截 → 全量回滚为空库 |
| `legacyBackupWithoutNewTablesRestoresAndLeavesThemEmpty` | 模拟修复前旧备份（剔除全部新表行）→ 恢复成功；**16 张新表明确为空 = 不可恢复清单** |
| `secretsExportToSecretsJsonNeverToPlainSettings` | mcp_/endpoint-/encryptedKey 明文只进 `secrets.json`；`settings.json` 无密文值、无密钥键名 |
| `encryptedBackupMagicAndRoundTrip` | `RESOUND-ENC-1` 魔数、AES-GCM 口令往返、错误口令 `口令不对` 且不留半成品 |

## 表 → 卷 → 恢复方式映射（当前 schema v41）

- **按天分卷**（`DayVolumeRows.dated`，含补齐）：topics、topic_spans、topic_marks、import_records、segments、transcript_lines、memory_events、thing_records、memory_items、skill_cards、reviews、ask_conversations、ask_messages、asr_attempts、asr_usage、llm_usage_v2、skill_runs、app_power、battery、heartbeats、watchdog、env_sounds、location_points、**ideas、project_marks、analysis_runs、agent_tasks、decisions、followups、capture_items、rule_runs、memory_revisions、scene_sessions**
- **全局卷 state.jsonl**（`DayVolumeRows.global`）：import_fingerprints、folder_observations、device_transfers、things、thing_watch_state、voiceprint_people、voiceprint_samples、voiceprint_exclusions、location_places、**projects、project_links、analysis_templates、agent_endpoints、workflow_rules、mcp_clients**
- **不备份（可重建/本机态）**：lines_fts、events_fts、items_fts（恢复时 SearchIndex 重建）、embeddings、redo_snapshot、privacy_file_cleanup、digest_pending
- **偏好**：`BackupSettings.capture` → `settings.json`（分类白名单键）+ `secrets.json`（解密后密钥值，`agent-secrets`/`mcp-secrets` 已在本轮归入密钥容器）
- **审计门禁**：`BackupAudit087Test.everySchemaTableHasAnExplicitBackupPolicy`（每张 schema 表必须出现在 dated/global/豁免清单，新表不登记即红）+ `PreferenceBackup190Test`（每个生产 prefs 文件必须分类）

## 旧备份不可恢复清单（诚实声明）

**凡在 `eda94651` 修复之前生成的日分卷备份，以下 15 张表的数据从未被写入，无法恢复**：ideas、project_links、project_marks、analysis_templates、analysis_runs、agent_endpoints、agent_tasks、decisions、followups、capture_items、workflow_rules、rule_runs、memory_revisions、mcp_clients（scene_sessions 随场景功能同期加入，同样缺失）。旧备份恢复后这些表为空表，其余数据完好。

## 恢复失败回滚机制（既有设计，已验证）

- `DayVolumeRows.rebuild` 在单一 `immediateTransaction` 内插入 → `foreign_key_check` + `integrity_check` 通过才提交；任何损坏/缺行/悬空外键整批回滚。
- 增量包恢复（`IncrementalBackup.verify`）先在工作目录重建并校验 `resultSha256`，通过后才 stage `backup-restore`；篡改包不触碰在用库（`tamperedPageFailsResultHashAndNeverStagesRestore` 既有回归）。

## 未覆盖（保留门禁）

- 真机上的完整备份→清除→恢复→重启验收（SAF/WebDAV 实路）——列入真机门禁项。
- `sqlite_sequence`/AUTOINCREMENT 水位在旧备份恢复后的续号——本测试库按显式主键恢复，未专测。
