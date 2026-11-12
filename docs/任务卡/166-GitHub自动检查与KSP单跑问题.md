# 卡 166：GitHub 自动检查 + 排查 KSP 单独运行报 MissingType

## 一、GitHub Actions 自动检查

仓库目前没有 `.github/workflows`。外部贡献者（如 PR #30）提交时没有任何自动检查。新建两个工作流（中文注释）：

1. `android.yml`（ubuntu-latest，push 到 main 与所有 PR 触发）：
   - JDK 21（temurin），Gradle 缓存（`gradle/actions/setup-gradle`，选当前稳定大版本）。
   - 依次：`python3 tools/check_shared.py`、`python3 tools/check_devices.py`、`./gradlew -PskipSigningSecrets=true :shared:check :shared:jvmTest :devices:check :app:compileDebugKotlin :app:testDebugUnitTest`。
   - 失败时上传 `app/build/reports/tests` 与 `shared/build/reports/tests` 作为产物。
   - 不需要任何密钥；`ThingsSharedLiveTest` 等需要真实服务商的测试本来就按条件跳过，确认它们在 CI 里会跳过而不是失败。
   - Android SDK：用 runner 自带或 `android-actions/setup-android`；`local.properties` 不提交，CI 里按需生成 `sdk.dir`。
2. `ios.yml`（macos-latest，只在 `shared/`、`devices/`、`tools/check_ios.sh`、Gradle 文件变化时触发）：跑 `zsh tools/check_ios.sh`（脚本会自己找 `/Applications/Xcode*.app`；若 runner 上路径不同，给脚本加一个读环境变量 `TAOSHENG_XCODE` 的覆盖入口，默认行为不变）。

动作版本一律写死到具体版本号（不写 `@main`）。在 README（中英文各一句）说明“提交前会自动检查共用边界、iOS 编译与单测”，并在 `AGENTS.md` 提一句 CI 存在。

**本卡只在本地提交工作流文件，不推送**；负责人同意推送后才会生效。无法在本地真正跑 GitHub Actions：用 `act` 不必；请至少用 `python3 -c "import yaml"`（或手写检查）确认 YAML 语法正确，并在交付说明写清“未在 GitHub 上实跑”。

## 二、KSP 单独运行报 MissingType

现象（卡 160 合并后发现）：`./gradlew :app:kspDebugKotlin`（含 `-Pksp.incremental=false`）报
`[MissingType]: Element 'app.resound.skills.SkillCardRow' references a type that is not present`、`TopicGroup` 同类错误；但 `:app:compileDebugKotlin`、`:app:assembleRelease` 完整构建正常。

要求：查清原因（例如 Room 实体字段类型来自 `:shared` 而 KSP 单跑时 `:shared` 的 jar 尚未产出/未进 KSP 的 classpath，或 KSP 与 KMP 依赖的任务依赖缺失），给出最小修复（如为 `kspDebugKotlin`/`kspReleaseKotlin` 显式依赖 `:shared` 的编译任务），使单独运行也通过。不改实体、DAO、SQL、数据库版本。交付说明写清根因证据。

## 测试与 Gradle 注意
- 本地验证：`./gradlew :app:kspDebugKotlin`（clean 后单跑）通过；`:shared:check :app:compileDebugKotlin` 通过；两个检查脚本通过。
- 另一张卡（164）同时在跑。**不要 `./gradlew --stop`**；gradlew 加 `-Dorg.gradle.daemon.registry.base=/tmp/taosheng-166-gradle -PskipSigningSecrets=true`。清理只能清本工作目录（`~/Developer/TaoSheng-166`）的 build，不清全局缓存。

## 交付
分支 `codex/166-ci-ksp`（从 main 193ccad，`~/Developer/TaoSheng-166`），中文提交，不推送，不操作手机。写 `docs/任务卡/166-交付说明.md`。编译前 `export JAVA_HOME=/opt/homebrew/opt/openjdk@21`。
