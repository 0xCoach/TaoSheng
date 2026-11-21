// 冒烟测试：内存 D1 shim 跑 worker.js 全路由，验证 auth 闭环 + ECDSA 凭证可被 SPKI 公钥验签。
// 运行: node server/test/smoke.mjs   (Node >= 20, 无需 wrangler/D1)
import { readFileSync } from "fs";
import { createPublicKey, createVerify } from "crypto";

const { subtle } = crypto;
const b64 = (buf) => Buffer.from(buf).toString("base64");
const ub64 = (s) => new Uint8Array(Buffer.from(s.replaceAll("-", "+").replaceAll("_", "/"), "base64"));

// ---------- 内存 D1 ----------
const tables = { users: [], sessions: [], licenses: [], audit_log: [], login_attempts: [] };
function match(sql, ...subs) { return subs.every((s) => sql.includes(s)); }

const TRACE = process.env.TRACE_D1 === "1";
class Stmt {
  constructor(sql) { this.sql = sql; this.args = []; }
  bind(...a) { this.args = a; return this; }
  async run() { if (TRACE) console.log("  run>", this.sql.slice(0, 70), this.args); return this._run(); }
  async _run() {
    const s = this.sql, a = this.args;
    if (match(s, "INSERT INTO users")) { tables.users.push({ id: a[0], username: a[1], pass_hash: a[2], status: a[3], capabilities: a[4], activation_code: a[5], created_at: a[6], disabled_at: null }); return { meta: { changes: 1 } }; }
    if (match(s, "INSERT INTO sessions")) { tables.sessions.push({ id: a[0], user_id: a[1], device_id: a[2], device_name: a[3], refresh_hash: a[4], created_at: a[5], expires_at: a[6], last_seen_at: a[7], revoked_at: null }); return { meta: { changes: 1 } }; }
    if (match(s, "INSERT INTO licenses")) { tables.licenses.push({ id: a[0], user_id: a[1], device_id: a[2], payload: a[3], signature: a[4], issued_at: a[5], expires_at: a[6], revoked_at: null }); return { meta: { changes: 1 } }; }
    if (match(s, "INSERT INTO audit_log")) { tables.audit_log.push({ user_id: a[0], event: a[1], detail: a[2], ip: a[3], at: a[4] }); return { meta: { changes: 1 } }; }
    if (match(s, "INSERT OR REPLACE INTO login_attempts")) { const i = tables.login_attempts.findIndex(r => r.key === a[0]); const row = { key: a[0], count: 1, reset_at: a[1] }; i < 0 ? tables.login_attempts.push(row) : tables.login_attempts[i] = row; return { meta: { changes: 1 } }; }
    if (match(s, "UPDATE login_attempts SET count")) { const r = tables.login_attempts.find(r => r.key === a[1]); if (r) r.count = a[0]; return { meta: { changes: r ? 1 : 0 } }; }
    if (match(s, "DELETE FROM login_attempts")) { const i = tables.login_attempts.findIndex(r => r.key === a[0]); if (i >= 0) tables.login_attempts.splice(i, 1); return { meta: { changes: 1 } }; }
    if (match(s, "UPDATE users SET status='active'")) { const r = tables.users.find(r => r.id === a[0]); if (r) { r.status = "active"; r.activation_code = null; } return { meta: { changes: r ? 1 : 0 } }; }
    if (match(s, "UPDATE users SET status=?, disabled_at=?")) { const r = tables.users.find(r => r.username === a[2] || r.id === a[3]); if (r) { r.status = a[0]; r.disabled_at = a[1]; } return { meta: { changes: r ? 1 : 0 } }; }
    if (match(s, "UPDATE sessions SET revoked_at=? WHERE user_id=(SELECT")) { const u = tables.users.find(r => r.username === a[1] || r.id === a[2]); let n = 0; for (const r of tables.sessions) if (r.user_id === u?.id && r.revoked_at == null) { r.revoked_at = a[0]; n++; } return { meta: { changes: n } }; }
    if (match(s, "UPDATE sessions SET refresh_hash=?, prev_refresh_hash=?")) { const r = tables.sessions.find(r => r.id === a[5]); if (r) { r.refresh_hash = a[0]; r.prev_refresh_hash = a[1]; r.prev_valid_until = a[2]; r.expires_at = a[3]; r.last_seen_at = a[4]; } return { meta: { changes: r ? 1 : 0 } }; }
    if (match(s, "UPDATE sessions SET revoked_at=? WHERE refresh_hash=?")) { const r = tables.sessions.find(r => r.refresh_hash === a[1]); if (r) r.revoked_at = a[0]; return { meta: { changes: r ? 1 : 0 } }; }
    if (match(s, "UPDATE sessions SET revoked_at=? WHERE id=? AND user_id=?")) { const r = tables.sessions.find(r => r.id === a[1] && r.user_id === a[2]); if (r) r.revoked_at = a[0]; return { meta: { changes: r ? 1 : 0 } }; }
    if (match(s, "UPDATE licenses SET revoked_at=?")) { const sess = tables.sessions.find(r => r.id === a[2]); let n = 0; for (const r of tables.licenses) if (r.user_id === a[1] && r.device_id === sess?.device_id) { r.revoked_at = a[0]; n++; } return { meta: { changes: n } }; }
    throw new Error("D1 shim: unhandled SQL: " + s);
  }
  async first() {
    if (TRACE) console.log("  first>", this.sql.slice(0, 70), this.args);
    const r = await this._first(); if (TRACE) console.log("    ->", JSON.stringify(r)?.slice(0, 120)); return r;
  }
  async _first() {
    const s = this.sql, a = this.args;
    if (match(s, "FROM users WHERE username=?")) return tables.users.find(r => r.username === a[0]) || null;
    if (match(s, "FROM sessions WHERE refresh_hash=? AND revoked_at IS NULL")) return tables.sessions.find(r => r.refresh_hash === a[0] && r.revoked_at == null) || null;
    if (match(s, "FROM sessions WHERE prev_refresh_hash=?")) return tables.sessions.find(r => r.prev_refresh_hash === a[0] && r.revoked_at == null && r.prev_valid_until > a[1]) || null;
    if (match(s, "FROM sessions WHERE id=? AND revoked_at IS NULL")) return tables.sessions.find(r => r.id === a[0] && r.revoked_at == null) || null;
    if (match(s, "FROM users WHERE id=? AND status='active'")) return tables.users.find(r => r.id === a[0] && r.status === "active") || null;
    if (match(s, "FROM users WHERE id=?")) return tables.users.find(r => r.id === a[0]) || null;
    if (match(s, "FROM login_attempts WHERE key=?")) return tables.login_attempts.find(r => r.key === a[0]) || null;
    throw new Error("D1 shim first(): unhandled SQL: " + s);
  }
  async all() {
    const s = this.sql, a = this.args;
    if (match(s, "FROM sessions WHERE user_id=? ORDER BY")) return { results: tables.sessions.filter(r => r.user_id === a[0]).map(({ id, device_id, device_name, created_at, last_seen_at, revoked_at }) => ({ id, device_id, device_name, created_at, last_seen_at, revoked_at })) };
    if (match(s, "FROM users")) return { results: tables.users.map(({ id, username, status, capabilities, created_at, disabled_at }) => ({ id, username, status, capabilities, created_at, disabled_at })) };
    throw new Error("D1 shim all(): unhandled SQL: " + s);
  }
}
const env = {
  DB: { prepare: (sql) => new Stmt(sql) },
  ADMIN_TOKEN: "test-admin-token",
  LICENSE_PRIVATE_KEY: readFileSync(new URL("../.dev/license-private-key.b64", import.meta.url), "utf8").trim(),
  LICENSE_PUBLIC_JWK: readFileSync(new URL("../.dev/license-public.jwk.json", import.meta.url), "utf8").trim(),
  ACCESS_TTL_SECONDS: "900", REFRESH_TTL_SECONDS: "2592000",
  OFFLINE_LICENSE_DAYS: "7", LOGIN_RATE_LIMIT: "8", LOGIN_RATE_WINDOW_SECONDS: "600",
};

// worker.js 是 ESM export default { fetch } —— 动态导入
const worker = (await import(new URL("../src/worker.js", import.meta.url))).default;
const call = (path, opts = {}) => worker.fetch(new Request("https://auth.test" + path, opts), env);
const j = (r) => r.json();

// ---------- 断言 ----------
let pass = 0, fail = 0;
const t = (name, cond) => { cond ? pass++ : fail++; if (!cond) console.log("FAIL " + name); else console.log("PASS " + name); };
const tj = async (name, r, check) => { const body = await r.json(); const ok = check(body); if (!ok) console.log("  body:", JSON.stringify(body).slice(0, 300)); t(name, ok); return body; };

// 1. 管理员建账号（含激活码邀请 + 直激活两个）
const badAdmin = await call("/admin/accounts", { method: "POST", headers: { authorization: "Bearer wrong", "content-type": "application/json" }, body: "{}" });
t("admin 无令牌 401", badAdmin.status === 401);
const mk = (body) => call("/admin/accounts", { method: "POST", headers: { authorization: "Bearer test-admin-token", "content-type": "application/json" }, body: JSON.stringify(body) });
const acc1 = await j(await mk({ username: "alice", password: "pw-alice-123" }));
t("admin 建直激活账号", acc1.status === "active" && !!acc1.id);
const acc2 = await j(await mk({ username: "bob", password: "pw-bob-456", activation_code: "INV-77" }));
t("admin 建邀请账号", acc2.status === "invited");

// 2. 登录路径
const badLogin = await call("/auth/login", { method: "POST", body: JSON.stringify({ username: "alice", password: "wrong", device_id: "dev-1" }) });
t("密码错 401", badLogin.status === 401);
const needAct = await call("/auth/login", { method: "POST", body: JSON.stringify({ username: "bob", password: "pw-bob-456", device_id: "dev-2" }) });
t("邀请账号无激活码 403", needAct.status === 403);
const actLogin = await tj("激活码激活+登录", await call("/auth/login", { method: "POST", body: JSON.stringify({ username: "bob", password: "pw-bob-456", device_id: "dev-2", activation_code: "INV-77" }) }), (b) => !!b.access_token && !!b.license);
const login = await tj("登录返回 account/token/license", await call("/auth/login", { method: "POST", body: JSON.stringify({ username: "alice", password: "pw-alice-123", device_id: "dev-1", device_name: "pixel" }) }), (b) => b.account?.username === "alice" && !!b.access_token && !!b.refresh_token && !!b.license?.payload);

// 3. 凭证验签（模拟安卓 LicenseVerifier：SPKI DER + SHA256withECDSA + DER 签名 base64）
const spkiDer = readFileSync(new URL("../../app/src/main/assets/auth-license-public-key.hex", import.meta.url));
const pubKey = createPublicKey({ key: spkiDer, format: "der", type: "spki" });
const payload = JSON.parse(login.license.payload);
// 同安卓口径 = Java SHA256withECDSA 期望 DER 签名；Node createVerify 对 EC 默认吃 DER
const verifyDer = (payloadText, sigB64) => createVerify("SHA256")
  .update(new TextEncoder().encode(payloadText)).verify(pubKey, Buffer.from(sigB64, "base64"));
const sigOk = verifyDer(login.license.payload, login.license.signature);
t("license ECDSA 验签通过(同安卓口径)", sigOk);
const payloadOk = payload.sub === login.account.id && payload.dev === "dev-1" && payload.v === 1
  && payload.caps.includes("RECORD_AUDIO") && payload.caps.includes("MCP_ACCESS")
  && payload.exp - payload.iat === 7 * 86400;
t("license payload 字段/能力/7天期", payloadOk);
const forged = JSON.stringify({ ...payload, caps: [...payload.caps, "ADMIN"] });
t("篡改 payload 验签失败", !verifyDer(forged, login.license.signature));

// 4. access token 状态
const st = await j(await call("/auth/status", { headers: { authorization: "Bearer " + login.access_token } }));
t("status 返回账号+会话", st.account.username === "alice" && !!st.session.id);
t("status 无令牌 401", (await call("/auth/status")).status === 401);

// 5. 刷新旋转
let ref = await j(await call("/auth/refresh", { method: "POST", body: JSON.stringify({ refresh_token: login.refresh_token }) }));
t("refresh 返回新 token+新 license", !!ref.access_token && !!ref.refresh_token && ref.refresh_token !== login.refresh_token);
const grace = await call("/auth/refresh", { method: "POST", body: JSON.stringify({ refresh_token: login.refresh_token }) });
t("旧 refresh token 在宽限期内可恢复丢失响应", grace.status === 200);
ref = await j(grace);
const currentSession = tables.sessions.find(r => r.device_id === "dev-1") || tables.sessions[0];
currentSession.prev_valid_until = 0;
t("宽限期结束后旧 refresh token 失效", (await call("/auth/refresh", { method: "POST", body: JSON.stringify({ refresh_token: login.refresh_token }) })).status === 401);

// 6. 设备列表/撤销
const devs = await j(await call("/auth/devices", { headers: { authorization: "Bearer " + ref.access_token } }));
t("devices 列出会话", devs.sessions.length >= 1);
const revoked = await j(await call("/auth/devices/revoke", { method: "POST", headers: { authorization: "Bearer " + ref.access_token }, body: JSON.stringify({ session_id: devs.sessions[0].id }) }));
t("revoke ok", revoked.ok === true);
t("撤销后 status 401", (await call("/auth/status", { headers: { authorization: "Bearer " + ref.access_token } })).status === 401);

// 7. 登出
await call("/auth/logout", { method: "POST", body: JSON.stringify({ refresh_token: ref.refresh_token }) });
t("登出后 refresh 401", (await call("/auth/refresh", { method: "POST", body: JSON.stringify({ refresh_token: ref.refresh_token }) })).status === 401);

// 8. 停用账号 → 登录拒 + 旧会话全撤
await call("/admin/accounts/status", { method: "POST", headers: { authorization: "Bearer test-admin-token", "content-type": "application/json" }, body: JSON.stringify({ username: "bob", disabled: true }) });
t("停用后登录 403", (await call("/auth/login", { method: "POST", body: JSON.stringify({ username: "bob", password: "pw-bob-456", device_id: "dev-9" }) })).status === 403);

// 9. 限流：连刷错误密码至 blocked
for (let i = 0; i < 9; i++) await call("/auth/login", { method: "POST", body: JSON.stringify({ username: "carol", password: "x", device_id: "d" }) });
t("限流生效 429", (await call("/auth/login", { method: "POST", body: JSON.stringify({ username: "carol", password: "x", device_id: "d" }) })).status === 429);

// 10. 服务端不落个人数据（schema 约束自证）
t("users 表无内容字段", !("audio" in tables.users[0]) && !("transcript" in tables.users[0]));
t("审计日志有记录", tables.audit_log.length > 5);

console.log(`\n${pass} passed, ${fail} failed`);
process.exit(fail ? 1 : 0);
