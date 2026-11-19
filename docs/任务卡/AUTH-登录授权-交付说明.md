# AUTH GATE 登录与授权系统 — 交付说明

## 做了什么

**独立分支特性，不改变 `R4_RELEASE_VERDICT=NO-GO`。**

### 服务端（`server/`）— Cloudflare Workers + D1
- `worker.js`：账号密码登录（PBKDF2-SHA256 100k 迭代，未自造算法）、登录限流（账号+IP/窗口）、ES256 access token（15min）、旋转 refresh token（30天，只存 SHA-256 哈希）、ECDSA P-256 签名离线授权凭证（默认 7 天，可配 `OFFLINE_LICENSE_DAYS`）、会话列表/撤销、账号停用（停即刻吊销全部会话）、审计日志、管理员建号/激活码邀请。
- `schema.sql`：users / sessions / licenses / audit_log / login_attempts 五表迁移。
- **不保存**音频、转写、个人记忆、用户 AI Key（只有账号/会话/凭证/审计）。
- `test/smoke.mjs`：内存 D1 shim 全路由冒烟 22 用例全过——含与安卓同口径的 ECDSA 验签（SPKI DER + SHA256withECDSA + DER 签名 base64）、refresh 旋转、撤销、停用、限流、篡改拒签。

### 客户端
- `shared/…/auth/AuthContract.kt`：平台无关门禁判定 `AuthGateRules`（游客/凭证/过期/串设备/串账号/能力缺口/绑定冲突全路径有判定），`Capability` 六项、`LicensePayload`、`AuthStatus`。
- `app/…/auth/`：`AuthGate`（统一运行时门禁 + 登录/刷新/登出 + 待恢复动作回放）、`AuthStore`（token 走 Keystore SecretStore，凭证/绑定走 prefs）、`AuthApi`（OkHttp 客户端）、`LicenseVerifier`（内置公钥验签）、`AuthGateUi`（登录面板 + 我的账号区）。
- 门禁落点：`CaptureService.onStartCommand`（磁贴/通知/Widget/场景/快捷指令统一硬闸，STOP 永远放行，录音中授权失效走正常 STOP 安全收尾）、`AndroidCaptureController.dispatch`（UI 路径弹登录+自动重发，START/RESUME 拦，STOP/PAUSE 放行）、`CaptureInbox.receive`（分享写入）、`McpServer.handle`（配对令牌之外再加 MCP_ACCESS）、`AgentBridge.send` + `AgentDispatchWork`（AGENT_ACCESS）、`AndroidBackupRepository.runBackup/exportTo`（EXPORT_BACKUP）、`FollowupWork`/`WorkflowWork`（READ_PERSONAL_DATA）、`GroundedQA.ask`（AI_ANALYSIS）。
- 登出/会话失效 → `onSessionCleared` → `controller.stop()` 立即安全停录。
- 数据隔离：`bound_account_id` 写死后门禁拒绝他账号读本机数据；换账号不复用解锁态。

### 界面（需 Claude 审核接管——AGENTS 约定 ui/MainActivity 归 Claude）
- `auth/AuthGateUi.kt`：`AuthLoginHost()`（挂 MainActivity 根布局即弹统一登录面板，取消回游客）+ `MyAccountSection()`（设置页：状态/设备管理/退出）。
- **未动 `ui/` 包与 `MainActivity.kt`**——接入是两行：根布局 `<AuthLoginHost/>`、设置页 `<MyAccountSection/>`。

## 验证

- `node server/test/smoke.mjs`：22/22 PASS。
- `:shared:jvmTest`：全过（含 `AuthGateRulesTest` 9 用例）。
- `:app:testDebugUnitTest --tests LicenseVerifierTest`：4/4 PASS。
- `:app:assembleDebug`：BUILD SUCCESSFUL。
- `python tools/check_shared.py`：**SKIPPED**（本机无 Python；shared 新代码只引 kotlinx.serialization，无平台 API）。

## 未完成 / 需要真机与 Claude 配合

- 登录 UI 未接入 MainActivity/设置页（归 Claude）；接入前门禁逻辑已在服务层全部生效。
- BiometricPrompt 隐私锁：`AuthStore` 字段已备（privacy_lock/lock_after/locked/bg_at），UI 与生命周期钩子未做。
- 旧无账号数据的一次性迁移/归属确认流程：`bindCurrent()` 已备，引导 UI 未做。
- 真机 E2E：未跑（部署服务端 + 真实账号后测）。
- 全量单测未重跑（本卡新增测试已过；全量留给 R4.1 门禁一并跑）。

## 安全边界（如实）

- 服务端撤销对**离线**设备不即时生效——凭证到期（默认 7 天）前离线设备仍可用；到期后必须联网续期。
- 授权失效发生在录音中：当前片段安全写完再停，不杀进程、不丢已存数据。
- 登出不删本地录音/记忆；换账号登录不能读前一账号绑定的本机数据。

## 状态

- `AUTH_BUILD_STATUS` = PASS（:app:assembleDebug 成功）
- `AUTH_SERVER_STATUS` = CODE_READY + SMOKE_PASS（22/22）；真实部署未做
- `GUEST_GATE_STATUS` = CODE_READY（服务层全闸；UI 登录面板待接入）
- `DATA_ISOLATION_STATUS` = PARTIAL（bound_account 判禁已生效；迁移引导 UI 未做）
- `OFFLINE_LICENSE_STATUS` = CODE_READY（验签+7天期已验；真机断网未测）
- `TEST_STATUS` = shared 9 + verifier 4 + worker 22 PASS；全量回归未跑
- `APK_PATH` = `app/build/outputs/apk/debug/app-debug.apk` SHA256 `550D8AFBD54090144299B6510037B3A81EADEB6578541BB60C4A7EDBF1AB669F`
- `KNOWN_BLOCKERS` = UI 接入（Claude）、隐私锁、迁移引导、真机 E2E、全量回归
