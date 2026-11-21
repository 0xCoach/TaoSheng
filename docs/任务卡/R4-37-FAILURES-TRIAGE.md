# R4 遗留 37 项测试失败逐项归因与修复记录

起始状态：`1a690f4d` 全量 1248 单测中 37 项失败（stash 对照证明非当轮回归）。
终态：**全量重跑 1248 项测试 0 失败 0 错误**（`app/build/test-results/testDebugUnitTest/*.xml` 机器可读报告已保留）。
机器：Windows 11 + JDK 21 + Robolectric（SDK 29、NATIVE SQLite）。Android 真机不受本类问题影响，
但所有产品侧修复均让桌面与设备语义一致或更严谨，没有为通过测试弱化任何校验。

## 分类总览

| 分类 | 数量 | 代表 |
|---|---|---|
| PRODUCT_BUG（Windows 可移植性缺陷，真机 POSIX 不影响但属真实缺陷） | 26 | renameTo 覆盖写、AtomicFile、`/` 分隔符假设 |
| LEGACY_BUG（历史真实缺陷） | 2 | `回响` 前缀识别丢失 + WebDav 旧目录枚举缺口 |
| TEST_BUG（测试自身错误） | 5 | 夹具被更名弄残、陈旧 golden、分隔符假设 |
| ENV（测试环境缺陷） | 4 | Windows MAX_PATH、androidx.core 1.18 `/` 硬编码 |

## PRODUCT_BUG（26 项，全部修复）

**Windows JVM `File.renameTo` 不能覆盖已存在目标**（POSIX 语义为原子覆盖；Windows
直接返回 false 或抛错，导致 check 失败或静默丢写）。修复：新增
`app/resound/util/AtomicFiles.kt` 的 `atomicReplace`（`Files.move` +
`ATOMIC_MOVE`/`REPLACE_EXISTING`，不支持原子时降级 `REPLACE_EXISTING`）与
`atomicWriteFile`（完整保留 AtomicFile 的 `.bak`/`.new`/回滚语义）。受影响站点全部改为
上述助手；`AtomicFile` 读侧保留。

| 失败组 | 站点 | 修复 |
|---|---|---|
| DayVolumes 重发同日卷、Push 同步状态、Subscription 状态、RecordingArchive、LocalSessionPolicy（22 项） | `VolumeStore.put`、`PushSync`、`RecordingUpload`、`LocalSessionPolicy`/`ModelVerification` 等 `renameTo` | `atomicReplace` |
| VoiceprintRepositoryTest ×2（`launchAction` 吞异常 → `schedule()` 未发 → receive 超时） | `SampleAudioStore.save` 的 `check(tmp.renameTo(target))` | `atomicReplace` |
| LlmProviders076Test（删除 provider 后 `overrides.json` 未落盘） | `SkillFiles.write` 的 `AtomicFile.startWrite/finishWrite` | `atomicWriteFile`（保留 .bak/.new 语义），同类站点一并收编：`IncrementalBackup.atomicCopy`、`TopicCheckpointFiles.write`、`LocalPromptTemplates.save`、`BackupRestore.applyContents` |
| LocalAsrFailure063Test（存在的音频被误判、段被放弃） | `LocalAsrFailure.readLocalAudio` 用 `canonicalPath + "/"` 前缀判断 | 改为平台安全的 File 路径关系判断 |

## LEGACY_BUG（2 项，修复）

- **旧品牌「回响-」前缀从 `BackupNames.pattern` 丢失**：更名提交移除了识别表达式里的
  `回响`，导致云端旧品牌备份**既不可见也不可下载**（用户真机上存在这类备份时恢复列表为空）。
  修复：`BackupFiles.kt` pattern 恢复 `回响`；`WebDavClient` 增加第三个**只读**目录
  `回响备份`（枚举/下载可用，上传/删除/retain 明确拒绝），`volumes().legacy()` 一并纳入。
  旧目录逐个容错，任一缺失不拖垮其余目录列表。
- `acceptsHrefVariantsFromServers`：更名提交对夹具做机械替换（期望值换成 `声声不息-`、
  href 仍是 `回响备份/回响-`），恢复原夹具意图（绝对 URL + 声声不息变体），并补第三目录
  的确定性 404 响应。

## TEST_BUG（5 项，修复）

| 测试 | 错误 | 修复 |
|---|---|---|
| WebDavClientTest.acceptsHrefVariants | 更名机械替换弄坏夹具 | 夹具恢复原意图 |
| WebDavClientTest.combinesBothFolders | `backups()` 增至三个目录后响应队列错位（第三个 PROPFIND 抢占 retain 的响应） | 补第三目录响应与 takeRequest 计数 |
| ProblemReportsTest | `canonicalPath + "/"` 分隔符假设 | `Path.startsWith` 组件级判断 |
| CaptureStats177Test | `File.toString` 把 `\` 写进比较文本（夹具为 POSIX） | 比较前统一分隔符 |
| KtorParity151Test | 更名后 golden `Content-Length: 27` 陈旧（`可能出现的词：声声不息`=33 字节） | 改 33 |

## ENV（4 项，已消除/绕过）

| 测试 | 环境根因 | 处理 |
|---|---|---|
| BackupPrompts029Test、IncrementalBackupTest.localRepository…、LocalBackupTest.exportEncrypted… | Robolectric 按 `<类>_<方法>` 造长临时目录，叠加 `no_backup/resound-restore-<uuid>/databases/capture.db-wal` 超过 Windows MAX_PATH(260) → SQLite CANTOPEN | 产品暂存目录名缩短：`resound-restore-<uuid>`→`r-<id>`、`.$id-content`→`.c<13>`（纯内部临时名，校验不弱化） |
| BackupHistoryTest ×2 | 同上，但深路径来自**测试自身**工作区 `cacheDir/history-tests/<uuid>/verified/databases/capture.db`（262 字符） | 测试根目录名缩短为 `h`（逻辑不变） |
| DayDiaryTest.markdownMatchesFullSample… | **androidx.core:core:1.18.0 上游缺陷**：`SimplePathStrategy.belongsToRoot` 用 `path.startsWith(root + '/')` 硬编码分隔符，Windows canonical `\` 路径永远匹配不到根 | 测试中向 `FileProvider.sCache` 注入等价 `PathStrategy`（反射 Proxy）；文件写出、内容、返回 URI 等全部真实断言保留 |

MAX_PATH 证据：`interruptedObjects…` 方法名 71 字符 → 暂存 db 路径 262 字符（>-wal 后 266），
而 60 字符以下方法名的同链路用例路径 ≤255 全部通过；缩短暂存名后全部转绿。

## 验证

- `:app:testDebugUnitTest` 全量重跑：**245 suite / 1248 测试 / 0 失败 / 0 错误**（10 项 skip 为既有条件跳过）。
- 受影响测试分组重跑多次确认非偶发。
- `git diff` 内无临时 println/探针残留。

## 遗留说明

- 真机路径短，本批 Windows 可移植性问题在设备上本不触发；修复属于"桌面/设备语义一致化"，
  不改变 Android 运行语义。
- androidx.core 1.18 `belongsToRoot` 属上游缺陷；若升级 core 时上游修复（使用
  `File.separatorChar`），DayDiaryTest 中的注入策略可移除（移除后 Windows 上应仍能过）。
- 长期机制建议：新增 `*Contract`/Room Entity 或偏好文件时，CI 增加备份覆盖断言
  （`BackupAudit087Test` 已覆盖 Room 表；偏好文件靠 `PreferenceBackup190Test` 的
  `safePrefs` 派生清单兜底）。
