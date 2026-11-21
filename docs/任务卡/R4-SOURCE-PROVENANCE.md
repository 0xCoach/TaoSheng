# R4.1 源码来源审计（R4-SOURCE-PROVENANCE）

> 基线：上游 `v0.4.0` tag（`ddd3306a`，FSL-1.1-MIT 生效版本，HEAD 的直接祖先）。
> 当前：`5438632b`（`dev/m0-m1-m2`）。分叉点 = v0.4.0，其后 43 个提交全部为本项目开发。
> 方法：双树逐文件 blob SHA 比对 + `git diff -M40 -C40 --find-copies-harder` 改名/复制检测 + 逐文件行数 churn 统计。所有结论可复算（命令附后）。
> 上游 v0.4.1/v0.4.2/v0.5.0 tag 不在 HEAD 祖先链上，属上游分叉后的独立演进，不计入。

## 一、总量结论

| 分类 | 文件数 | 占 HEAD 树 | 说明 |
|---|---|---|---|
| ORIGINAL_FSL（与 v0.4.0 逐字节相同） | **2876** | 82.9% | 原样保留的上游 FSL 代码/资源 |
| MODIFIED_FSL（同路径、内容有改） | **488** | 14.1% | 上游文件的修改版；绝大多数为个位数行机械改动 |
| ADDED（v0.4.0 不存在） | **104** | 3.0% | 本项目新增 |
| DELETED | 10 | — | 上游流程/文档文件（CLA.md、NOTICE.md、CHANGELOG.md、README_EN.md、cla.yml、发布脚本×2、许可证公告、媒体视频×0） |
| 改名检出 | 1（媒体 mp4） | — | `docs/media/taosheng-film.mp4`→`soundsound-film.mp4` |
| 复制检出（-C40） | 7 | — | 全部是 Room schema JSON 自动产物（34→35..41.json），非源码 |

**核心答案：FSL 授权范围内的源码绝大多数仍在原样或近原样使用。** 改名、换主题、加功能都不改变这一点——上游代码占本仓库源代码的 ~94%（按文件数）。

## 二、源码（.kt/.java）分模块明细

app + shared + devices 共 1089 个 Kotlin/Java 文件：

| 模块 | 逐字节相同 | 有修改 | 本项目新增 | 合计 |
|---|---|---|---|---|
| app | **564** | 132 | 41 | 737 |
| shared | **248** | 29 | 28 | 305 |
| devices | **47** | 0 | 0 | 47 |
| 合计 | **859 (78.9%)** | 161 (14.8%) | 69 (6.3%) | 1089 |

### 2.1 修改文件的改动幅度（172 个源码/资源文件）

| 改动量（增删行/文件行数） | 文件数 | 典型 |
|---|---|---|
| <2% | 26 | 单行改（图标引用、常量） |
| 2–5% | 62 | 机械改（品牌字样、包内引用） |
| 5–15% | 57 | 小幅功能改 |
| >15% | 27 | 实质性修改（见下） |

实质性修改（churn≥15%）的文件：UpdatePopupRule（整文件重写）、Theme.kt（主题换色 48%）、AboutPage.kt（36%）、LocalAsrChoices.kt（68%）、CaptureDatabase.kt（26%，新实体/迁移登记）、AppGraph.kt（18%）、HealthWatchdogRules.kt（33%）、UpdatePage/UpdateManifest（更新源自配置化）、SegmentFiles.kt、MicYield.kt、BackupRestore.kt、OverviewCard.kt、SettingsScreen.kt 等。**即便这些文件也不构成"独立实现"——它们仍是上游文件的修改版。**

### 2.2 用户点名的"未专项重写"文件——SHA 实证

以下文件 HEAD 与 v0.4.0 **blob SHA 完全相同（逐字节未改）**，即原封不动的 FSL 代码：

`PipelineProgress.kt`、`AndroidPipeline.kt`、`PipelineDiagnostics.kt`、`PipelineScheduleStore.kt`、`PipelineScheduler.kt`、`shared/search/RecordSearch.kt`（改动 11%，仍为修改版而非重写）、`ui/today/RecordSearchSheet.kt`、`ui/today/HomeNotices.kt`（10%）、`shared/diagnostics/FailureNotes.kt`、`shared/digest/DigestProgress.kt`、`shared/scheduling/PipelineCoordinator.kt`、`PipelineDue.kt`、`PipelineGate.kt` 及配套测试。

**此前"约 20 个增量功能文件未专项重写"的记录属实。**

## 三、本项目新增（INDEPENDENT 候选）

69 个新增源码文件（含 22 个测试），全部产生自本仓库 43 个提交，可按提交逐文件溯源（`git log -- <file>`）：

- **业务新增**：agent(2)、analysis、collect、decision、followup(2)、inbox、mcp、projects、qa、revise、rules(2)、capture/AndroidScene、transcribe/TranscriptReviewRepository、util/AtomicFiles
- **shared 新规则**：AgentData、AnalysisData、SceneEngine、CollectData、MemoryValueRules、SceneSessions、TemporalGuard、DecisionData、FollowupData、IdeaData、McpData、ProjectData、EvidenceGuard、QaHints、RevisionData、RuleData、TranscriptReviewRules
- **UI 新增**：assistant/（8 屏）、agent/、analysis/、inbox/、projects/、verify/SceneSection

判定为 **INDEPENDENT（本仓库可核查独立开发）**，但注意两点：(a) 它们调用上游基础设施（AppGraph、CaptureDatabase、Contract），是衍生生态内的模块，不是脱离上游的独立作品；(b) git 历史只能证明"在本仓库内何时由谁提交"，法律意义上的 clean-room 独立实现需要更严格的来源证明，单靠 git 不足以支撑商业发布的许可清零。

## 四、第三方与二进制

- 随包二进制：`assets/silero_vad.onnx`、`device-cards-public-key.hex`、device JSON、提示词/skills md、asr-test.ogg——**全部与 v0.4.0 逐字节相同**；`THIRD_PARTY_NOTICES.md`（新增）已覆盖其授权。
- `.so/.aar/.jar` 不入库，经 Gradle 依赖引入（sherpa-onnx、onnxruntime、MLKit、Room、Ktor、serialization 等）→ THIRD_PARTY 类，清单见 `THIRD_PARTY_NOTICES.md`。
- LICENSE.md 已恢复 FSL-1.1-MIT 全文（与上游差一个 UTF-8 BOM，`eb77ae66` vs `987bca90`）。
- 上游 `NOTICE.md` 被删除，其内容已由 `THIRD_PARTY_NOTICES.md` 承接；`LicenseGateTest` 防再删。

## 五、风险与残留 UNKNOWN

- UNKNOWN：**0**。全部 3468 个文件均可落入四类之一（工具链/IDE 文件未计入，见复算脚本范围）。
- 删除的 `NOTICE.md`/CLA/CONTRIBUTING 属流程文档，无合规残留；`publish_update.py` 删除属于功能裁剪（上游自动发布）。
- 残留 FSL 面 ≈ 全部 app+devices 源码（859 同 + 161 改）。若未来目标是 FSL 代码清零，需另立"有来源记录的独立实现"专项；按 FSL-1.1-MIT，对衍生作品的许可义务不随改写比例消失，商用前需独立法律审查。

## 六、复算命令

```powershell
git ls-tree -r v0.4.0 --format='%(objectname) %(path)'   # 基线树 3374
git ls-tree -r HEAD    --format='%(objectname) %(path)'  # 当前树 3468
git diff --name-status -M -C40 --find-copies-harder v0.4.0 HEAD   # M488 D9 A96 R1 C7
git diff --numstat v0.4.0 HEAD   # 逐文件 churn
```
分类规则：同路径同 SHA→ORIGINAL；同路径异 SHA→MODIFIED；新路径无 C 检出→ADDED/INDEPENDENT；C 检出→标注来源。

## 七、判定

**FSL_SOURCE_INVENTORY = 2876 同 + 488 改 + 104 增（源码口径：859 同/161 改/69 增）**
**INDEPENDENT_IMPLEMENTATION_COVERAGE ≈ 6.3%（按文件数）**
结论：SoundSound 是「上游 FSL 代码 + 合规声明 + 6% 自主新模块」的衍生作品，**未重写、也不应以"重写率"宣称清零**；个人二开在已恢复的授权声明下合规。
