# 卡 139：`:devices` 共用模块骨架 + 搬入纯逻辑 + 自动检查

## 背景

外设改造第 2 步的第一张代码卡。先读：
- `docs/方案/外设驱动插口设计.md`（尤其第 2、3、3.1、6、8 节）
- `devices/src/commonMain/kotlin/app/resound/devices/driver/DeviceDriverContract.kt`（卡 138，Claude 写的约定，**不要改签名**；确需修改在交付说明里提）
- `AGENTS.md` 末尾“为以后 iOS 复用”的 10 条
- `docs/任务卡/137-交付说明.md` 第五节

目标：建好 Kotlin 多平台结构的 `:devices` 模块，让“共用层不许用安卓 / Java 专有东西”由**编译器强制**；把现有设备代码里本来就是纯逻辑的部分搬进去；加自动检查脚本。**App 行为完全不变。**

## 要做的

1. **建模块 `:devices`**（目录 `devices/`，约定文件已在 `devices/src/commonMain/...`）：
   - Kotlin 多平台插件，Kotlin 版本与根构建一致（2.2.20）；AGP 8.11.1、compileSdk 36 不变，不升级 Compose BOM 等无关依赖。
   - 目标：安卓（库）+ `jvm()`（电脑上跑单测）。**暂不加 iOS 目标**（以后单独卡）。安卓目标的写法（`com.android.kotlin.multiplatform.library` 或 `com.android.library` + `androidTarget()`）选与 AGP 8.11.1 兼容、最稳的那种，在交付说明写理由。
   - commonMain 依赖只允许：Kotlin 标准库、kotlinx-coroutines-core、kotlinx-serialization-json（及其编译插件，版本与 Kotlin 2.2.20 匹配）。
   - `:app` 依赖 `:devices`。包名保持 `app.resound.devices...` 不变，这样 `:app` 里的 import 基本不用改。
2. **搬入纯逻辑**（搬到 `devices/src/commonMain/kotlin/app/resound/devices/...`，包名不变）：
   - `DeviceContract.kt`（说明卡模型、文件插口、`DeviceLink`、给界面的类型）——**签名不改**，只是换位置；被 `:app` 用到的 `internal` 声明需改为 `public`，交付说明里列出改了哪些可见性。
   - `core/` 里的 `Crc`、`FrameValidation`、`AudioDecoder`（接口及纯逻辑部分）、`FrameCodec`、`CardParser`、`StrictJson`、`CardSummary`、`NotificationFrames`（若是纯逻辑）。
   - 搬的过程中把 JVM 依赖换成通用写法：`ByteArrayOutputStream` → 自己的字节缓冲；`org.json` → kotlinx.serialization 的 `JsonElement`；JVM 格式化 → 通用函数；`synchronized` → 不需要锁就去掉，需要的话留在平台层。
   - **说明卡解析必须和原来一样严**：重复字段、未知字段、类型不对、超范围等原来拒绝的现在照样拒绝，报错信息含义不变。远程说明卡验签是**对原始 JSON 字节**验签，不许先解析再序列化后验签。
   - `AndroidOpusDecoder` 留在 `:app`（安卓实现），只依赖 `:devices` 里的解码接口。
   - 其余文件（`ManagedDevices`、`CardAudioSource`、`DeviceSync`、`AndroidDevice*`、`RemoteDeviceCards`、`DeviceCardsWork`、`plugins/sonicloud`）**本卡不动**，留在 `:app`，后续卡处理。
3. **测试跟着搬**：`app/src/test/.../devices/core/CardCodec118Test.kt` 以及 `DeviceCards129Test.kt` 中只测纯解析的部分，搬到 `devices/src/commonTest`（或 `jvmTest`，若依赖 JUnit 写法）。测试内容不改、断言不放宽；原来用 `org.json` 造数据的地方改为字符串。依赖安卓 / Robolectric 的测试留在 `:app`。
4. **自动检查脚本** `tools/check_devices.py`（Python 3，无第三方依赖；`python3 tools/check_devices.py` 在仓库根目录运行，有问题非零退出并逐条列出文件和行号）：
   1. `devices/src/commonMain` 下出现 `android.`、`java.`、`javax.`、`org.json`、`okhttp3` 的 import 或全限定引用 → 报错。
   2. 品牌名出现在 `devices/src/commonMain/kotlin/app/resound/devices/plugins/`、`app/src/main/java/app/resound/devices/plugins/`、`app/src/main/assets/devices/`、`docs/`、测试目录以外的 Kotlin 源码里 → 报错。品牌名清单：从 `app/src/main/assets/devices/*.json` 的 `id` 自动读取，再加脚本里一个小的补充清单（`d3200`、`sonicloud`、`omi`、`adeus`、`folotoy`、`plaud`、`limitless`、`bee`）。匹配不区分大小写、按单词边界。**现有代码里已有的违规（如 `AndroidDevices.kt` 里的声云注册）先列入脚本内的“已知待清理”白名单并注明卡号 140**，新违规才报错。
   3. `plugins/A/` 下的文件引用 `plugins.B` 包 → 报错。
   4. `devices/build.gradle.kts` 的 commonMain 依赖超出上面允许的三项 → 报错。
   脚本附一个最小自测（`tools/test_check_devices.py`，3～5 个用例：干净通过、出现 java import、出现品牌名、跨插件引用）。
5. **不改**：`ui/`、`MainActivity.kt`、数据库、录音服务、任何 App 行为。

## 验证

- `./gradlew :devices:jvmTest`（或对应任务）与搬过去的测试通过；`:app` 里与设备相关的现有测试照常通过（只跑 `app.resound.devices.*`）。
- `./gradlew :app:assembleDebug` 通过。
- `python3 tools/check_devices.py` 在本分支通过；`python3 tools/test_check_devices.py` 通过。
- 按 AGENTS.md：只跑相关测试，不跑全量。

## 交付

在本分支 `codex/139-devices-module` 提交（中文提交信息，不推送），写 `docs/任务卡/139-交付说明.md`：做了什么、选的安卓目标写法和理由、改了哪些可见性、JSON 换库后如何保证严格规则不变、白名单列表、验证结果、遗留问题。
