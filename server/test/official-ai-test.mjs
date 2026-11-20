// R6.2 官方 AI 网关/点数测试：内存 D1 shim + 真 worker fetch + mock 服务商。
// 运行: node server/test/official-ai-test.mjs   (Node >= 20)
import { readFileSync } from "fs";
import { webcrypto } from "crypto";

const subtle = webcrypto.subtle;
const b64 = (buf) => Buffer.from(buf).toString("base64");
const kp = await subtle.generateKey({ name: "ECDSA", namedCurve: "P-256" }, true, ["sign", "verify"]);
const pkcs8 = await subtle.exportKey("pkcs8", kp.privateKey);
const jwk = await subtle.exportKey("jwk", kp.publicKey);
const ADMIN_TOKEN = "test-admin-token";
const nowS = () => Math.floor(Date.now() / 1000);

// ---------- 内存 D1 ----------
const T = { users: [], sessions: [], licenses: [], audit_log: [], login_attempts: [],
  plans: [], entitlements: [], admin_sessions: [],
  ai_settings: [], ai_provider_configs: [], ai_quota_periods: [], ai_usage_requests: [],
  ai_usage_ledger: [], ai_point_adjustments: [], ai_consents: [] };

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
    if (s.includes("INSERT INTO users")) return I("users", ["id", "username", "pass_hash", "status", "capabilities", "activation_code", "created_at"]);
    if (s.includes("INSERT INTO sessions")) return I("sessions", ["id", "user_id", "device_id", "device_name", "refresh_hash", "created_at", "expires_at", "last_seen_at"]);
    if (s.includes("INSERT INTO licenses")) return I("licenses", ["id", "user_id", "device_id", "payload", "signature", "issued_at", "expires_at"]);
    if (s.includes("INSERT INTO entitlements")) return I("entitlements", ["id", "user_id", "capability", "source", "starts_at", "expires_at", "meta", "created_at"]);
    if (s.includes("INSERT INTO audit_log")) { T.audit_log.push({ user_id: a[0], event: a[1], detail: a[2], ip: a[3], at: a[4] }); return 1; }
    // ---- ai_* ----
    if (s.includes("INSERT OR REPLACE INTO ai_settings")) { const i = T.ai_settings.findIndex(x => x.key === "official"); if (i >= 0) T.ai_settings[i].value = a[0]; else T.ai_settings.push({ key: "official", value: a[0] }); return 1; }
    if (s.includes("INSERT OR REPLACE INTO ai_consents")) { const i = T.ai_consents.findIndex(x => x.user_id === a[0]); if (i >= 0) { T.ai_consents[i].version = a[1]; T.ai_consents[i].at = a[2]; } else T.ai_consents.push({ user_id: a[0], version: a[1], at: a[2] }); return 1; }
    if (s.includes("INSERT OR IGNORE INTO ai_quota_periods")) {
      if (this._row("ai_quota_periods", x => x.id === a[0])) return 0;
      T.ai_quota_periods.push({ id: a[0], user_id: a[1], plan: a[2], period_start: a[3], period_end: a[4], granted: a[5], used: 0, reserved: 0, status: "active", created_at: a[6] }); return 1;
    }
    if (s.includes("UPDATE ai_quota_periods SET reserved=reserved+?")) {
      const r = this._row("ai_quota_periods", x => x.id === a[1]);
      if (!r || (a[2] - r.used - r.reserved) < a[0]) return 0; // 原子条件：?-used-reserved >= reserve
      r.reserved += a[0]; return 1;
    }
    if (s.includes("UPDATE ai_quota_periods SET used=used+?")) { const r = this._row("ai_quota_periods", x => x.id === a[2]); if (!r) return 0; r.used += a[0]; r.reserved -= a[1]; return 1; }
    if (s.includes("UPDATE ai_quota_periods SET reserved=MAX(0,reserved-?")) { const r = this._row("ai_quota_periods", x => x.id === a[1]); if (!r) return 0; r.reserved = Math.max(0, r.reserved - a[0]); return 1; }
    if (s.includes("INSERT INTO ai_usage_requests")) {
      if (this._row("ai_usage_requests", x => x.user_id === a[1] && x.request_key === a[2])) return 0;
      T.ai_usage_requests.push({ id: a[0], user_id: a[1], request_key: a[2], task_type: a[3], status: a[4], provider_id: a[5], model: a[6], reserved_points: a[7], points: 0, input_tokens: 0, output_tokens: 0, estimated: 0, cost_milli: 0, error_code: null, result: null, created_at: a[8], settled_at: null }); return 1;
    }
    if (s.includes("UPDATE ai_usage_requests SET status='settled'")) {
      const r = this._row("ai_usage_requests", x => x.id === a[7]); if (!r) return 0;
      r.status = "settled"; r.points = a[0]; r.input_tokens = a[1]; r.output_tokens = a[2]; r.estimated = a[3]; r.cost_milli = a[4]; r.result = a[5]; r.settled_at = a[6]; return 1;
    }
    if (s.includes("UPDATE ai_usage_requests SET status='rejected'") || s.includes("UPDATE ai_usage_requests SET status='failed'")) {
      const r = this._row("ai_usage_requests", x => x.id === a[1]); if (!r) return 0;
      r.status = s.includes("'rejected'") ? "rejected" : "failed"; r.error_code = a[0]; return 1;
    }
    if (s.includes("INSERT INTO ai_usage_ledger")) return I("ai_usage_ledger", ["id", "user_id", "period_id", "request_id", "kind", "delta", "actor", "at"]);
    if (s.includes("INSERT INTO ai_point_adjustments")) return I("ai_point_adjustments", ["id", "user_id", "period_id", "points", "reason", "actor", "at"]);
    if (s.includes("UPDATE sessions SET refresh_hash=?")) {
      const r = this._row("sessions", x => x.id === a[5]); if (!r) return 0;
      r.refresh_hash = a[0]; r.prev_refresh_hash = a[1]; r.prev_valid_until = a[2]; r.expires_at = a[3]; r.last_seen_at = a[4]; return 1;
    }
    if (s.includes("INSERT OR REPLACE INTO login_attempts")) {
      // SQL: VALUES(?,1,?) —— count 是字面量 1，bind 只有 key+reset_at
      const i = T.login_attempts.findIndex(x => x.key === a[0]);
      const r = { key: a[0], count: 1, reset_at: a[1] };
      if (i >= 0) T.login_attempts[i] = r; else T.login_attempts.push(r); return 1;
    }
    if (s.includes("UPDATE login_attempts SET count=?")) { const r = this._row("login_attempts", x => x.key === a[1]); if (r) r.count = a[0]; return r ? 1 : 0; }
    if (s.includes("DELETE FROM login_attempts")) { const i = T.login_attempts.findIndex(x => x.key === a[0]); if (i >= 0) T.login_attempts.splice(i, 1); return i >= 0 ? 1 : 0; }
    if (s.includes("INSERT INTO ai_provider_configs")) return I("ai_provider_configs", ["id", "name", "api_base", "model", "api_key_enc", "max_input_tokens", "max_output_tokens", "cost_meta", "status", "is_default", "created_at", "updated_at"]);
    if (s.includes("UPDATE ai_provider_configs SET is_default=0")) { let n = 0; for (const r of T.ai_provider_configs) if (r.is_default) { r.is_default = 0; n++; } return n; }
    if (s.includes("UPDATE ai_provider_configs SET is_default=1")) { const r = this._row("ai_provider_configs", x => x.id === a[1]); if (r) { r.is_default = 1; r.status = "active"; r.updated_at = a[0]; } return r ? 1 : 0; }
    if (s.includes("UPDATE ai_provider_configs SET status='disabled'")) { const r = this._row("ai_provider_configs", x => x.id === a[1]); if (r) { r.status = "disabled"; r.is_default = 0; r.updated_at = a[0]; } return r ? 1 : 0; }
    if (s.includes("UPDATE ai_provider_configs SET status=?,updated_at=?")) { const r = this._row("ai_provider_configs", x => x.id === a[2]); if (r) { r.status = a[0]; r.updated_at = a[1]; } return r ? 1 : 0; }
    if (s.includes("UPDATE ai_provider_configs SET name=?")) { const r = this._row("ai_provider_configs", x => x.id === a[8]); if (!r) return 0; [r.name, r.api_base, r.model, r.api_key_enc, r.max_input_tokens, r.max_output_tokens, r.cost_meta, r.updated_at] = [a[0], a[1], a[2], a[3], a[4], a[5], a[6], a[7]]; return 1; }
    if (s.includes("login_attempts")) return 1;
    if (s.includes("usage_ledger WHERE") || s.includes("INSERT OR IGNORE INTO usage_ledger") || s.includes("INSERT INTO quota_adjustments") || s.includes("INSERT INTO redeem")) return 1;
    throw new Error("shim run: " + s);
  }
  async first() {
    const s = this.sql, a = this.args;
    if (s.includes("FROM admin_sessions WHERE id=?")) return this._row("admin_sessions", x => x.id === a[0] && !x.revoked_at) || null;
    if (s.includes("FROM users WHERE username=?")) return this._row("users", x => x.username === a[0]) || null;
    if (s.includes("FROM users WHERE id=?")) return this._row("users", x => x.id === a[0]) || null;
    if (s.includes("FROM sessions WHERE id=?")) return this._row("sessions", x => x.id === a[0] && !x.revoked_at) || null;
    if (s.includes("FROM sessions WHERE refresh_hash=?")) return this._row("sessions", x => x.refresh_hash === a[0] && !x.revoked_at) || null;
    if (s.includes("FROM sessions WHERE prev_refresh_hash=?")) return this._row("sessions", x => x.prev_refresh_hash === a[0] && !x.revoked_at && x.prev_valid_until > a[1]) || null;
    if (s.includes("FROM login_attempts WHERE key=?")) return this._row("login_attempts", x => x.key === a[0]) || null;
    if (s.includes("MAX(expires_at)")) {
      const rows = this._rows("entitlements", x => x.user_id === a[0] && x.capability === "pro" && !x.revoked_at && (x.expires_at == null || x.expires_at > a[1]));
      return rows.length ? { e: Math.max(...rows.map(r => r.expires_at ?? Infinity)) } : { e: null };
    }
    if (s.includes("MIN(starts_at)")) {
      const rows = this._rows("entitlements", x => x.user_id === a[0] && x.capability === "pro" && !x.revoked_at && (x.expires_at == null || x.expires_at > a[1]));
      return { s: rows.length ? Math.min(...rows.map(r => r.starts_at)) : null };
    }
    if (s.includes("FROM ai_settings WHERE key='official'")) return this._row("ai_settings", x => x.key === "official") || null;
    if (s.includes("FROM ai_consents WHERE user_id=?")) return this._row("ai_consents", x => x.user_id === a[0]) || null;
    if (s.includes("FROM ai_quota_periods WHERE id=?")) return this._row("ai_quota_periods", x => x.id === a[0]) || null;
    if (s.includes("ai_quota_periods WHERE user_id=? AND plan='pro' AND period_start<=?")) return this._rows("ai_quota_periods", x => x.user_id === a[0] && x.plan === "pro" && x.period_start <= a[1] && x.period_end > a[2]).sort((x, y) => y.period_start - x.period_start)[0] || null;
    if (s.includes("ai_quota_periods WHERE user_id=? AND plan='pro' ORDER BY period_end DESC")) return this._rows("ai_quota_periods", x => x.user_id === a[0] && x.plan === "pro").sort((x, y) => y.period_end - x.period_end)[0] || null;
    if (s.includes("SUM(points)") && s.includes("FROM ai_point_adjustments")) return { p: this._rows("ai_point_adjustments", x => x.period_id === a[0]).reduce((t, x) => t + x.points, 0) };
    if (s.includes("FROM ai_usage_requests WHERE user_id=? AND request_key=?")) return this._row("ai_usage_requests", x => x.user_id === a[0] && x.request_key === a[1]) || null;
    if (s.includes("COUNT(*) n FROM ai_usage_requests WHERE user_id=? AND status='settled' AND created_at>=?")) return { n: this._rows("ai_usage_requests", x => x.user_id === a[0] && x.status === "settled" && x.created_at >= a[1]).length };
    if (s.includes("SUM(points),0) p FROM ai_usage_requests WHERE user_id=? AND task_type='auto_digest'")) return { p: this._rows("ai_usage_requests", x => x.user_id === a[0] && x.task_type === "auto_digest" && x.status === "settled" && x.created_at >= a[1]).reduce((t, x) => t + x.points, 0) };
    if (s.includes("SUM(cost_milli),0) c FROM ai_usage_requests WHERE created_at>=?")) return { c: this._rows("ai_usage_requests", x => x.created_at >= a[0]).reduce((t, x) => t + (x.cost_milli || 0), 0) };
    if (s.includes("FROM ai_provider_configs WHERE id=?")) return this._row("ai_provider_configs", x => x.id === a[0]) || null;
    if (s.includes("ai_provider_configs WHERE status='active' ORDER BY is_default")) return this._rows("ai_provider_configs", x => x.status === "active").sort((x, y) => (y.is_default - x.is_default) || (x.created_at - y.created_at))[0] || null;
    if (s.includes("COUNT(*) n FROM users")) return { n: T.users.length };
    if (s.includes("COUNT(DISTINCT user_id)")) return { n: new Set(this._rows("ai_usage_requests", x => x.status === "settled").map(x => x.user_id)).size };
    if (s.includes("SUM(input_tokens)")) { const rs = this._rows("ai_usage_requests", x => x.status === "settled"); return { i: rs.reduce((t, x) => t + x.input_tokens, 0), o: rs.reduce((t, x) => t + x.output_tokens, 0), p: rs.reduce((t, x) => t + x.points, 0), c: rs.reduce((t, x) => t + x.cost_milli, 0) }; }
    if (s.includes("COUNT(*) n FROM ai_usage_requests WHERE status=")) { const st = s.includes("'settled'") ? "settled" : "failed"; return { n: this._rows("ai_usage_requests", x => x.status === st).length }; }
    if (s.includes("COUNT(*) n FROM ai_provider_configs")) return { n: this._rows("ai_provider_configs", x => x.status === "active").length };
    if (s.includes("FROM login_attempts")) return null;
    throw new Error("shim first: " + s);
  }
  async all() {
    const s = this.sql, a = this.args;
    if (s.includes("has_key FROM ai_provider_configs")) return { results: this._rows("ai_provider_configs").sort((x, y) => x.created_at - y.created_at).map(r => ({ id: r.id, name: r.name, api_base: r.api_base, model: r.model, protocol: "openai", max_input_tokens: r.max_input_tokens, max_output_tokens: r.max_output_tokens, cost_meta: r.cost_meta, status: r.status, is_default: r.is_default, created_at: r.created_at, updated_at: r.updated_at, has_key: r.api_key_enc ? 1 : 0 })) };
    if (s.includes("task_type,points,status,created_at,settled_at FROM ai_usage_requests WHERE user_id=?")) return { results: this._rows("ai_usage_requests", x => x.user_id === a[0]).sort((x, y) => y.created_at - x.created_at).slice(0, a[1]) };
    if (s.includes("q.plan, COALESCE(SUM(r.points)")) {
      const g = {};
      for (const r of this._rows("ai_usage_requests", x => x.status === "settled")) { const q = this._rows("ai_quota_periods", q => q.user_id === r.user_id && r.created_at >= q.period_start && r.created_at < q.period_end)[0]; if (q) g[q.plan] = (g[q.plan] || 0) + r.points; }
      return { results: Object.entries(g).map(([plan, p]) => ({ plan, p })) };
    }
    if (s.includes("created_at/86400 d")) { const rs = this._rows("ai_usage_requests", x => x.status === "settled" && x.created_at >= a[0]); return { results: rs.map(r => ({ d: Math.floor(r.created_at / 86400), n: 1, p: r.points, i: r.input_tokens, o: r.output_tokens })) }; }
    if (s.includes("r.user_id,u.username")) { const g = {}; for (const r of this._rows("ai_usage_requests", x => x.status === "settled")) { const k = r.user_id; g[k] = g[k] || { user_id: k, n: 0, p: 0, c: 0 }; g[k].n++; g[k].p += r.points; g[k].c += r.cost_milli; } return { results: Object.values(g).map(x => ({ user_id: x.user_id, username: this._row("users", u => u.id === x.user_id)?.username, n: x.n, p: x.p, c: x.c })) }; }
    if (s.includes("FROM ai_quota_periods q LEFT JOIN users")) return { results: this._rows("ai_quota_periods").sort((x, y) => y.period_start - x.period_start).slice(0, 300).map(q => ({ ...q, username: this._row("users", u => u.id === q.user_id)?.username })) };
    if (s.includes("FROM ai_usage_requests")) { const uid = s.includes("WHERE user_id=?") ? a[0] : null; return { results: this._rows("ai_usage_requests", x => !uid || x.user_id === uid).sort((x, y) => y.created_at - x.created_at).slice(0, 100) }; }
    if (s.includes("SELECT expires_at FROM entitlements WHERE user_id=? AND capability='pro'")) return { results: this._rows("entitlements", x => x.user_id === a[0] && x.capability === "pro" && !x.revoked_at && (x.expires_at == null || x.expires_at > a[1])).map(x => ({ expires_at: x.expires_at })) };
    if (s.includes("SELECT DISTINCT capability")) return { results: [...new Set(this._rows("entitlements", x => x.user_id === a[0] && !x.revoked_at && (x.expires_at == null || x.expires_at > a[1])).map(x => x.capability))].map(c => ({ capability: c })) };
    if (s.includes("FROM login_attempts") || s.includes("FROM usage_ledger") || s.includes("redeem")) return { results: [] };
    throw new Error("shim all: " + s);
  }
}
const DB = { prepare: (sql) => new Stmt(sql), batch: async (stmts) => { const out = []; for (const s of stmts) out.push(await s.run()); return out; } };
const env = { DB, ADMIN_TOKEN, AI_KEY_SECRET: "test-ai-key-secret", LICENSE_PRIVATE_KEY: b64(pkcs8), LICENSE_PUBLIC_JWK: JSON.stringify(jwk),
  ACCESS_TTL_SECONDS: 900, REFRESH_TTL_SECONDS: 2592000, OFFLINE_LICENSE_DAYS: 7, LOGIN_RATE_LIMIT: 8, LOGIN_RATE_WINDOW_SECONDS: 600 };

// ---------- mock 服务商 fetch ----------
let mockMode = "ok"; // ok / fail500 / rate429 / badjson / nousage / timeout
const origFetch = globalThis.fetch;
globalThis.fetch = async (url, init) => {
  if (String(url).includes("chat/completions")) {
    if (mockMode === "timeout") return new Promise((_, rj) => setTimeout(() => rj(Object.assign(new Error("aborted"), { name: "AbortError" })), 100));
    if (mockMode === "fail500") return new Response("oops", { status: 500 });
    if (mockMode === "rate429") return new Response("rate", { status: 429 });
    if (mockMode === "badjson") return new Response("not-json", { status: 200 });
    const usage = mockMode === "nousage" ? null : { prompt_tokens: 2000, completion_tokens: 500 };
    return new Response(JSON.stringify({ choices: [{ message: { content: JSON.stringify({ title: "测试纪要", ok: true }) } }], usage }), { status: 200 });
  }
  return origFetch(url, init);
};

// ---------- 载入 worker ----------
const strip = (s, name) => s.replace(`export async function ${name}`, `async function ${name}`);
const wsrc = readFileSync(new URL("../src/worker.js", import.meta.url), "utf8")
  .replace('import { handleMembership } from "./membership.js";', "")
  .replace('import { handleAi } from "./ai.js";', "")
  .replace("export default {", "const _default = {");
const msrc = readFileSync(new URL("../src/membership.js", import.meta.url), "utf8").replace("export async function handleMembership", "async function handleMembership");
const asrc = readFileSync(new URL("../src/ai.js", import.meta.url), "utf8").replace("export async function handleAi", "async function handleAi");
const mod = await import("data:text/javascript;base64," + Buffer.from(msrc + "\n" + asrc + "\n" + wsrc + "\nexport default _default;").toString("base64"));
const call = (req, env) => mod.default.fetch(req, env);

let pass = 0, fail = 0;
const ok = (cond, name) => { if (cond) { pass++; console.log("  PASS", name); } else { fail++; console.log("  FAIL", name); } };
const req = (path, method = "GET", body, headers = {}) => new Request("http://t" + path, { method, body: body ? JSON.stringify(body) : undefined, headers: { "content-type": "application/json", ...headers } });
const j = async (r) => r.json();

T.plans.push({ id: "free" }, { id: "pro" });

// ===== 准备：建用户 + 登录 + 管理台会话 =====
await call(req("/admin/accounts", "POST", { username: "u1", password: "pw1" }, { authorization: "Bearer " + ADMIN_TOKEN }), env);
const lb = await j(await call(req("/auth/login", "POST", { username: "u1", password: "pw1", device_id: "dev1" }), env));
const authH = { authorization: "Bearer " + lb.access_token };
const sr = await call(req("/console/api/session", "POST", { token: ADMIN_TOKEN }), env);
const sb = await j(sr); const ck = (sr.headers.get("set-cookie") || "").split(";")[0];
const ah = { cookie: ck, "x-csrf": sb.csrf };

// ===== 1. 游客/未登录 =====
let r = await call(req("/ai/official/status"), env); ok(r.status === 401, "unauth status -> 401");
r = await call(req("/ai/official/generate", "POST", { request_id: "x", task_type: "digest", user: "hi" }), env);
ok(r.status === 401, "unauth generate -> 401（游客不开放）");

// ===== 2. 同意 + 额度（Free 100/月）=====
r = await call(req("/ai/official/quota", "GET", null, authH), env);
let qb = await j(r);
ok(qb.plan === "free" && qb.granted === 100 && qb.remaining === 100, "Free 首月发放 100 点");
const st0 = await j(await call(req("/ai/official/status", "GET", null, authH), env)); ok(st0.consented === false, "未同意状态");
await call(req("/ai/official/consent", "POST", { version: 1 }, authH), env);
r = await call(req("/ai/official/generate", "POST", { request_id: "n1", task_type: "digest", user: "x".repeat(500) }, authH), env);
ok(r.status === 503, "未配置服务商 -> PROVIDER_UNAVAILABLE（不假装可用）");
const est = await j(await call(req("/ai/official/estimate", "POST", { task_type: "digest", chars: 20000 }, authH), env));
ok(Array.isArray(est.estimate) && est.estimate[0] > 0 && est.estimate[1] >= est.estimate[0], "estimate 返回区间");

// ===== 3. 管理台：服务商 =====
r = await call(req("/console/api/ai/providers", "POST", { name: "bad", api_base: "https://evil.example.com", model: "m", api_key: "sk-x" }, ah), env);
ok(r.status === 403, "SSRF 域名拒绝");
r = await call(req("/console/api/ai/providers", "POST", { name: "ds", api_base: "https://api.deepseek.com", model: "deepseek-chat", api_key: "sk-test-123", cost_meta: { in_per_1k: 0.1, out_per_1k: 0.2 } }, ah), env);
const pid = (await j(r)).id; ok(!!pid, "添加服务商");
const pl = (await j(await call(req("/console/api/ai/providers", "GET", null, ah), env))).providers[0];
ok(pl.has_key === true && !("api_key" in pl) && !("api_key_enc" in pl), "列表不返回密钥明文");
const tst = await j(await call(req(`/console/api/ai/providers/${pid}/test`, "POST", {}, ah), env));
ok(tst.ok === true && typeof tst.ms === "number", "测试连接 OK（mock）");
await call(req(`/console/api/ai/providers/${pid}/default`, "POST", {}, ah), env);
const rp = await call(req("/console/api/ai/rules", "PATCH", { enabled: true }, ah), env);
ok(rp.status === 200, "启用官方 AI 开关");
// 密钥确实加密存储（D1 行里不是明文）
ok(!T.ai_provider_configs[0].api_key_enc.includes("sk-test-123") && T.ai_provider_configs[0].api_key_enc.length > 0, "D1 存密文");

// ===== 4. 生成成功扣点 + 幂等 =====
r = await call(req("/ai/official/generate", "POST", { request_id: "g1", task_type: "digest", user: "x".repeat(4000), system: "sys" }, authH), env);
let gb = await j(r);
ok(r.status === 200 && gb.points === 4 && gb.content.includes("测试纪要"), `成功扣点（2000in+500out=4点，得 ${gb.points}）`);
ok(gb.balance.used === 4 && gb.balance.remaining === 96, "余额 96/100");
r = await call(req("/ai/official/generate", "POST", { request_id: "g1", task_type: "digest", user: "x".repeat(4000) }, authH), env);
gb = await j(r); ok(gb.duplicated === true && gb.points === 4, "同 request_id 幂等重放不重复扣");
qb = await j(await call(req("/ai/official/quota", "GET", null, authH), env));
ok(qb.used === 4 && qb.reserved === 0, "重放后 used 仍 4、reserved 归零");

// ===== 5. 失败不扣点 =====
mockMode = "fail500";
r = await call(req("/ai/official/generate", "POST", { request_id: "g2", task_type: "digest", user: "x".repeat(4000) }, authH), env);
ok(r.status === 503, "服务商 500 -> PROVIDER_UNAVAILABLE");
qb = await j(await call(req("/ai/official/quota", "GET", null, authH), env));
ok(qb.used === 4 && qb.reserved === 0, "失败释放预留不扣点");
mockMode = "rate429";
r = await call(req("/ai/official/generate", "POST", { request_id: "g3", task_type: "digest", user: "x".repeat(4000) }, authH), env);
ok(r.status === 429, "服务商 429 -> RATE_LIMITED");
qb = await j(await call(req("/ai/official/quota", "GET", null, authH), env));
ok(qb.used === 4, "429 后仍不扣");
mockMode = "badjson";
r = await call(req("/ai/official/generate", "POST", { request_id: "g4", task_type: "digest", user: "x".repeat(4000) }, authH), env);
ok(r.status === 502, "坏 JSON -> TASK_FAILED 不扣点");
qb = await j(await call(req("/ai/official/quota", "GET", null, authH), env)); ok(qb.used === 4, "TASK_FAILED 余额不变");
mockMode = "nousage";
r = await call(req("/ai/official/generate", "POST", { request_id: "g5", task_type: "digest", user: "x".repeat(4000) }, authH), env);
gb = await j(r); ok(r.status === 200 && gb.points >= 1, "无 usage 回包 -> 估算扣点（ESTIMATED）");
mockMode = "ok";

// ===== 6. Free 禁止自动整理 =====
r = await call(req("/ai/official/generate", "POST", { request_id: "a1", task_type: "auto_digest", user: "x".repeat(4000) }, authH), env);
ok(r.status === 402, "Free 自动整理 -> QUOTA_EXHAUSTED");

// ===== 7. 额度耗尽 =====
const uid = T.users.find(u => u.username === "u1").id;
const period = T.ai_quota_periods.find(p => p.user_id === uid);
await call(req("/console/api/ai/users/adjust", "POST", { user_id: uid, points: -94, reason: "测试扣减" }, ah), env);
r = await call(req("/ai/official/generate", "POST", { request_id: "g6", task_type: "digest", user: "x".repeat(4000) }, authH), env);
ok(r.status === 402, "额度不足 -> QUOTA_EXHAUSTED，无负余额");
await call(req("/console/api/ai/users/adjust", "POST", { user_id: uid, points: 94, reason: "恢复" }, ah), env);

// ===== 8. Pro 升级：1000 点/30 天周期 =====
const pstart = nowS() - 60;
await DB.prepare("INSERT INTO entitlements(id,user_id,capability,source,starts_at,expires_at,meta,created_at) VALUES(?,?,?,?,?,?,?,?)")
  .bind("e1", uid, "pro", "redeem", pstart, pstart + 30 * 86400, "{}", pstart).run();
qb = await j(await call(req("/ai/official/quota", "GET", null, authH), env));
ok(qb.plan === "pro" && qb.granted === 1000 && (qb.period_end - qb.period_start) === 30 * 86400 && qb.used === 0, "Pro 周期发放 1000 点/30 天，Free 池不叠加");
// 重复调用不重复发点
qb = await j(await call(req("/ai/official/quota", "GET", null, authH), env));
ok(T.ai_quota_periods.filter(p => p.user_id === uid && p.plan === "pro").length === 1, "Pro 周期幂等不重复发点");

// ===== 9. 年付周期：11×30 + 35 天锚定 =====
T.entitlements.find(e => e.id === "e1").starts_at = nowS() - 330 * 86400;
T.entitlements.find(e => e.id === "e1").expires_at = nowS() + 35 * 86400;
T.ai_quota_periods = T.ai_quota_periods.filter(p => !(p.user_id === uid && p.plan === "pro"));
qb = await j(await call(req("/ai/official/quota", "GET", null, authH), env));
const per = T.ai_quota_periods.find(p => p.user_id === uid && p.plan === "pro");
const perLen = per.period_end - per.period_start;
ok(qb.granted === 1000 && perLen >= 34 * 86400, `年付第 12 期合并残尾（本期 ${Math.round(perLen / 86400)} 天 ≈ 35 天）`);
ok(per.period_end - nowS() > 0, "当前期覆盖 now");
T.entitlements.find(e => e.id === "e1").starts_at = pstart;
T.entitlements.find(e => e.id === "e1").expires_at = pstart + 30 * 86400;
T.ai_quota_periods = T.ai_quota_periods.filter(p => !(p.user_id === uid && p.plan === "pro"));
// 恢复 pro 期（quota 会重建）
await j(await call(req("/ai/official/quota", "GET", null, authH), env));

// ===== 9.5 Pro 到期 → 回 Free 点数（生命周期回归）=====
const eRow = T.entitlements.find(e => e.id === "e1");
const keepStart = eRow.starts_at, keepExp = eRow.expires_at;
eRow.expires_at = nowS() - 60;
qb = await j(await call(req("/ai/official/quota", "GET", null, authH), env));
ok(qb.plan === "free" && qb.granted === 100, "Pro 到期后点数回 Free 档（每月 100）");
eRow.starts_at = keepStart; eRow.expires_at = keepExp;
await j(await call(req("/ai/official/quota", "GET", null, authH), env));

// ===== 10. 管理分析 + 请求记录 =====
const an = await j(await call(req("/console/api/ai/usage", "GET", null, ah), env));
ok(an.users === 1 && an.ai_users === 1 && an.success >= 2 && an.failed >= 3 && an.points === 6, "管理分析汇总");
const ulist = await j(await call(req("/console/api/ai/users", "GET", null, ah), env));
ok(ulist.periods.some(p => p.user_id === uid && p.granted === 1000), "用户点数列表");
const rqs = await j(await call(req("/console/api/ai/requests", "GET", null, ah), env));
ok(rqs.requests.length >= 5 && rqs.requests.some(x => x.status === "failed"), "请求流水可查（含失败）");
const usage = await j(await call(req("/ai/official/usage", "GET", null, authH), env));
ok(usage.items.length >= 5 && usage.items.every(x => !x.input_tokens), "用户用量记录（无 token 字段）");

// ===== 11. 一键暂停 / 开关 =====
await call(req("/console/api/ai/rules", "PATCH", { paused: true }, ah), env);
r = await call(req("/ai/official/generate", "POST", { request_id: "g7", task_type: "digest", user: "x".repeat(100) }, authH), env);
ok(r.status === 503, "一键暂停 -> PROVIDER_UNAVAILABLE");
await call(req("/console/api/ai/rules", "PATCH", { paused: false }, ah), env);

// ===== 12. 每日任务上限 =====
await call(req("/console/api/ai/rules", "PATCH", { pro_daily_tasks: 0 }, ah), env);
r = await call(req("/ai/official/generate", "POST", { request_id: "g8", task_type: "digest", user: "x".repeat(100) }, authH), env);
ok(r.status === 429, "每日任务上限 -> RATE_LIMITED");
await call(req("/console/api/ai/rules", "PATCH", { pro_daily_tasks: 60 }, ah), env);

// ===== 13. 管理台未授权 =====
r = await call(req("/console/api/ai/providers", "GET"), env);
ok(r.status === 401, "无会话管理 API -> 401");

console.log(`\n${pass} PASS / ${fail} FAIL`);
process.exit(fail ? 1 : 0);
