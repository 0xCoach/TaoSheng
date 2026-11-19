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
    if (s.includes("UPDATE sessions SET refresh_hash")) { const r = this._row("sessions", x => x.id === a[3]); if (r) { r.refresh_hash = a[0]; r.expires_at = a[1]; r.last_seen_at = a[2]; } return r ? 1 : 0; }
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
}

console.log(`\n${pass} PASS / ${fail} FAIL`);
process.exit(fail ? 1 : 0);
