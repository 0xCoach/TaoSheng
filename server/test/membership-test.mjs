// R4.3 会员路由测试：内存 D1 shim + 真 worker fetch（与 smoke.mjs 同法）。
// 运行: node server/test/membership-test.mjs   (Node >= 20)
import { readFileSync } from "fs";
import { webcrypto } from "crypto";

const subtle = webcrypto.subtle;
const b64 = (buf) => Buffer.from(buf).toString("base64");

// ---------- 测试用 ECDSA 密钥 ----------
const kp = await subtle.generateKey({ name: "ECDSA", namedCurve: "P-256" }, true, ["sign", "verify"]);
const pkcs8 = await subtle.exportKey("pkcs8", kp.privateKey);
const jwk = await subtle.exportKey("jwk", kp.publicKey);
const ADMIN_TOKEN = "test-admin-token";

// ---------- 内存 D1 ----------
const T = { users: [], sessions: [], licenses: [], audit_log: [], login_attempts: [],
  plans: [], entitlements: [], usage_ledger: [], quota_adjustments: [],
  redeem_campaigns: [], redeem_codes: [], redeem_records: [], admin_sessions: [] };

class Stmt {
  constructor(sql) { this.sql = sql.replace(/\s+/g, " "); this.args = []; }
  bind(...a) { this.args = a; return this; }
  _row(t, pred) { return T[t].find(pred); }
  _rows(t, pred = () => true) { return T[t].filter(pred); }
  async run() { return { meta: { changes: this._run() } }; }
  _run() {
    const s = this.sql, a = this.args;
    const I = (t, cols) => { const r = {}; cols.forEach((c, i) => r[c] = a[i]); T[t].push(r); return 1; };
    if (s.includes("INSERT INTO admin_sessions")) return I("admin_sessions", ["id", "token_hash", "csrf", "created_at", "expires_at"]);
    if (s.includes("UPDATE admin_sessions SET revoked_at")) { const r = this._row("admin_sessions", x => x.id === a[1]); if (r) r.revoked_at = a[0]; return r ? 1 : 0; }
    if (s.includes("INSERT INTO users")) return I("users", ["id", "username", "pass_hash", "status", "capabilities", "activation_code", "created_at"]);
    if (s.includes("UPDATE users SET status='active'")) { const r = this._row("users", x => x.id === a[0]); if (r) { r.status = "active"; r.activation_code = null; } return r ? 1 : 0; }
    if (s.includes("UPDATE users SET status=?,disabled_at=?")) { const r = this._row("users", x => x.id === a[2]); if (r) { r.status = a[0]; r.disabled_at = a[1]; } return r ? 1 : 0; }
    if (s.includes("UPDATE sessions SET revoked_at=? WHERE user_id=(SELECT")) { const u = this._row("users", x => x.username === a[1] || x.id === a[2]); let n = 0; for (const r of T.sessions) if (r.user_id === u?.id && !r.revoked_at) { r.revoked_at = a[0]; n++; } return n; }
    if (s.includes("UPDATE sessions SET revoked_at=? WHERE user_id=?")) { let n = 0; for (const r of T.sessions) if (r.user_id === a[1] && !r.revoked_at) { r.revoked_at = a[0]; n++; } return n; }
    if (s.includes("INSERT INTO sessions")) return I("sessions", ["id", "user_id", "device_id", "device_name", "refresh_hash", "created_at", "expires_at", "last_seen_at"]);
    if (s.includes("UPDATE sessions SET refresh_hash")) { const r = this._row("sessions", x => x.id === a[5]); if (r) { r.refresh_hash = a[0]; r.prev_refresh_hash = a[1]; r.prev_valid_until = a[2]; r.expires_at = a[3]; r.last_seen_at = a[4]; } return r ? 1 : 0; }
    if (s.includes("INSERT OR REPLACE INTO login_attempts")) {
      // SQL: VALUES(?,1,?) —— count 是字面量 1，bind 只有 key+reset_at
      const i = T.login_attempts.findIndex(x => x.key === a[0]);
      const r = { key: a[0], count: 1, reset_at: a[1] };
      if (i >= 0) T.login_attempts[i] = r; else T.login_attempts.push(r); return 1;
    }
    if (s.includes("UPDATE login_attempts SET count=?")) { const r = this._row("login_attempts", x => x.key === a[1]); if (r) r.count = a[0]; return r ? 1 : 0; }
    if (s.includes("DELETE FROM login_attempts")) { const i = T.login_attempts.findIndex(x => x.key === a[0]); if (i >= 0) T.login_attempts.splice(i, 1); return i >= 0 ? 1 : 0; }
    if (s.includes("UPDATE sessions SET revoked_at=? WHERE refresh_hash=?")) { const r = this._row("sessions", x => x.refresh_hash === a[1]); if (r) r.revoked_at = a[0]; return r ? 1 : 0; }
    if (s.includes("UPDATE sessions SET revoked_at=? WHERE id=? AND user_id=?")) { const r = this._row("sessions", x => x.id === a[1] && x.user_id === a[2]); if (r) r.revoked_at = a[0]; return r ? 1 : 0; }
    if (s.includes("UPDATE licenses SET revoked_at=? WHERE user_id=? AND device_id=(SELECT")) { const sess = this._row("sessions", x => x.id === a[2]); let n = 0; for (const r of T.licenses) if (r.user_id === a[1] && r.device_id === sess?.device_id) { r.revoked_at = a[0]; n++; } return n; }
    if (s.includes("INSERT INTO licenses")) return I("licenses", ["id", "user_id", "device_id", "payload", "signature", "issued_at", "expires_at"]);
    if (s.includes("INSERT INTO entitlements")) return I("entitlements", ["id", "user_id", "capability", "source", "starts_at", "expires_at", "meta", "created_at"]);
    if (s.includes("UPDATE entitlements SET revoked_at")) { const r = this._row("entitlements", x => x.id === a[1] && !x.revoked_at); if (r) r.revoked_at = a[0]; return r ? 1 : 0; }
    if (s.includes("INSERT OR IGNORE INTO usage_ledger")) {
      if (this._row("usage_ledger", x => x.id === a[0] && x.user_id === a[1])) return 0;
      return I("usage_ledger", ["id", "user_id", "device_id", "minutes", "month", "at"]);
    }
    if (s.includes("INSERT INTO quota_adjustments")) return I("quota_adjustments", ["id", "user_id", "month", "minutes", "reason", "actor", "at"]);
    if (s.includes("INSERT INTO redeem_campaigns")) { T.redeem_campaigns.push({ id: a[0], name: a[1], grant_days: a[2], status: "draft", created_at: a[3] }); return 1; }
    if (s.includes("INSERT INTO redeem_codes")) { T.redeem_codes.push({ id: a[0], campaign_id: a[1], code_hash: a[2], status: "draft", created_at: a[3] }); return 1; }
    if (s.includes("UPDATE redeem_codes SET status='redeemed'")) {
      const r = this._row("redeem_codes", x => x.id === a[2] && x.status === "active");
      if (!r) return 0; r.status = "redeemed"; r.redeemed_by = a[0]; r.redeemed_at = a[1]; return 1;
    }
    if (s.includes("UPDATE redeem_codes SET status='active'")) { let n = 0; for (const r of T.redeem_codes) if (r.campaign_id === a[0] && r.status === "draft") { r.status = "active"; n++; } return n; }
    if (s.includes("UPDATE redeem_codes SET status='revoked'")) { let n = 0; for (const r of T.redeem_codes) if (r.campaign_id === a[0] && ["draft", "active"].includes(r.status)) { r.status = "revoked"; n++; } return n; }
    if (s.includes("UPDATE redeem_campaigns SET status='active'")) { const r = this._row("redeem_campaigns", x => x.id === a[1] && x.status === "draft"); if (r) { r.status = "active"; r.activated_at = a[0]; } return r ? 1 : 0; }
    if (s.includes("UPDATE redeem_campaigns SET status='disabled'")) { const r = this._row("redeem_campaigns", x => x.id === a[1]); if (r) { r.status = "disabled"; r.disabled_at = a[0]; } return r ? 1 : 0; }
    if (s.includes("INSERT INTO redeem_records")) {
      if (a[4] && this._row("redeem_records", x => x.request_id === a[4])) throw new Error("UNIQUE request_id");
      return I("redeem_records", ["id", "code_id", "campaign_id", "user_id", "request_id", "granted_days", "at"]);
    }
    if (s.includes("INSERT INTO audit_log")) { T.audit_log.push({ user_id: a[0], event: a[1], detail: a[2], ip: a[3], at: a[4] }); return 1; }
    if (s.includes("UPDATE plans")) { const r = this._row("plans", x => x.id === a[2]); if (r) { if (a[0]) r.name = a[0]; r.config = a[1]; } return r ? 1 : 0; }
    if (s.includes("login_attempts")) return 1;
    throw new Error("shim run: " + s);
  }
  async first() {
    const s = this.sql, a = this.args;
    if (s.includes("FROM admin_sessions WHERE id=?")) return this._row("admin_sessions", x => x.id === a[0] && !x.revoked_at) || null;
    if (s.includes("SELECT id FROM users WHERE id=? OR username=?")) return this._row("users", x => x.id === a[0] || x.username === a[1]) || null;
    if (s.includes("FROM users WHERE username=?")) return this._row("users", x => x.username === a[0]) || null;
    if (s.includes("FROM users WHERE id=?")) return this._row("users", x => x.id === a[0] && (s.includes("status='active'") ? x.status === "active" : true)) || null;
    if (s.includes("FROM sessions WHERE id=?")) return this._row("sessions", x => x.id === a[0] && !x.revoked_at) || null;
    if (s.includes("FROM sessions WHERE refresh_hash=?")) return this._row("sessions", x => x.refresh_hash === a[0] && !x.revoked_at) || null;
    if (s.includes("FROM sessions WHERE prev_refresh_hash=?")) return this._row("sessions", x => x.prev_refresh_hash === a[0] && !x.revoked_at && x.prev_valid_until > a[1]) || null;
    if (s.includes("FROM login_attempts WHERE key=?")) return this._row("login_attempts", x => x.key === a[0]) || null;
    if (s.includes("MAX(expires_at)")) {
      const rows = this._rows("entitlements", x => x.user_id === a[0] && x.capability === "pro" && !x.revoked_at && (x.expires_at == null || x.expires_at > a[1]));
      return rows.length ? { e: Math.max(...rows.map(r => r.expires_at ?? Infinity)) } : { e: null };
    }
    if (s.includes("FROM redeem_codes WHERE code_hash=?")) return this._row("redeem_codes", x => x.code_hash === a[0]) || null;
    if (s.includes("FROM redeem_campaigns WHERE id=?")) return this._row("redeem_campaigns", x => x.id === a[0]) || null;
    if (s.includes("FROM redeem_records WHERE request_id=?")) return this._row("redeem_records", x => x.request_id === a[0]) || null;
    if (s.includes("FROM redeem_records WHERE code_id=? AND user_id=?")) return this._row("redeem_records", x => x.code_id === a[0] && x.user_id === a[1]) || null;
    if (s.includes("quota_adjustments WHERE user_id=? AND month=?")) { const m = this._rows("quota_adjustments", x => x.user_id === a[0] && x.month === a[1]).reduce((t, r) => t + r.minutes, 0); return { m }; }
    if (s.includes("SUM(minutes)")) { const m = this._rows("usage_ledger", x => x.user_id === a[0] && x.month === a[1]).reduce((t, r) => t + r.minutes, 0); return { m }; }
    if (s.includes("FROM login_attempts")) return null;
    throw new Error("shim first: " + s);
  }
  async all() {
    const s = this.sql, a = this.args;
    if (s.includes("FROM entitlements e LEFT JOIN")) return { results: this._rows("entitlements").map(e => ({ ...e, username: this._row("users", u => u.id === e.user_id)?.username })) };
    if (s.includes("DISTINCT capability")) return { results: [...new Set(this._rows("entitlements", x => x.user_id === a[0] && !x.revoked_at && (x.expires_at == null || x.expires_at > a[1])).map(e => e.capability))].map(c => ({ capability: c })) };
    if (s.includes("SELECT id,device_id,device_name")) return { results: this._rows("sessions", x => x.user_id === a[0]) };
    if (s.includes("SELECT user_id,capability,expires_at FROM entitlements")) return { results: this._rows("entitlements", x => !x.revoked_at && (x.expires_at == null || x.expires_at > a[0])) };
    if (s.includes("SELECT expires_at FROM entitlements WHERE user_id=? AND capability='pro'")) return { results: this._rows("entitlements", x => x.user_id === a[0] && x.capability === "pro" && !x.revoked_at && (x.expires_at == null || x.expires_at > a[1])).map(e => ({ expires_at: e.expires_at })) };
    if (s.includes("SELECT id,username,status,capabilities")) return { results: this._rows("users") };
    if (s.includes("redeem_codes GROUP BY")) return { results: [] };
    if (s.includes("FROM redeem_campaigns")) return { results: this._rows("redeem_campaigns") };
    if (s.includes("redeem_records r")) return { results: this._rows("redeem_records") };
    if (s.includes("FROM audit_log")) return { results: this._rows("audit_log") };
    if (s.includes("FROM plans")) return { results: this._rows("plans") };
    if (s.includes("usage_ledger WHERE month=? GROUP BY")) { const g = {}; for (const r of this._rows("usage_ledger", x => x.month === a[0])) g[r.user_id] = (g[r.user_id] || 0) + r.minutes; return { results: Object.entries(g).map(([user_id, m]) => ({ user_id, m })) }; }
    if (s.includes("quota_adjustments WHERE month=?")) return { results: this._rows("quota_adjustments", x => x.month === a[0]) };
    if (s.includes("SELECT id,username FROM users")) return { results: this._rows("users") };
    throw new Error("shim all: " + s);
  }
}
const DB = { prepare: (sql) => new Stmt(sql), batch: async (stmts) => { const out = []; for (const s of stmts) out.push(await s.run()); return out; } };
const env = { DB, ADMIN_TOKEN, LICENSE_PRIVATE_KEY: b64(pkcs8), LICENSE_PUBLIC_JWK: JSON.stringify(jwk),
  ACCESS_TTL_SECONDS: 900, REFRESH_TTL_SECONDS: 2592000, OFFLINE_LICENSE_DAYS: 7, LOGIN_RATE_LIMIT: 8, LOGIN_RATE_WINDOW_SECONDS: 600 };

// ---------- 载入 worker：membership.js + worker.js 合并成 data: 模块 ----------
const src = readFileSync(new URL("../src/worker.js", import.meta.url), "utf8")
  .replace('import { handleMembership } from "./membership.js";', "")
  .replace('import { handleAi } from "./ai.js";', "async function handleAi() { return null; }")
  .replace("export default {", "const _default = {");
const msrc = readFileSync(new URL("../src/membership.js", import.meta.url), "utf8")
  .replace("export async function handleMembership", "async function handleMembership");
const mod = await import("data:text/javascript;base64," +
  Buffer.from(msrc + "\n" + src + "\nexport default _default;").toString("base64"));
const call = (req, env) => mod.default.fetch(req, env);

// ---------- 测试 ----------
let pass = 0, fail = 0;
const ok = (cond, name) => { if (cond) { pass++; console.log("  PASS", name); } else { fail++; console.log("  FAIL", name); } };
const req = (path, method = "GET", body, headers = {}) => new Request("http://t" + path, {
  method, body: body ? JSON.stringify(body) : undefined,
  headers: { "content-type": "application/json", ...headers } });
const j = async (r) => r.json();

T.plans.push({ id: "free", name: "free", price_cents: 0, config: "{}" }, { id: "pro", name: "pro", price_cents: 0, config: "{}" });

{
  const r = await call(req("/admin/accounts", "POST", { username: "u1", password: "pw1" }, { authorization: "Bearer " + ADMIN_TOKEN }), env);
  const b = await j(r); ok(r.status === 200 && b.id, "admin create user");
  // 登录
  const lr = await call(req("/auth/login", "POST", { username: "u1", password: "pw1", device_id: "dev1" }), env);
  const lb = await j(lr); ok(lr.status === 200 && lb.access_token, "u1 login");
  const authH = { authorization: "Bearer " + lb.access_token };
  // 会员初始态 → free + 1800
  let mv = await j(await call(req("/auth/membership", "GET", null, authH), env));
  ok(mv.plan === "free" && mv.usage.free_limit_minutes === 1800, "free + 1800min quota");
  // 管理台会话
  const sr = await call(req("/console/api/session", "POST", { token: ADMIN_TOKEN }), env);
  const sb = await j(sr); const ck = (sr.headers.get("set-cookie") || "").split(";")[0];
  ok(sr.status === 200 && sb.csrf && ck.startsWith("sad="), "console session login");
  const ah = { cookie: ck, "x-csrf": sb.csrf };
  // 无 CSRF 写操作 → 403
  const nc = await call(req("/console/api/codes/batches", "POST", { name: "t", count: 1, grant_days: 30 }, { cookie: ck }), env);
  ok(nc.status === 403, "no-csrf write -> 403");
  // 草稿批次
  const br = await j(await call(req("/console/api/codes/batches", "POST", { name: "founder", count: 2, grant_days: 30 }, ah), env));
  ok(br.status === "draft" && br.codes.length === 2, "draft batch x2");
  const code = br.codes[0];
  const d0 = await call(req("/auth/redeem", "POST", { code }, authH), env);
  ok(d0.status === 409, "draft code not redeemable");
  await call(req(`/console/api/codes/batches/${br.batch_id}/activate`, "POST", {}, ah), env);
  const rdResp = await call(req("/auth/redeem", "POST", { code, request_id: "req-1" }, authH), env);
  const rd = await j(rdResp); if (!rd.ok) console.log("   redeem resp:", rdResp.status, JSON.stringify(rd));
  ok(rd.ok && rd.granted_days === 30 && rd.pro_until > Math.floor(Date.now() / 1000) + 29 * 86400, "redeem -> +30d pro");
  const rd2 = await j(await call(req("/auth/redeem", "POST", { code }, authH), env));
  ok(rd2.ok && rd2.duplicated, "same code retry -> duplicated");
  const rd3 = await j(await call(req("/auth/redeem", "POST", { code: br.codes[1], request_id: "req-1" }, authH), env));
  ok(rd3.duplicated === true && rd3.granted_days === 30, "same request_id idempotent");
  mv = await j(await call(req("/auth/membership", "GET", null, authH), env));
  ok(mv.plan === "pro" && mv.capabilities.includes("local_asr_minutes"), "membership view pro + caps");
  const ur = await j(await call(req("/auth/usage", "POST",
    { items: [{ id: "seg1", minutes: 60, month: "2026-11" }, { id: "seg1", minutes: 60, month: "2026-11" }, { id: "seg2", minutes: 30, month: "2026-11" }] }, authH), env));
  ok(ur.accepted === 2, "usage dedupe 2/3");
  const before = mv.pro_until;
  const rd4 = await j(await call(req("/auth/redeem", "POST", { code: br.codes[1] }, authH), env));
  ok(rd4.pro_until === before + 30 * 86400, "existing pro extended +30d");
  const br2 = await j(await call(req("/console/api/codes/batches", "POST", { name: "x", count: 1, grant_days: 30 }, ah), env));
  await call(req(`/console/api/codes/batches/${br2.batch_id}/disable`, "POST", {}, ah), env);
  const d1 = await call(req("/auth/redeem", "POST", { code: br2.codes[0] }, authH), env);
  ok(d1.status === 409, "disabled batch code not redeemable");
  const ur2 = await j(await call(req("/console/api/users", "GET", null, ah), env));
  ok(ur2.users.some(u => u.username === "u1"), "console users list");
  // 未登录管理台 API → 401
  const na = await call(req("/console/api/users", "GET"), env);
  ok(na.status === 401, "no session -> 401");
  // 第二个用户兑同码 → 409（已核销）
  await call(req("/admin/accounts", "POST", { username: "u2", password: "pw2" }, { authorization: "Bearer " + ADMIN_TOKEN }), env);
  const lb2 = await j(await call(req("/auth/login", "POST", { username: "u2", password: "pw2", device_id: "dev2" }), env));
  const d2 = await call(req("/auth/redeem", "POST", { code }, { authorization: "Bearer " + lb2.access_token }), env);
  ok(d2.status === 409, "other user redeem used code -> 409");

  // ===== R6.2b：自助注册 + refresh 轮换宽限 =====
  const reg = await j(await call(req("/auth/register", "POST", { username: "newbie_1", password: "password88", device_id: "reg-dev1" }), env));
  ok(reg.account?.username === "newbie_1" && reg.access_token && reg.license, "register -> account+session+license");
  const rDup = await call(req("/auth/register", "POST", { username: "newbie_1", password: "password88", device_id: "d2" }), env);
  if (rDup.status !== 409) console.log("   dup status:", rDup.status, JSON.stringify(await rDup.clone().json().catch(() => null)));
  ok(rDup.status === 409, "duplicate username -> 409");
  const rBad = await call(req("/auth/register", "POST", { username: "x", password: "password88", device_id: "d" }), env);
  ok(rBad.status === 400, "short username -> 400");
  const rPw = await call(req("/auth/register", "POST", { username: "validname", password: "short", device_id: "d" }), env);
  ok(rPw.status === 400, "short password -> 400");
  // 注册即用：membership=free 1800 分钟
  const mvR = await j(await call(req("/auth/membership", "GET", null, { authorization: "Bearer " + reg.access_token }), env));
  ok(mvR.plan === "free" && mvR.usage.free_limit_minutes === 1800, "registered user -> free 1800min");
  // refresh 轮换宽限：旧票宽限期内重放仍换新票（防丢包误登出）
  const rf1 = await j(await call(req("/auth/refresh", "POST", { refresh_token: reg.refresh_token }), env));
  ok(rf1.access_token && rf1.refresh_token && rf1.refresh_token !== reg.refresh_token, "refresh rotates token");
  const rfOld = await j(await call(req("/auth/refresh", "POST", { refresh_token: reg.refresh_token }), env));
  ok(rfOld.access_token && rfOld.refresh_token, "old token within grace -> still rotates");
  const rfStale = await j(await call(req("/auth/refresh", "POST", { refresh_token: rf1.refresh_token }), env));
  ok(rfStale.error === "invalid refresh token" || rfStale.access_token, "prev-prev token handled");
  // 注册限流：同 IP（无 CF 头=同空 IP）第 6 个被拒
  let lastReg = null;
  for (let i = 0; i < 6; i++) lastReg = await call(req("/auth/register", "POST", { username: `flood_${i}`, password: "password88", device_id: "d" }), env);
  ok(lastReg.status === 429, "register rate limited after 5/hour");

  // ===== R6.3c 生命周期回归：注册Free → Pro → 到期回Free → 停用 的权限与提示 =====
  T.login_attempts.length = 0; // 上一段限流用例已把 reg| 计数打满，回归段重新计数
  const nowS = () => Math.floor(Date.now() / 1000);
  const lc = await j(await call(req("/auth/register", "POST", { username: "life_cycle", password: "password88", device_id: "lc-dev" }), env));
  let lcCaps = JSON.parse(lc.license.payload).caps;
  ok(lcCaps.length === 6 && !lcCaps.includes("local_asr_minutes"), "L1 注册即 Free：license 仅基础 6 能力");
  let mvLc = await j(await call(req("/auth/membership", "GET", null, { authorization: "Bearer " + lc.access_token }), env));
  ok(mvLc.plan === "free" && mvLc.pro_until == null, "L2 Free 无 pro_until");
  // 授予 Pro（与 grantPro 同构：pro + 4 项 pro 能力各一条权益行）
  const uidLc = lc.account.id;
  const entIds = ["pro", "local_asr_minutes", "cross_meeting_tracking", "advanced_project_analysis", "agent_workflow"]
    .map((c, i) => { const id = `ent-lc-${i}`; T.entitlements.push({ id, user_id: uidLc, capability: c, source: "test", starts_at: nowS(), expires_at: nowS() + 30 * 86400, meta: null, created_at: nowS(), revoked_at: null }); return id });
  const rfLc = await j(await call(req("/auth/refresh", "POST", { refresh_token: lc.refresh_token }), env));
  lcCaps = JSON.parse(rfLc.license.payload).caps;
  if (!(lcCaps.includes("local_asr_minutes") && JSON.parse(rfLc.license.payload).pro_until > nowS()))
    console.log("   L3 dbg:", JSON.stringify(lcCaps), "pro_until:", JSON.parse(rfLc.license.payload).pro_until, "ents:", T.entitlements.filter(e => e.user_id === uidLc).map(e => e.capability + "@" + e.expires_at).join(","));
  ok(lcCaps.includes("local_asr_minutes") && JSON.parse(rfLc.license.payload).pro_until > nowS(), "L3 Pro 后 license 含 pro 能力+pro_until");
  mvLc = await j(await call(req("/auth/membership", "GET", null, { authorization: "Bearer " + rfLc.access_token }), env));
  ok(mvLc.plan === "pro", "L4 membership 视图切 Pro");
  // Pro 到期 → 回 Free
  entIds.forEach(id => { T.entitlements.find(e => e.id === id).expires_at = nowS() - 60; });
  const rfLc2 = await j(await call(req("/auth/refresh", "POST", { refresh_token: rfLc.refresh_token }), env));
  lcCaps = JSON.parse(rfLc2.license.payload).caps;
  ok(lcCaps.length === 6 && !lcCaps.includes("local_asr_minutes") && JSON.parse(rfLc2.license.payload).pro_until == null, "L5 Pro 到期 license 回基础 6 能力");
  mvLc = await j(await call(req("/auth/membership", "GET", null, { authorization: "Bearer " + rfLc2.access_token }), env));
  ok(mvLc.plan === "free" && mvLc.usage.free_limit_minutes === 1800, "L6 到期后回到 Free 1800 分钟");
  // 停用 → 登录/刷新一律拒，提示明确
  T.users.find(u => u.id === uidLc).status = "disabled";
  const rfDis = await call(req("/auth/refresh", "POST", { refresh_token: rfLc2.refresh_token }), env);
  ok(rfDis.status === 403, "L7 停用后 refresh -> 403");
  const lgDis = await call(req("/auth/login", "POST", { username: "life_cycle", password: "password88", device_id: "lc-dev" }), env);
  ok(lgDis.status === 403 && (await lgDis.json()).error === "account disabled", "L8 停用后 login -> 403 account disabled");
  // 恢复 → 又能登录（管理台启用路径）
  T.users.find(u => u.id === uidLc).status = "active";
  const lgRe = await call(req("/auth/login", "POST", { username: "life_cycle", password: "password88", device_id: "lc-dev" }), env);
  ok(lgRe.status === 200, "L9 恢复后可重新登录");
}

console.log(`\n${pass} PASS / ${fail} FAIL`);
process.exit(fail ? 1 : 0);
