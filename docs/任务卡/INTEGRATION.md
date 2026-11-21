# SoundSound Admin R4.3 · 集成与回归说明

> 状态：**独立模块已实现并通过本地 SQLite 兼容测试；尚未读取 `D:\ts-link` 原始 `worker.js`，因此没有接入现有用户认证、Android、生产 D1，也没有部署上线。**
>
> 不得用该模块测试通过冒充 R4.3 或 R4 整体验收通过。

## 已定位的线上现象

- 在用户当前浏览器标签读取 `https://soundsound-auth.kan831019.workers.dev/`，响应正文为 `{ "error": "not found" }`，说明根路由未返回管理 UI。
- 原 `AUDIT-功能清单.md` 记录：现有 Worker 只有五张认证表，未实现 Web Admin、会员表、额度与兑换码。
- 无法从这里进入用户 Windows 工作目录 `D:\ts-link`，也没有部署所需的 Cloudflare 授权会话。**不可宣称已经修复线上网址。**

## 本包交付

| 文件 | 作用 |
| --- | --- |
| `web/index.html`、`web/admin.css`、`web/admin.js` | 真实 REST API 驱动的中文后台界面，无假用户数据 |
| `admin-addon.js` | D1 后台 API，管理员 HttpOnly 会话、CSRF、审计、会员、用量、兑换码 |
| `schema.sql` | 会员/兑换/用量的增量建表草案（不能未经审查直接跑生产） |
| `tests/admin.test.mjs` | 兼容 SQLite 的本地 CRUD/权限/兑换测试 |
| `scripts/live-probe.mjs` | 只读检查真实路由，不输出个人信息 |
| `scripts/embed-html.mjs` | 生成可直接从原 Worker import 的 JS 字符串资源 |
| `scripts/collect-source.ps1` | 从 Windows 原仓库安全收集必须对接的文件清单 |

## 首先核实线上地址

在 **你自己本机**（可访问网络的环境）执行：

```powershell
node scripts/live-probe.mjs https://soundsound-auth.kan831019.workers.dev
```

`/` 返回 404 且 `/auth/*` 正常，不代表 Worker 宕机；只表示无首页路由。Cloudflare 官方说明也存在路由关闭、worker 异常和 BIC 访问限制等原因，须结合 `wrangler tail` 和实际响应排查。

## 集成步骤（必须由能读取原仓库的人执行）

1. 在 Windows 的 SoundSound 原仓库 **先备份并提交当前未提交修改**；不能对正在使用的 D1 使用清空、DROP、重建。
2. 读取 `server/worker.js` 与 `server/schema.sql` 的真实认证字段与 `DB` 绑定名。确认生产 `users` 的 ID、密码格式、停用字段、刷新 Token 撤销方法。
3. 审核 `schema.sql` 新表是否与既有表冲突；必要时改写为 **带编号 D1 Migration**，先本地迁移、备份验证，再部署。
4. 运行 `node scripts/embed-html.mjs`，生成 `dist/admin-ui.js`；把 `admin-addon.js` 和 `dist/admin-ui.js` 放在服务器源码可 import 的目录。
5. **为现有认证系统实现 accountAdapter**，把 `listUsers/createUser/updateUser/userExists` 连接到原有创建账号、密码 PBKDF2、账号停用与会话撤销逻辑。当前未提供真实原代码，不允许猜测或旁路另建账号库。
6. 在原 Worker 的 `fetch(request,env,ctx)` 路由最前面挂载：

```js
import {createConsoleAddon,redeemCode,recordAsrUsage} from './admin-addon.js';
import {adminHtml,adminCss,adminJs} from './dist/admin-ui.js';
// accountAdapter MUST call your EXISTING Auth functions and share the real users table.
const consoleHandler=createConsoleAddon({
  db:env.DB, env, accountAdapter,
  pageHtml:adminHtml,assets:{css:adminCss,js:adminJs}
});
const consoleResponse=await consoleHandler(request);
if(consoleResponse)return consoleResponse;
// For GET / only, after verifying no existing root contract:
if(new URL(request.url).pathname==='/' && request.method==='GET')
  return Response.redirect(new URL('/console',request.url),302);
// Else continue EXISTING /auth and /admin route handling.
```

`env.DB` 和注入位置是示例，必须根据现有 worker 代码调整。推荐把管理 UI 部署在 `/console`，以免冲突原 `/admin/*` JSON API。

7. 在 Cloudflare Dashboard 的 Worker 设置中新增强随机密钥 `ADMIN_SESSION_SECRET`（与既有 `ADMIN_TOKEN` 分开）；用 `wrangler secret put ADMIN_SESSION_SECRET` 在本机交互录入，**不要发到聊天、提交或日志**。
8. 将 `redeemCode(db, verifiedUserId,code,requestId)` 接入已有用户授权保护的 `/auth/redeem` 路由。必须从验证后的 access token 取得 userId，不能相信请求体里传来的 user_id。
9. 将 `recordAsrUsage` 接入经过身份、设备验证的授权用量同步 API；并由 Android `asr_usage` 与服务端用量账本按 segment ID 去重。**当前包不含 Android 端计费执行与离线授权升级**。
10. 增加 Cloudflare Access 保护 `/console*` 或为管理后台绑定独立域名与访问策略（不能仅靠公开 UI 隐藏后台）。如启用 Access，必须测试 API、静态资源和移动端认证路径不冲突。
11. 本地测试 -> staging D1 -> 生产 D1 增量迁移 -> 正式部署 -> 管理后台全流程 CRUD E2E。部署前备份生产 D1。

## 后台 API 合约

- `POST /console/api/session`：输入管理员凭证，设置 HttpOnly SameSite=Strict Cookie；返回 CSRF
- `GET /console/api/session`：验证会话、返回 CSRF；`DELETE`：撤销
- `GET /console/api/overview`
- `GET/POST /console/api/users`；`PATCH /console/api/users/:id`（通过原 Auth 适配器）
- `GET/POST /console/api/memberships`；`DELETE /console/api/memberships/:id`
- `GET /console/api/usage`；`POST /console/api/usage/adjustments`
- `GET/POST /console/api/codes/batches`；`POST /console/api/codes/batches/:id/activate`；`POST /console/api/codes/batches/:id/disable`
- `GET /console/api/redemptions`；`GET /console/api/audit`；`GET /console/api/plans`；`PATCH /console/api/plans/:id`

后台默认不会存用户音频、转写正文、个人记忆和模型密钥。管理员操作追加审计日志；兑换码只保存哈希，明文生成时仅返回一次。

## Free 与首批权益

- Free：**每自然月 1800 分钟**；计费单位为成功转写的有效音频时长。同一段音频失败重试不重复扣量。
- Pro：本机离线转写不限时长，不意味着无限云端 AI 调用。
- Founder：**100 个草稿码、每码 30 天 Pro**；管理员确认启用后才可核销，核销与权益发放事务原子化。
- Pro 以能力行记录：`local_asr_minutes`、`cross_meeting_tracking`、`advanced_project_analysis`、`agent_workflow`；同时保留可识别的 Pro 组记录。

## 仍然未完成／不可宣称通过

- 无原始 `worker.js`，无法接入真实账号创建与用户密码/会话逻辑；模块返回 501，而不是建虚假用户。
- Android `MyAccountSection` 会员区、完整 quota check 和旧数据归属迁移仍未在这个独立包中实现。
- Cloudflare 生产 D1 未迁移、Worker 未部署；本地 SQLite 模拟不等于 Cloudflare D1 原生 E2E。
- 项目原有 `P0/P1` 修复工作树无法读取，故不能独立复跑 Gradle、真机断网 E2E 或 72h Soak。
- 正式上线前，还必须审计本模块的并发兑码、密码/管理员鉴权、会话失效、安全 CSP、时区/额度算法及数据迁移；目前只用于集成开发与审查。
- `web/` 前端与增量 D1 模块不能替代原有登录授权隔离与 Android 设备门禁。

## 回归验收清单

### 原系统（必须在 Windows 真正运行）

- Git HEAD/工作区完整性、已提交/未提交变更及差异备份
- `:shared:jvmTest`、`:app:testDebugUnitTest`、`check_shared.py`
- P0-1 整理失败 pending 仍在；P0-2 防重复付费；P0-3 隐私硬锁；P0-4 A/B 隔离；P0-5 老版升级不锁死；P0-6 停用测试账号与签名密钥轮换计划
- P1-11 登录后回放原动作与 Android FGS 异常路径

### 会员与后台（必须打真实 API）

- 未登录后台返回 401，已登录管理员进入 `/console`
- 用户创建 → App 能真实登录 → 禁用用户 → Refresh Token 无法再续
- Pro 授予 → 30 天 → 到期/撤销 → Free，不删记录
- 100 枚草稿码未启用前无法核销，启用后一人一枚、一枚只授一次
- 同步网络重试、重复请求、不同账号竞争同一码：不能双重核销
- 多设备同一个 segment 重传不重复扣费
- 月末/月初账期边界与本地时区一致
- 权限变更与既有离线 7 天授权的更新/撤销一致
- 管理操作全部存在审计记录；生产库可以备份、迁移和恢复

**目前原 R4_RELEASE_VERDICT 仍是 NO-GO。**
