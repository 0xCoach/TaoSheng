# 101 · minSdk 降到 28（兼容 Android 9）

## 背景与目标

负责人有一台明确的 Android 9 设备要装（arm64-v8a）。
目标：把 `app/build.gradle.kts` 的 `minSdk` 从 **29 降到 28**，让 App 能在 Android 9 上编译、安装、不崩，核心功能（后台录音、本地 CPU 转写、推送提醒）能跑。

已确认的前提（负责人拍板）：

- 设备 ABI 是 **arm64-v8a** → `ndk { abiFilters += "arm64-v8a" }` **不改**。
- 转写走 **本地 CPU**（目标设备芯片非 SM8650，`npuSupported()` 自动返回 false，退回 SenseVoice CPU）→ 本地模型下载链路要能在 9 上跑通。
- **targetSdk = 35 保持不动**，只降 minSdk。

分工边界（AGENTS.md）：本卡属于底层改动。**不碰 `ui/` 包和 `MainActivity.kt`**。界面侧涉及的 lint 修复写进交付说明，交给界面侧（Claude）处理，不在本卡改。

## 必做改动

### 1. build.gradle.kts
- `minSdk = 29` → `minSdk = 28`。
- `targetSdk`、`compileSdk`、`abiFilters` 不动。

### 2. 后台前台服务（硬坎，API 29 起才有的三参 startForeground / 前台服务类型）
按版本分叉，28 走双参 `startForeground` / 双参 `ForegroundInfo`：

- `capture/CaptureService.kt`：`startForeground(id, notif, FOREGROUND_SERVICE_TYPE_MICROPHONE)` 在 `SDK_INT < 29` 时改用双参 `startForeground(id, notif)`（加 `@Suppress("DEPRECATION")`）。
- `transcribe/TranscribeWork.kt`、`importer/ImportWork.kt`、`review/ReviewPreparation.kt`、`transcribe/local/LocalModels.kt`：`ForegroundInfo(id, notif, FOREGROUND_SERVICE_TYPE_DATA_SYNC)` 在 `SDK_INT < 29` 时改用双参 `ForegroundInfo(id, notif)`。
- `AndroidManifest.xml`：`foregroundServiceType`、`FOREGROUND_SERVICE_MICROPHONE`、`FOREGROUND_SERVICE_DATA_SYNC` 等高版本属性/权限**保留不删**（28 上系统忽略，高版本仍需要）。`FOREGROUND_SERVICE`（不带后缀）保留。

### 3. 高版本 API 守卫补注解（非界面文件）
这些地方运行时已有 `if (SDK_INT >= N)` 守卫，逻辑不改；minSdk=28 后 lint 基线变了会报 NewApi。
修法：把调用新 API 的部分抽成私有方法并加 `@RequiresApi(N)`，调用处仍按 SDK_INT 分支（优先此法，比 `@SuppressLint` 干净）。

需要处理（非 ui/ 的文件）：
- `audio/ExternalAudioSource.kt` — availableCommunicationDevices / setCommunicationDevice / clearCommunicationDevice（31）
- `audio/OggGuard.kt` — getHistoricalProcessExitReasons / ApplicationExitInfo（30）
- `push/StrongReminders.kt` — canScheduleExactAlarms（31）
- `push/PhoneReminders.kt` — canScheduleExactAlarms（31）
- `digest/AndroidDigestTiming.kt` — canScheduleExactAlarms（31）
- `push/AlarmPlayer.kt` — VibratorManager（31）
- `push/StrongSetup.kt` — canUseFullScreenIntent（34）/ 精确闹钟设置（31）
- `transcribe/local/LocalModels.kt` — Build.SOC_MODEL（31）
- `capture/AndroidVoiceInputOcr.kt` — 截屏识字（30）
- `capture/VoiceInputService.kt` — maximumWindowMetrics 等（30）
- `capture/PlaybackMonitor.kt` — AudioPlaybackConfiguration.audioDeviceInfo（33）

### 4. 依赖 minSdk 冲突
`assembleDebug` 若报 manifest merger 某依赖要求 minSdk ≥ 29，记录是哪个依赖；能降版本兼容就降，不能就在交付说明里标记为阻塞，交负责人定。**不要**全局 `lint { disable += "NewApi" }`。

## 界面侧待办（本卡不改，写进交付说明）
以下 `ui/` 文件在 minSdk=28 后也会被 NewApi lint 标，交界面侧（Claude）处理：
- `ui/verify/InputSection.kt`（31）
- `ui/verify/VerifyScreen.kt`（33）
- `ui/settings/DigestTimingCard.kt`（31）
- `ui/alarm/StrongAlarmActivity.kt`（27，低于 28，实际大概率无需改，列出待确认）

## 验证
- `./gradlew assembleDebug` 必须通过（这是硬指标）。
- 跑受影响的现有单元测试（前台服务 / 录音 / 推送相关），不跑全量。按 AGENTS.md，一张卡新增测试 5~15 个即可；本卡以"改兼容、不改逻辑"为主，能不新增就不新增，重点是编译与既有测试不回归。
- 真机实测项写进交付说明，交负责人在目标 Android 9 设备上做：
  - 整晚后台录音不断（注意 Android 9 的 Doze / 后台前台服务管控比 10+ 严，可能更易被杀——这是系统差异，属已知风险）。
  - 本地 CPU 转写：模型下载 + 转写出文字。
  - 推送提醒（PushPlus / 每日回顾）能到。

## 追加需求：Android 9 手动让出麦克风
Android 10 之前同一时间只能一个应用录音，涛声占着麦时别的应用抢不走，系统也不通知我们，无法自动让。改为手动让出，且只影响 Android 9（SDK<29），Android 10+ 行为不变：
- `capture/CaptureNotifications.kt`：仅 SDK<29 常驻通知按钮改成"让出麦克风/继续"，复用现有暂停（PAUSE/RESUME），到点由 alarm 自动恢复；让出时长读 `capture` 偏好 `yieldMinutes`（默认 30 分钟，范围 1~10080，界面侧可写）。
- `audio/ExternalAudioSource.kt`：仅 SDK<29 非蓝牙时 `open()` 只用标准格式（16kHz/单声道/MIC）试一次，失败即判被占、整轮退出，不再遍历几十种组合刷系统"XX 正在录音"弹框。
- `CaptureTuning.kt` + `capture/CaptureService.kt`：被占用重试间隔按版本分叉，SDK<29 用新增 `LEGACY_MIC_RETRY_MS`=10 秒防刷屏，SDK>=29 仍用 `MIC_RETRY_MS`=3 秒。
- 界面侧待办：设置页加"让出麦克风时长"入口，写入 `getSharedPreferences("capture").putInt(CaptureNotifications.YIELD_MINUTES, 分钟)`。
- 真机实测（目标 Android 9 设备）已过：让出→别的应用能录→继续时对方在录则进被占用重试（每 10 秒一次，不刷屏）→对方停后最多 10 秒自动接回；让出时长到点（1 分钟验证）准时自动恢复。详见交付说明。

## 已知残留风险
即便兼容完成，Android 9 上整晚录音可靠性仍低于 10+（系统后台管控差异），代码层面解决不了，仅能做到"能装能跑"。

## 交付
- 独立分支/worktree，中文提交，不推远端。
- 交付说明写 `docs/任务卡/101-交付说明.md`：做了什么、怎么验证、真机注意、界面侧待办、遗留问题。
