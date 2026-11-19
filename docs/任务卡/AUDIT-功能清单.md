# 声声不息 功能验收与代码审计（基线 `0287254a` / 分支 `feature/auth-gate`）

> 审计方式：逐个读源码核对，不凭提交说明下结论。上一版清单里有 **2 处误判**已在第 0 节更正。
> 图例：✅ 实现且验证过｜⚠️ 部分实现｜❌ 未做｜🧪 代码在但没真机验证｜🔴 代码有缺陷（需修）
> 优先级：**P0** = 丢数据/越权/锁死用户/违反硬约束；**P1** = 功能不按承诺工作；**P2** = 体验/整洁

---

## 0. 对上一版审计的更正

| 原结论 | 实际 | 依据 |
|---|---|---|
| 「AuthStore.kt / wrangler.toml 中文注释乱码」🔴 | **误报，文件完好** | 是 PowerShell 按 ANSI 显示 UTF-8 造成的假象；全仓扫描乱码特征字符 0 处 |
| 「换账号数据隔离 ⚠️ 写了没测」 | **实际没生效，见 P0-4** | `bindCurrent()` 全仓无调用方 |
| 「PENDING 失败可恢复 ✅（4d06bddf）」 | **修复本身有缺陷，见 P0-1** | `lastRunAt` 在失败时也会写入 |

---

## 1. P0 缺陷（必须先修）

### P0-1 安静整理的 PENDING 修复无效：整理失败也会被当成已成功
- 位置：`app/.../digest/QuietDigestWatcher.kt` 第 53–58 行；`DigestRunner.kt` 第 382–385 行 `record()`
- 原因：Watcher 用 `lastRunAt >= quietPendingAt` 判断成功。但 `DigestRunner.record(at, error)` 在**失败时也写 `lastRunAt`**：`needsUser()`（没填密钥、本地存储失败、模型拒收）和 `runBatches` 里的「等待网络」/网络/限流/格式错误都会调 `record(now(), reason)`。
- 后果：AI 失败或断网后，pending 被当成已完成，进入冷却 → **还是会漏整理**。`4d06bddf` 想修的问题没修好。
- 修法：成功判据改成 `lastRunAt >= pendingAt && lastError == null`，或者让 DigestRunner 单独写一个 `lastSuccessAt`（只在 `Done/NothingToDo` 时写）。补测试：失败 → pending 保留 → 15 分钟后重试 → 成功后才进冷却。

### P0-2 安静整理绕过了「防重复付费」保护
- 位置：`QuietDigestWatcher.tick()` 调 `scheduler.runDigestNow()` → 打上 `MANUAL_DIGEST` 标签 → `DigestRunner.runOutcome(manual = true)`
- 原因：`resolveInFlight(undigested, manual)` 里有一条规则：上次请求的结果没保存下来时**自动任务要暂停**，只有用户手动点「现在整理」才能清掉。自动的安静触发被当成手动，这条保护就失效了。同时技能调度 `afterDigest(manual=true)` 也会跳过最短间隔。
- 违反：AGENTS.md「保留每次请求的唯一凭据……防重复付费，不能删掉或弱化」。
- 修法：TranscribeScheduler 新增一个自动整理入口（例如 `runDigestAuto()`，带 force=true 但 manual=false），安静触发走这个入口。

### P0-3 敲敲手势可以绕过隐私暂停
- 位置：`KnockGestureController.onKnock()` 第 42–46 行 + `CaptureService` 第 193–203 行
- 原因：隐私暂停写的是 `manualPausedUntil = Long.MAX_VALUE`。敲敲只看 `manualPausedUntil > now`，判成「手动暂停」→ 发 `RESUME`；而 `RESUME` 会把 `KEY_PRIVACY` 置为 false 并恢复录音。
- 后果：隐私暂停中，**敲两下就恢复录音**。需求原话是「PRIVACY_PAUSED → 不得绕过隐私暂停」。
- 修法：onKnock 先读 `AndroidScene.KEY_PRIVACY`，为 true 时什么都不做（给失败震动）；更稳妥的是 CaptureService 对 `route=knock` 的 RESUME 拒绝清除隐私标志。补测试。

### P0-4 账号数据隔离实际不生效
- 位置：`AuthStore.bindCurrent()`（全仓无调用）；`AuthContract.kt` 第 52 行的隔离判断依赖 `boundAccountId != null`
- 后果：`boundAccountId` 永远为空，隔离判断永不触发。**任何能登录的账号都能看到本机全部历史录音和记忆**。上一版写的「bound_account_id 跨账号隔离」不成立。
- 修法：做「旧数据归属确认」流程（首次登录弹确认 → 先备份 → 用户确认后才 `bindCurrent()`），未确认前对已有数据按未绑定处理；补「A 登录绑定 → 退出 → B 登录 → B 读不到」的测试。

### P0-5 装这个 APK 后，负责人本人会被锁在门外
- 位置：`ResoundApp.kt` 第 85 行 AuthGate 总是装配；`AuthGateRules.decide()` 没有凭证时一律拒绝；`CaptureService` 第 107–113 行被拒时把 `enabled` 写成 false
- 后果：覆盖安装后，在登录前：录音不能启动（打开 App 会自动重启录音，被拒后连「上次开着」的标记也被清掉）、个人数据读不到、跟进/工作流/Agent/MCP 全停。登录又必须联网且依赖 Cloudflare 在线，第一次登录时如果服务挂了就完全用不了。
- 说明：这和需求「游客不能读取个人数据」一致，但需求同时要求「旧数据需要一次性迁移与归属确认」，而这个流程不存在（P0-4）。现状等于强制登录、而且没有迁移引导。
- 修法：在归属流程完成前给已有安装保留「本机所有者」模式（或首次升级强制走归属引导）；发版前必须先在真机上验证升级路径。

### P0-6 测试账号密码写进了已提交的文档
- 位置：本文件上一版第 99 行，以及对话记录
- 后果：`dev-admin` 拥有全部 6 项能力，部署在公网 `soundsound-auth.kan831019.workers.dev`。
- 修法：立刻调用 `/admin/accounts/status` 停用 `dev-admin`，或者换新密码；本文件已删除这个密码。另外离线凭证用的是**开发签名密钥对**，正式发版前必须轮换（轮换后旧 App 内置的公钥要一起更新）。

---

## 2. P1 缺陷（功能和承诺不一致）

| # | 问题 | 位置 | 修法 |
|---|---|---|---|
| P1-1 | **watcher 一抛异常 App 就崩溃**：`scope = SupervisorJob()+IO`，没有异常处理器；`tick()` 里的数据库查询一旦抛异常，就会变成未捕获异常导致进程崩溃，循环也随之停止 | `QuietDigestWatcher.start()` | 循环体加 `try/catch`（CancellationException 重新抛出），失败记到 FailureNotes |
| P1-2 | **功能关着也在耗电**：`collect()` 先跑 5 个查询再判断开关；`undigestedLines()` 每分钟把所有未整理的句子整表读进内存 | `QuietDigestWatcher.collect()` | 先读开关，关闭时直接返回；改成 `COUNT`/`EXISTS` 查询；只在录音服务运行时启动循环 |
| P1-3 | pending 未满 15 分钟时会掉进常规判断，每 60 秒重复调用 `runDigestNow()`（REPLACE/APPEND 反复替换任务），因为冷却锚点要等成功后才写 | `tick()` 第 54–72 行 | 有 pending 且未满 RETRY_MS 时直接 return |
| P1-4 | 重试分支不检查开关/暂停/隐私暂停/是否在聆听：用户关掉功能或进入隐私暂停后仍会重试整理 | `tick()` 第 60–63 行 | 重试也走 `QuietDigestRules.evaluate`，只放宽冷却条件 |
| P1-5 | 「隐私暂停」判断错误：`pausedUntil != null` 时手动暂停也被显示成隐私暂停 | `collect()` 第 89 行 | 改读 `AndroidScene.KEY_PRIVACY` |
| P1-6 | **在设置里改敲击次数或灵敏度不生效**：`sync()` → `sensor.start()` 在已监听时直接 return；`updateConfig()` 没人调用，而且会忽略灵敏度变化 | `KnockSensor.start()/updateConfig()` | 已在监听时先 stop 再 start，或重建 detector |
| P1-7 | 没开录音时，敲敲也一直以约 50Hz 采样加速度计，而安卓 9+ 的后台 App 本来就收不到事件 → 白白耗电、功能也不起作用 | `KnockGestureController` 在 Application 启动时注册 | 只在录音服务运行（录音中/暂停中）时注册；「未录时敲一下启动」如实标为不支持，改用磁贴兜底 |
| P1-8 | 敲敲没有任何反馈：没有震动，也没有提示，用户不知道到底暂停了还是开始了 | 需求第七节 | 开始震 1 次、暂停震 2 次、失败用不同的震动模式 |
| P1-9 | 敲敲设置区钉在录音页列表**下方**，没有为底部导航栏留空间，很可能被底栏挡住；打开后的选项块会挤占列表区域 | `MainActivity` 中 `SettingsPage.Capture` | 改成 VerifyScreen 列表里的一项，或者加 `bottom` 内边距；真机截图确认 |
| P1-10 | 「我的账号」里的「设备管理」按钮是空回调，点了没反应 | `MainActivity` 挂载时未传 `onManageDevices` | 接上 `/auth/devices` 列表与撤销页面，或者先隐藏按钮 |
| P1-11 | 游客点受保护按钮 → 登录 → 回放原动作，这条链路**从未在真机上走通过** | `AuthLoginHost` / `AuthGate.afterAuthSuccess` | 真机实测并截图 |
| P1-12 | 游客敲敲或点磁贴启动时，服务被 `startForegroundService` 拉起，但被拒后没调 `startForeground` 就 `stopSelf()`；部分安卓版本会因此抛 "did not then call startForeground" 崩溃（原有 NoPermission 分支也是同样写法） | `CaptureService` 第 107–118 行 | 先 `startForeground` 一个最简通知再立即停止，或在发出 START 前就在调用侧拦截；真机验证 |
| P1-13 | 全量回归**在敲敲、安静整理、UI 挂载、默认服务地址这几轮改动后从没重跑过**，「1252 全绿」是旧数据 | — | 见第 6 节本次重跑结果 |
| P1-14 | 安静整理的 PENDING / 重试 / 冷却路径、敲敲控制器（状态→命令映射）、隐私暂停拦截都**没有自动化测试**；只有 `QuietDigestRules` 7 例和 `KnockDetector` 8 例 | — | 把 Watcher 的判定抽成可注入时钟和存储的纯函数后再补测试 |

---

## 3. P2（体验/整洁）

- `QuietDigestSection` / `KnockSettingsSection` / `AuthGateUi` 直接用 MaterialTheme，没用项目统一的 `Rs.colors`/`RsType`，视觉和其他设置页不一致。
- `QuietDigestSection` 的「上次自动整理」只在进入页面时读一次，之后不会刷新。
- 「我的账号」挂在「模型」设置页底部，入口位置不对，应该放到设置首页单独一行。
- 敲敲在「计划停录时段」中会发 PAUSE（7 天）把计划覆盖掉，下次再敲 RESUME 会提前结束这个停录时段；行为需要确认后写进说明。
- 默认服务地址写死成个人 workers.dev 子域；以后换正式域名需要发新版。
- `MainActivity` 里 `SettingsPage.Devices` 的分支留着，但已经没有入口能进去（死代码，保留是为了以后恢复）。

---

## 4. 功能完成度总表

### 4.1 敲敲控制 / 背部轻敲
| 项 | 状态 |
|---|---|
| 加速度计敲击检测（尖峰+连击窗+冷却），8 个测试 | ✅ |
| 2/3 下、三档灵敏度、设置挂载 | ⚠️ 改设置不生效（P1-6），可能被底栏挡住（P1-9） |
| 录音中暂停 / 暂停中恢复 | 🧪 + 🔴 能绕过隐私暂停（P0-3） |
| 未录时敲一下启动 | ❌ 后台基本收不到传感器事件（P1-7） |
| 震动反馈 | ❌（P1-8） |
| 陀螺仪融合、背部敲击专项识别 | ❌ |
| 11 态状态机 / 手势待命态 + 通知 | ❌ |
| 双击开始、三击暂停组合模式 | ❌ |
| 实时测试校准入口 | ❌ |
| 锁屏/口袋/走路/车载误触率与耗电实测 | 🧪 |
| TapTap(GPL-3.0) 方案研究与来源记录 | ❌ |

### 4.2 安静自动整理
| 项 | 状态 |
|---|---|
| 独立开关/阈值/冷却，不动 DigestTimingContract | ✅ |
| 判定规则（服务健康、说话中、暂停、麦被占、积压、授权、AI 配置），7 个测试 | ✅ |
| 设置页（阈值/间隔/状态/现在整理）挂载 | ✅ |
| 失败后可恢复、不漏整理 | 🔴 P0-1 |
| 复用幂等管线、不弱化防重复付费 | 🔴 P0-2 |
| 低开销 | 🔴 P1-2、P1-3 |
| 异常不崩溃 | 🔴 P1-1 |
| 隐私暂停状态判断 | 🔴 P1-5 |
| 崩溃恢复/并发/幂等测试 | ❌ |
| 「安静触发状态日志」交付物 | ❌（只有界面状态，没有持久化日志） |
| 真机触发验证 | 🧪 |

### 4.3 AUTH 登录授权
| 项 | 状态 |
|---|---|
| Workers+D1：PBKDF2、限流、ES256 双 token、签名离线凭证、撤销、停用、审计 | ✅ 冒烟 22/22；线上建号+登录实测通过 |
| 部署 | ✅ `https://soundsound-auth.kan831019.workers.dev` |
| 管理员接口常量时间比较 | ✅ `timingSafeEq` |
| 服务/控制器/分享/MCP/Agent/备份/Worker/问答硬门禁 | ✅（代码层；真机未验证） |
| STOP/PAUSE 放行、失效后安全收尾、登出停录 | ✅ |
| 登录面板、我的账号挂载 | ✅ 已上屏；🧪 没走通过（P1-11） |
| 账号数据隔离 | 🔴 P0-4 |
| 旧数据归属确认 + 迁移前强制备份 | ❌ |
| 已有安装的升级路径 | 🔴 P0-5 |
| 隐私锁 BiometricPrompt | ❌（只备了存储字段） |
| 设备管理页 | ❌（P1-10） |
| 注册（用户自助） | ❌ 只能由管理员建号；登录面板里有激活码输入框，但首次激活流程没实测 |
| 离线凭证 7 天到期/续期 | 🧪 |
| 生产签名密钥、测试账号清理 | 🔴 P0-6 |

### 4.4 R4.3 会员 / 额度 / 兑换码 / 管理后台 —— **全部未做**
| 项 | 状态 |
|---|---|
| D1 新表：plans、entitlements、usage_ledger、usage_monthly、quota_adjustments、redeem_campaigns、redeem_codes、redeem_records、admin_audit_logs | ❌（现有只有 users / sessions / licenses / audit_log / login_attempts 5 张表） |
| 按权益逐项存储（local_asr_minutes、cross_meeting_tracking、advanced_project_analysis、agent_workflow），不用 is_pro | ❌ |
| Free 每月 1800 分钟：按成功转写时长计量、同一音频只算一次、账期时区、多设备合并 | ❌ |
| 超额后录音照存、新转写排队；到期回到 Free 不删数据 | ❌ |
| 离线用量本地记账 + 联网去重对账 | ❌ |
| 兑换码：**30 天 Pro**（已取代 180 天）、Founder 活动草稿 100 个、启用前不生效 | ❌ |
| 原子核销、幂等请求 ID、一码一账号、已有 Pro 顺延、撤销留痕不删记录 | ❌ |
| Web Admin：管理员登录、仪表盘、用户、设备会话、会员、用量、兑换码、审计、套餐配置；CSRF/XSS/限流 | ❌（目前只有 ADMIN_TOKEN 保护的 3 个 JSON 接口：建号、停用、列表） |
| App 会员中心：套餐、已用/剩余、到期时间、兑换输入、结果反馈 | ❌ |
| 额度检查覆盖自动/手动/导入转写、失败重试、WorkManager | ❌ |
| 支付 | 不做（按决定只预留） |

### 4.5 推送通道（用户提问：是不是多余）
| 项 | 状态 |
|---|---|
| 9 个通道（PushPlus、Server酱、企业微信、飞书、钉钉、Telegram、邮件、Notion、同步文件夹、自定义接口） | 现有功能，可以用 |
| 建议：保留 同步文件夹 / 自定义接口 / 邮件（会议纪要交付要用）；隐藏 PushPlus、Server酱、企业微信、飞书、钉钉、Telegram | ⏳ 等用户确认，未改 |

### 4.6 外接设备
| 项 | 状态 |
|---|---|
| 设置首页、录音页两处入口隐藏（代码保留） | ✅ `959fa88b` |
| 开发者「记录蓝牙通信」随之不可见 | ✅ |
| 驱动真机验证 | ❌ 一个都没有（D3200 社区试用；ESP32-C3、Omi 未验证） |

### 4.7 产品方向调整（会议 / 办公 / 灵感 / 项目分析）—— **全部未开始**
| 项 | 状态 |
|---|---|
| 智能会议模式（主题/项目/参会人/重要标记/会后自动整理） | ❌（已有「专题」能力可以复用，尚未评估差距） |
| 纪要分层输出（摘要/正式纪要/决议/待办/待确认） | ❌ |
| 参会人与负责人识别、无法确认时标「待确认」 | ❌ |
| 跨会议决议追踪 | ❌（DecisionMemory、FollowupEngine 可复用） |
| 会前准备、一键交付（Markdown/PDF/任务表/Webhook） | ❌ |
| 会议录音缺口标注、会后人工补充与原话区分 | ❌ |
| 语音建待办、日报/周报、沟通整理、承诺追踪、工作搜索 | ❌（部分底层已有：待办提取、每日回顾、问答） |
| 灵感追加、灵感发展轨迹 | ❌（IdeaInbox 可复用） |
| ProjectTimeline / MeetingActionTracker / DecisionConflictAnalysis / ProjectRiskAnalysis / ProjectNextActions / ProjectReviewReport | ❌ |
| Free/Pro 按工作流深度划分权益 | ❌（依赖 4.4） |

### 4.8 R4 基础盘（之前已交付）
| 项 | 状态 |
|---|---|
| FSL 许可证恢复、来源审计（约 94% 仍是 FSL 衍生，如实记录）、功能等价 26+3+0+0 | ✅ |
| MCP 越权修复、备份 16 表、Room 原子写、旧品牌备份识别 | ✅ |
| `tools/check_shared.py` | ✅ 本次已跑，通过 |
| `tools/check_ios.sh` | ❌ 本机没有 macOS/Xcode，改过 shared 的提交都没跑过 |
| 商业化前的 FSL-1.1-MIT 竞争性使用审查 | ❌ 收费上线前必须处理 |

---

## 5. 发布门禁（独立于功能完成情况）

| 门禁 | 状态 |
|---|---|
| 严格断网真机 E2E | ❌ NOT_TESTED |
| 72 小时 Soak | ❌ BLOCKED |
| AUTH 真机联调（登录→录音→登出→换账号） | 🧪 |
| 敲敲误触率/耗电 | 🧪 |
| 安静整理真机触发 | 🧪 |
| 升级安装路径（老用户不被锁死、不丢数据） | 🔴 P0-5 |
| **R4_RELEASE_VERDICT** | **NO-GO（维持）** |

---

## 6. 本次全量回归

命令：`gradlew :shared:jvmTest :app:testDebugUnitTest --continue`（JDK 21，本次审计实际运行）

| 项 | 结果 |
|---|---|
| 构建 | BUILD SUCCESSFUL（5 分 13 秒） |
| 用例 | **1552 例（shared + app），失败 0，错误 0，跳过 10** |
| `tools/check_shared.py` | 通过 |
| 服务端冒烟 `server/test/smoke.mjs` | 本次未重跑（上次 22/22；部署后线上实测建号、登录正常） |

**注意**：全绿**不代表**第 1、2 节的缺陷不存在——P0-1 ~ P0-5 都处在没有测试覆盖的路径上（PENDING 重试、自动整理入口、敲敲与隐私暂停、数据归属、升级路径），所以测试测不出来。修复时必须先补会失败的测试，再修代码。

---

## 7. 给修复 AI 的执行顺序

1. **P0-1、P0-2**（安静整理成功判据、自动入口）+ 补 Watcher 测试
2. **P0-3**（敲敲不得解除隐私暂停）+ 测试
3. **P0-6**（停用测试账号；把密钥轮换列入发版清单）
4. **P0-4 + P0-5**（数据归属引导 + 升级路径）——会动 UI，先出方案再改
5. P1-1 ~ P1-7（崩溃、耗电、设置不生效、重复派发）
6. P1-8 ~ P1-12（反馈、布局、设备管理、真机走通登录）
7. 重跑全量回归，更新第 6 节
8. 之后才进入 R4.3 会员后台（4.4）→ 背敲 v2 → 会议/办公/灵感/项目分析（4.7）
