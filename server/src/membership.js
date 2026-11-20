// R4.3 会员/权益/用量/兑换码 + /console 管理台（复用 worker.js 注入的工具，不另建账号体系）
// 安全约定：兑换码只存哈希；后台不存音频/转写/记忆/AI Key；管理操作进 audit_log。

const FREE_MONTHLY_MINUTES = 1800;
const PRO_CAPS = ["local_asr_minutes", "cross_meeting_tracking", "advanced_project_analysis", "agent_workflow"];

export async function handleMembership(req, env, H) {
  const url = new URL(req.url);
  const path = url.pathname;
  const { json, err, timingSafeEq, uuid, nowS, sha256b64, hashPassword, audit, authed } = H;

  // ---------- 管理台会话 ----------
  async function adminSession() {
    const c = (req.headers.get("Cookie") || "").match(/sad=([^;]+)/);
    if (!c) return null;
    const [sid, secret] = c[1].split(":");
    if (!sid || !secret) return null;
    const row = await env.DB.prepare("SELECT * FROM admin_sessions WHERE id=? AND revoked_at IS NULL").bind(sid).first();
    if (!row || row.expires_at < nowS()) return null;
    if (!timingSafeEq(await sha256b64(secret), row.token_hash)) return null;
    return { id: sid, csrf: row.csrf };
  }
  // 非 GET 必须带 x-csrf（双提交防护）
  async function admin() {
    const s = await adminSession();
    if (!s) return null;
    if (req.method !== "GET" && req.headers.get("x-csrf") !== s.csrf) return "csrf";
    return s;
  }
  const cookie = (sid, secret, maxAge) =>
    `sad=${sid}:${secret}; HttpOnly; SameSite=Strict; Secure; Path=/; Max-Age=${maxAge}`;
  const withCookie = (obj, ck) => new Response(JSON.stringify(obj),
    { status: 200, headers: { "content-type": "application/json; charset=utf-8", "set-cookie": ck } });

  // ---------- 页面 ----------
  if (req.method === "GET" && path === "/console") {
    return new Response(CONSOLE_HTML, { headers: { "content-type": "text/html; charset=utf-8" } });
  }

  if (!path.startsWith("/console/api") && !path.startsWith("/auth/")) return null;

  // ---------- 会话 ----------
  if (req.method === "POST" && path === "/console/api/session") {
    const b = await req.json().catch(() => ({}));
    if (!timingSafeEq(b.token || "", env.ADMIN_TOKEN)) return err("unauthorized", 401);
    const sid = uuid(), secret = b64r(), csrf = b64r();
    await env.DB.prepare("INSERT INTO admin_sessions(id,token_hash,csrf,created_at,expires_at) VALUES(?,?,?,?,?)")
      .bind(sid, await sha256b64(secret), csrf, nowS(), nowS() + 12 * 3600).run();
    return withCookie({ ok: true, csrf }, cookie(sid, secret, 12 * 3600));
  }
  if (path === "/console/api/session") {
    const s = await adminSession();
    if (!s) return err("unauthorized", 401);
    if (req.method === "DELETE") {
      await env.DB.prepare("UPDATE admin_sessions SET revoked_at=? WHERE id=?").bind(nowS(), s.id).run();
      return withCookie({ ok: true }, "sad=; HttpOnly; SameSite=Strict; Secure; Path=/; Max-Age=0");
    }
    return json({ ok: true, csrf: s.csrf });
  }

  // ---------- 用户端 ----------
  if (req.method === "GET" && path === "/auth/membership") {
    const a = await authed(env, req);
    if (!a) return err("unauthorized", 401);
    return json(await membershipView(env, a.user.id, H));
  }
  if (req.method === "POST" && path === "/auth/usage") {
    const a = await authed(env, req);
    if (!a) return err("unauthorized", 401);
    const b = await req.json().catch(() => ({}));
    const items = Array.isArray(b.items) ? b.items.slice(0, 500) : [];
    let accepted = 0;
    for (const it of items) {
      if (!it.id || !(it.minutes > 0) || !/^\d{4}-\d{2}$/.test(it.month || "")) continue;
      const r = await env.DB.prepare(
        "INSERT OR IGNORE INTO usage_ledger(id,user_id,device_id,minutes,month,at) VALUES(?,?,?,?,?,?)")
        .bind(String(it.id), a.user.id, a.session.device_id, it.minutes, it.month, nowS()).run();
      accepted += r.meta.changes || 0;
    }
    const v = await membershipView(env, a.user.id, H);
    return json({ accepted, usage: v.usage, plan: v.plan, pro_until: v.pro_until });
  }
  if (req.method === "POST" && path === "/auth/redeem") {
    const a = await authed(env, req);
    if (!a) return err("unauthorized", 401);
    const b = await req.json().catch(() => ({}));
    if (!b.code) return err("code required");
    // 幂等键按账号隔离：request_id 存为 user:id 复合串——同账号重试返回原结果，
    // 不同账号撞 id 不互相串号（request_id 列全局 UNIQUE，复合键天然兼容）。
    const rid = b.request_id ? `${a.user.id}:${String(b.request_id)}` : null;
    if (rid) {
      const dup = await env.DB.prepare("SELECT * FROM redeem_records WHERE request_id=?").bind(rid).first();
      if (dup) return json({ ok: true, granted_days: dup.granted_days, pro_until: (await membershipView(env, a.user.id, H)).pro_until, duplicated: true });
    }
    const code = await env.DB.prepare("SELECT * FROM redeem_codes WHERE code_hash=?").bind(await sha256b64(normalizeCode(b.code))).first();
    if (!code) return err("invalid code", 404);
    const campaign = await env.DB.prepare("SELECT * FROM redeem_campaigns WHERE id=?").bind(code.campaign_id).first();
    if (!campaign) return err("invalid code", 404);
    // 同一用户重兑同一码：幂等返回原结果（网络重试不重复发权益）
    if (code.status === "redeemed" && code.redeemed_by === a.user.id) {
      const r = await env.DB.prepare("SELECT granted_days FROM redeem_records WHERE code_id=? AND user_id=?").bind(code.id, a.user.id).first();
      if (r) return json({ ok: true, granted_days: r.granted_days, pro_until: (await membershipView(env, a.user.id, H)).pro_until, duplicated: true });
    }
    if (campaign.status !== "active" || code.status !== "active") return err("code not redeemable", 409);
    // 原子认领：并发/重试下只有一个请求能翻状态
    const claim = await env.DB.prepare(
      "UPDATE redeem_codes SET status='redeemed',redeemed_by=?,redeemed_at=? WHERE id=? AND status='active'")
      .bind(a.user.id, nowS(), code.id).run();
    if (!claim.meta.changes) return err("code already redeemed", 409);
    const granted = await grantPro(env, a.user.id, campaign.grant_days, `redeem:${code.id}`, nowS, uuid);
    await env.DB.prepare("INSERT INTO redeem_records(id,code_id,campaign_id,user_id,request_id,granted_days,at) VALUES(?,?,?,?,?,?,?)")
      .bind(uuid(), code.id, campaign.id, a.user.id, rid, campaign.grant_days, nowS()).run();
    await audit(env, a.user.id, "redeem.ok", `${campaign.id}/${code.id}+${campaign.grant_days}d`, req);
    return json({ ok: true, granted_days: campaign.grant_days, pro_until: granted });
  }

  // ---------- 管理台 API（以下全部需要管理会话）----------
  // 未匹配到的 /auth/* 放行给 worker.js 原认证路由；非 console/api 的其余路径也放行。
  if (path.startsWith("/auth/")) return null;
  if (!path.startsWith("/console/api")) return null;
  const adm = await admin();
  if (adm === "csrf") return err("bad csrf", 403);
  if (!adm) return err("unauthorized", 401);

  if (req.method === "GET" && path === "/console/api/overview") {
    const c = async (sql) => (await env.DB.prepare(sql).first())?.n || 0;
    return json({
      users: await c("SELECT COUNT(*) n FROM users"),
      pro: await c("SELECT COUNT(DISTINCT user_id) n FROM entitlements WHERE capability='pro' AND revoked_at IS NULL AND expires_at>" + nowS()),
      codes_active: await c("SELECT COUNT(*) n FROM redeem_codes WHERE status='active'"),
      codes_redeemed: await c("SELECT COUNT(*) n FROM redeem_codes WHERE status='redeemed'"),
    });
  }

  if (path === "/console/api/users") {
    if (req.method === "GET") {
      const r = await env.DB.prepare("SELECT id,username,status,capabilities,created_at,disabled_at FROM users ORDER BY created_at DESC").all();
      const ents = await env.DB.prepare(
        "SELECT user_id,capability,expires_at FROM entitlements WHERE revoked_at IS NULL AND (expires_at IS NULL OR expires_at>?)").bind(nowS()).all();
      const byUser = {};
      for (const e of ents.results) (byUser[e.user_id] ??= []).push(`${e.capability}${e.expires_at ? "→" + e.expires_at : ""}`);
      return json({ users: r.results.map(u => ({ ...u, entitlements: byUser[u.id] || [] })) });
    }
    if (req.method === "POST") {
      const b = await req.json();
      if (!b.username || !b.password) return err("username/password required");
      const exists = await env.DB.prepare("SELECT id FROM users WHERE username=?").bind(b.username).first();
      if (exists) return err("username exists", 409);
      const id = uuid();
      const caps = b.capabilities || H.CAPS_DEFAULT;
      await env.DB.prepare(
        "INSERT INTO users(id,username,pass_hash,status,capabilities,activation_code,created_at) VALUES(?,?,?,?,?,?,?)")
        .bind(id, b.username, await hashPassword(b.password),
          b.activation_code ? "invited" : "active", JSON.stringify(caps), b.activation_code || null, nowS()).run();
      await audit(env, id, "admin.create", b.username, req);
      return json({ id, username: b.username, status: b.activation_code ? "invited" : "active", capabilities: caps });
    }
  }
  if (req.method === "PATCH" && path.startsWith("/console/api/users/")) {
    const id = path.split("/").pop();
    const b = await req.json();
    const st = b.disabled ? "disabled" : "active";
    const r = await env.DB.prepare("UPDATE users SET status=?,disabled_at=? WHERE id=?").bind(st, b.disabled ? nowS() : null, id).run();
    if (b.disabled) await env.DB.prepare("UPDATE sessions SET revoked_at=? WHERE user_id=? AND revoked_at IS NULL").bind(nowS(), id).run();
    await audit(env, id, b.disabled ? "admin.disable" : "admin.enable", "", req);
    return json({ changed: r.meta.changes });
  }
  if (req.method === "POST" && path.match(/^\/console\/api\/users\/[^/]+\/reset_password$/)) {
    const id = path.split("/")[4];
    const b = await req.json();
    if (!b.password || b.password.length < 8 || b.password.length > 128) return err("password length 8-128");
    const r = await env.DB.prepare("UPDATE users SET pass_hash=? WHERE id=?").bind(await hashPassword(b.password), id).run();
    if (!r.meta.changes) return err("user not found", 404);
    await env.DB.prepare("UPDATE sessions SET revoked_at=? WHERE user_id=? AND revoked_at IS NULL").bind(nowS(), id).run();
    await audit(env, id, "admin.reset_password", "", req);
    return json({ ok: true });
  }
  if (req.method === "DELETE" && path.match(/^\/console\/api\/users\/[^/]+$/)) {
    const id = path.split("/").pop();
    for (const t of ["sessions", "licenses", "entitlements", "usage_ledger", "quota_adjustments", "redeem_records", "ai_quota_periods", "ai_usage_requests", "ai_usage_ledger", "ai_point_adjustments", "ai_consents"]) {
      await env.DB.prepare(`DELETE FROM ${t} WHERE user_id=?`).bind(id).run();
    }
    const r = await env.DB.prepare("DELETE FROM users WHERE id=?").bind(id).run();
    if (!r.meta.changes) return err("user not found", 404);
    await audit(env, id, "admin.delete_user", "", req);
    return json({ ok: true });
  }

  if (path === "/console/api/memberships") {
    if (req.method === "GET") {
      const r = await env.DB.prepare(
        `SELECT e.*, u.username FROM entitlements e LEFT JOIN users u ON u.id=e.user_id
         ORDER BY e.created_at DESC LIMIT 200`).all();
      return json({ entitlements: r.results });
    }
    if (req.method === "POST") {
      const b = await req.json();
      const u = await env.DB.prepare("SELECT id FROM users WHERE id=? OR username=?").bind(b.user_id || "", b.username || "").first();
      if (!u) return err("user not found", 404);
      if (b.capability === "pro") {
        const exp = await grantPro(env, u.id, Number(b.days || 30), "admin", nowS, uuid);
        await audit(env, u.id, "admin.grant_pro", `${b.days || 30}d`, req);
        return json({ ok: true, pro_until: exp });
      }
      await env.DB.prepare("INSERT INTO entitlements(id,user_id,capability,source,starts_at,expires_at,created_at) VALUES(?,?,?,?,?,?,?)")
        .bind(uuid(), u.id, String(b.capability), "admin", nowS(), b.days ? nowS() + Number(b.days) * 86400 : null, nowS()).run();
      await audit(env, u.id, "admin.grant_cap", String(b.capability), req);
      return json({ ok: true });
    }
  }
  if (req.method === "DELETE" && path.startsWith("/console/api/memberships/")) {
    const id = path.split("/").pop();
    const r = await env.DB.prepare("UPDATE entitlements SET revoked_at=? WHERE id=? AND revoked_at IS NULL").bind(nowS(), id).run();
    await audit(env, null, "admin.revoke_ent", id, req);
    return json({ changed: r.meta.changes });
  }

  if (path === "/console/api/usage") {
    const month = url.searchParams.get("month") || new Date().toISOString().slice(0, 7);
    const used = await env.DB.prepare(
      "SELECT user_id, SUM(minutes) m FROM usage_ledger WHERE month=? GROUP BY user_id").bind(month).all();
    const adj = await env.DB.prepare("SELECT * FROM quota_adjustments WHERE month=?").bind(month).all();
    const users = await env.DB.prepare("SELECT id,username FROM users").all();
    const name = {}; for (const u of users.results) name[u.id] = u.username;
    return json({ month, limit: FREE_MONTHLY_MINUTES,
      usage: used.results.map(r => ({ user_id: r.user_id, username: name[r.user_id], minutes: Math.round(r.m * 10) / 10 })),
      adjustments: adj.results });
  }
  if (req.method === "POST" && path === "/console/api/usage/adjustments") {
    const b = await req.json();
    if (!b.user_id || !/^-?\d+(\.\d+)?$/.test(String(b.minutes)) || !/^\d{4}-\d{2}$/.test(b.month || "")) return err("bad input");
    await env.DB.prepare("INSERT INTO quota_adjustments(id,user_id,month,minutes,reason,actor,at) VALUES(?,?,?,?,?,?,?)")
      .bind(uuid(), b.user_id, b.month, Number(b.minutes), b.reason || "", "console", nowS()).run();
    await audit(env, b.user_id, "admin.usage_adjust", `${b.month} ${b.minutes}m`, req);
    return json({ ok: true });
  }

  if (path === "/console/api/codes/batches") {
    if (req.method === "GET") {
      const camps = await env.DB.prepare("SELECT * FROM redeem_campaigns ORDER BY created_at DESC").all();
      const counts = await env.DB.prepare("SELECT campaign_id,status,COUNT(*) n FROM redeem_codes GROUP BY campaign_id,status").all();
      const by = {};
      for (const c of counts.results) { (by[c.campaign_id] ??= {})[c.status] = c.n; }
      return json({ batches: camps.results.map(c => ({ ...c, counts: by[c.id] || {} })) });
    }
    if (req.method === "POST") {
      const b = await req.json();
      const count = Math.min(Math.max(Number(b.count) || 0, 1), 10000);
      const days = Number(b.grant_days) || 30;
      const cid = uuid();
      await env.DB.prepare("INSERT INTO redeem_campaigns(id,name,grant_days,status,created_at) VALUES(?,?,?,'draft',?)")
        .bind(cid, b.name || `batch-${cid.slice(0, 8)}`, days, nowS()).run();
      const codes = [];
      const stmts = [];
      for (let i = 0; i < count; i++) {
        const code = makeCode();
        codes.push(code);
        stmts.push(env.DB.prepare("INSERT INTO redeem_codes(id,campaign_id,code_hash,status,created_at) VALUES(?,?,?,'draft',?)")
          .bind(uuid(), cid, await sha256b64(normalizeCode(code)), nowS()));
      }
      await env.DB.batch(stmts);
      await audit(env, null, "admin.codes_batch", `${cid} x${count} ${days}d`, req);
      // 明文仅此一次返回；服务端只存哈希
      return json({ batch_id: cid, count, grant_days: days, status: "draft", codes });
    }
  }
  if (req.method === "POST" && /^\/console\/api\/codes\/batches\/[^/]+\/activate$/.test(path)) {
    const cid = path.split("/")[5];
    await env.DB.batch([
      env.DB.prepare("UPDATE redeem_campaigns SET status='active',activated_at=? WHERE id=? AND status='draft'").bind(nowS(), cid),
      env.DB.prepare("UPDATE redeem_codes SET status='active' WHERE campaign_id=? AND status='draft'").bind(cid),
    ]);
    await audit(env, null, "admin.codes_activate", cid, req);
    return json({ ok: true });
  }
  if (req.method === "GET" && /^\/console\/api\/codes\/batches\/[^/]+\/codes$/.test(path)) {
    const cid = path.split("/")[5];
    const r = await env.DB.prepare(
      "SELECT id,status,redeemed_by,redeemed_at FROM redeem_codes WHERE campaign_id=? ORDER BY created_at LIMIT 20000").bind(cid).all();
    const names = {};
    for (const u of (await env.DB.prepare("SELECT id,username FROM users").all()).results) names[u.id] = u.username;
    return json({ codes: r.results.map(c => ({ id: c.id, status: c.status,
      redeemed_by: names[c.redeemed_by] || c.redeemed_by || null, redeemed_at: c.redeemed_at })) });
  }
  if (req.method === "POST" && /^\/console\/api\/codes\/batches\/[^/]+\/disable$/.test(path)) {
    const cid = path.split("/")[5];
    await env.DB.batch([
      env.DB.prepare("UPDATE redeem_campaigns SET status='disabled',disabled_at=? WHERE id=?").bind(nowS(), cid),
      env.DB.prepare("UPDATE redeem_codes SET status='revoked' WHERE campaign_id=? AND status IN ('draft','active')").bind(cid),
    ]);
    await audit(env, null, "admin.codes_disable", cid, req);
    return json({ ok: true });
  }

  if (req.method === "GET" && path === "/console/api/redemptions") {
    const r = await env.DB.prepare(
      `SELECT r.*, u.username, c.name campaign_name FROM redeem_records r
       LEFT JOIN users u ON u.id=r.user_id LEFT JOIN redeem_campaigns c ON c.id=r.campaign_id
       ORDER BY r.at DESC LIMIT 200`).all();
    return json({ redemptions: r.results });
  }
  if (req.method === "GET" && path === "/console/api/audit") {
    const r = await env.DB.prepare("SELECT a.*, u.username FROM audit_log a LEFT JOIN users u ON u.id=a.user_id ORDER BY a.at DESC LIMIT 200").all();
    return json({ audit: r.results });
  }
  if (path === "/console/api/plans") {
    if (req.method === "GET") return json({ plans: (await env.DB.prepare("SELECT * FROM plans").all()).results });
    if (req.method === "PATCH") {
      const b = await req.json();
      if (typeof b.config !== "object") return err("config object required");
      const r = await env.DB.prepare("UPDATE plans SET name=COALESCE(?,name),config=? WHERE id=?")
        .bind(b.name || null, JSON.stringify(b.config), b.id).run();
      await audit(env, null, "admin.plan_update", b.id, req);
      return json({ changed: r.meta.changes });
    }
  }

  return err("not found", 404);
}

// ---------- 内部 ----------
const enc2 = new TextEncoder();
function b64r() { return btoa(String.fromCharCode(...crypto.getRandomValues(new Uint8Array(24)))).replaceAll("+", "-").replaceAll("/", "_").replaceAll("=", ""); }
function normalizeCode(s) { return String(s).toUpperCase().replace(/[^A-Z0-9]/g, ""); }
function makeCode() {
  const a = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // 去易混淆字符
  let s = "";
  const rnd = crypto.getRandomValues(new Uint8Array(12));
  for (const b of rnd) s += a[b % a.length];
  return `SS-${s.slice(0, 4)}-${s.slice(4, 8)}-${s.slice(8, 12)}`;
}
/** Pro 顺延：已有有效 Pro 在原到期日基础上加天数，不覆盖。返回新到期时间。 */
async function grantPro(env, userId, days, source, nowS, uuid) {
  const cur = await env.DB.prepare(
    "SELECT MAX(expires_at) e FROM entitlements WHERE user_id=? AND capability='pro' AND revoked_at IS NULL AND (expires_at IS NULL OR expires_at>?)")
    .bind(userId, nowS()).first();
  const base = Math.max(nowS(), cur?.e || 0);
  const exp = base + days * 86400;
  const caps = ["pro", ...PRO_CAPS];
  const stmts = caps.map(c => env.DB.prepare(
    "INSERT INTO entitlements(id,user_id,capability,source,starts_at,expires_at,meta,created_at) VALUES(?,?,?,?,?,?,?,?)")
    .bind(uuid(), userId, c, source, nowS(), exp, "{}", nowS()));
  await env.DB.batch(stmts);
  return exp;
}
async function membershipView(env, userId, H) {
  const nowS = H.nowS;
  const pro = await env.DB.prepare(
    "SELECT MAX(expires_at) e FROM entitlements WHERE user_id=? AND capability='pro' AND revoked_at IS NULL AND (expires_at IS NULL OR expires_at>?)")
    .bind(userId, nowS()).first();
  const caps = await env.DB.prepare(
    "SELECT DISTINCT capability FROM entitlements WHERE user_id=? AND revoked_at IS NULL AND (expires_at IS NULL OR expires_at>?)")
    .bind(userId, nowS()).all();
  const month = new Date().toISOString().slice(0, 7);
  const used = await env.DB.prepare("SELECT COALESCE(SUM(minutes),0) m FROM usage_ledger WHERE user_id=? AND month=?")
    .bind(userId, month).first();
  const adj = await env.DB.prepare("SELECT COALESCE(SUM(minutes),0) m FROM quota_adjustments WHERE user_id=? AND month=?")
    .bind(userId, month).first();
  const limit = FREE_MONTHLY_MINUTES + (adj?.m || 0);
  return {
    plan: pro?.e ? "pro" : "free",
    pro_until: pro?.e || null,
    capabilities: caps.results.map(c => c.capability),
    usage: { month, used_minutes: Math.round((used?.m || 0) * 10) / 10, free_limit_minutes: limit, remaining: Math.max(0, Math.round((limit - (used?.m || 0)) * 10) / 10) },
  };
}

// ---------- 管理台页面（无管理员凭据内嵌；凭据由管理员每次登录输入） ----------
const CONSOLE_HTML = `<!doctype html><html lang="zh"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>声声不息 管理台</title><style>
body{font-family:system-ui;margin:0;background:#f6f4fb;color:#222}h1{font-size:18px;padding:12px 20px;background:#5b3ba8;color:#fff;margin:0}
#app{max-width:1100px;margin:0 auto;padding:16px}nav button{margin:0 6px 12px 0;padding:6px 14px;border:1px solid #5b3ba8;background:#fff;border-radius:6px;cursor:pointer}
nav button.on{background:#5b3ba8;color:#fff}table{border-collapse:collapse;width:100%;font-size:13px;background:#fff}td,th{border:1px solid #e0d8f0;padding:6px 8px;text-align:left}
input,button{padding:6px 10px;font-size:13px}input{border:1px solid #bbb;border-radius:4px}button{cursor:pointer;border-radius:4px;border:1px solid #999;background:#fff}
button.pri{background:#5b3ba8;color:#fff;border-color:#5b3ba8}.card{background:#fff;border:1px solid #e0d8f0;border-radius:8px;padding:14px;margin-bottom:14px}
.err{color:#b00020}.ok{color:#187a2f}code{background:#eee;padding:1px 4px;border-radius:3px;font-size:12px}pre{white-space:pre-wrap;word-break:break-all;max-height:300px;overflow:auto}
</style></head><body><h1>声声不息 管理台</h1><div id="app">
<div id="login" class="card"><b>管理员登录</b><p><input id="tok" type="password" placeholder="管理员令牌" style="width:300px">
<button class="pri" onclick="login()">登录</button></p><div id="lerr" class="err"></div></div>
<div id="main" style="display:none"><nav>
<button onclick="tab('overview',this)">总览</button><button onclick="tab('users',this)">用户</button>
<button onclick="tab('members',this)">会员权益</button><button onclick="tab('usage',this)">用量</button>
<button onclick="tab('codes',this)">兑换码</button><button onclick="tab('aioff',this)">官方AI</button><button onclick="tab('audit',this)">审计</button>
<button onclick="logout()">退出</button></nav><div id="panel">加载中…</div></div></div>
<script>
let csrf="";const $=id=>document.getElementById(id);
const api=async(p,m,b)=>{const r=await fetch("/console/api"+p,{method:m||"GET",headers:{"content-type":"application/json","x-csrf":csrf},body:b?JSON.stringify(b):undefined});
if(r.status===401){sessionExpired();throw 0}const j=await r.json().catch(()=>({}));if(!r.ok)throw new Error(j.error||r.status);return j};
// 会话失效/越权：立即清掉已渲染的敏感数据与 csrf，强制回登录页
function sessionExpired(){csrf="";lastCodes=[];const p=$("panel");if(p)p.innerHTML="";const n=$("newcodes");if(n)n.style.display="none";const bc=$("bcodes");if(bc)bc.textContent="";const ct=$("codetable");if(ct)ct.textContent="";$("lerr").textContent="会话已失效，请重新登录";showLogin()}
function showLogin(){$("login").style.display="";$("main").style.display="none"}
async function login(){try{const r=await fetch("/console/api/session",{method:"POST",headers:{"content-type":"application/json"},body:JSON.stringify({token:$("tok").value})});
if(!r.ok){sessionExpired();$("lerr").textContent="令牌错误";return}const j=await r.json();csrf=j.csrf;$("lerr").textContent="";$("login").style.display="none";$("main").style.display="";tab("overview")}catch(e){$("lerr").textContent=e.message}}
async function logout(){await api("/session","DELETE").catch(()=>{});csrf="";showLogin()}
function tab(name,btn){[...document.querySelectorAll("nav button")].forEach(b=>b.classList.remove("on"));if(btn)btn.classList.add("on");({overview,users,members,usage,codes,audit,aioff})[name]()}
const ts=t=>t?new Date(t*1000).toLocaleString():"—";
async function overview(){const j=await api("/overview");$("panel").innerHTML=\`<div class="card">用户 <b>\${j.users}</b> · 在期 Pro <b>\${j.pro}</b> · 可兑码 <b>\${j.codes_active}</b> · 已兑 <b>\${j.codes_redeemed}</b></div>\`}
const CAP_CN={pro:"Pro 会员",local_asr_minutes:"本机转写时长",cross_meeting_tracking:"跨会议跟踪",advanced_project_analysis:"高级项目分析",agent_workflow:"Agent 工作流"};
const STATUS_CN={active:"正常",disabled:"已停用",invited:"待激活"};
const entText=e=>{const m=/^([^→]+)→?(\\d+)?$/.exec(e);if(!m)return e;const n=CAP_CN[m[1]]||m[1];return n+(m[2]?" · 至 "+new Date(m[2]*1000).toLocaleDateString():" · 不限期")};
async function users(){const j=await api("/users");
$("panel").innerHTML=\`<div class="card"><table><tr><th>用户名</th><th>状态</th><th>权益</th><th>注册</th><th></th></tr>\${j.users.map(u=>\`<tr><td>\${u.username}</td><td>\${STATUS_CN[u.status]||u.status}</td><td>\${(u.entitlements||[]).map(e=>u.status=="disabled"?'<span style="color:#999">'+entText(e)+"（随停用不生效）</span>":entText(e)).join("<br>")||"—"}</td><td>\${ts(u.created_at)}</td><td>\${u.status=="disabled"?'<button onclick="enableUser(\\''+u.id+'\\')">启用</button>':'<button onclick="disableUser(\\''+u.id+'\\')">停用</button>'}<button onclick="resetPw(\\''+u.id+'\\',\\''+u.username+'\\')">重置密码</button><button onclick="delUser(\\''+u.id+'\\',\\''+u.username+'\\')">删除</button></td></tr>\`).join("")}</table>
<p><input id="nu" placeholder="用户名"><input id="np" type="password" placeholder="密码"><button class="pri" onclick="createUser()">创建账号</button></p><div id="umsg"></div></div>\`}
async function createUser(){try{const j=await api("/users","POST",{username:$("nu").value,password:$("np").value});$("umsg").innerHTML='<span class="ok">已创建 '+j.username+'</span>';users()}catch(e){$("umsg").innerHTML='<span class="err">'+e.message+'</span>'}}
async function disableUser(id){if(!confirm("停用该账号并撤销其全部会话？"))return;await api("/users/"+id,"PATCH",{disabled:true});users()}
async function resetPw(id,name){const p=prompt("为用户 "+name+" 设置新密码（8-128 位）：");if(p==null)return;if(p.length<8||p.length>128)return alert("密码需 8-128 位");await api("/users/"+id+"/reset_password","POST",{password:p});alert("已重置，请把新密码告知用户")}
async function delUser(id,name){if(!confirm("彻底删除用户 "+name+" 及其会话/权益/点数记录？不可恢复！"))return;await api("/users/"+id,"DELETE");users()}
async function enableUser(id){await api("/users/"+id,"PATCH",{disabled:false});users()}
async function members(){const j=await api("/memberships");
$("panel").innerHTML=\`<div class="card"><table><tr><th>用户</th><th>能力</th><th>来源</th><th>到期</th><th>状态</th><th></th></tr>\${j.entitlements.map(e=>\`<tr><td>\${e.username||e.user_id.slice(0,8)}</td><td>\${e.capability}</td><td>\${e.source}</td><td>\${e.expires_at?ts(e.expires_at):"不限期"}</td><td>\${e.revoked_at?"已撤销":""}</td><td>\${e.revoked_at?"":'<button onclick="revokeEnt(\\''+e.id+'\\')">撤销</button>'}</td></tr>\`).join("")}</table>
<p><input id="mu" placeholder="用户名"><input id="md" placeholder="天数" value="30" style="width:60px"><button class="pri" onclick="grantPro()">授予 Pro</button></p><div id="mmsg"></div></div>\`}
async function grantPro(){try{const j=await api("/memberships","POST",{username:$("mu").value,capability:"pro",days:Number($("md").value)});$("mmsg").innerHTML='<span class="ok">已授予，到期 '+ts(j.pro_until)+'</span>';members()}catch(e){$("mmsg").innerHTML='<span class="err">'+e.message+'</span>'}}
async function revokeEnt(id){if(!confirm("撤销该权益？"))return;await api("/memberships/"+id,"DELETE");members()}
async function usage(){const j=await api("/usage");
$("panel").innerHTML=\`<div class="card">账期 <b>\${j.month}</b> · Free 额度 \${j.limit} 分钟<table><tr><th>用户</th><th>已用(分钟)</th></tr>\${j.usage.map(u=>\`<tr><td>\${u.username||u.user_id.slice(0,8)}</td><td>\${u.minutes}</td></tr>\`).join("")}</table>
<p><input id="au" placeholder="用户ID"><input id="am" placeholder="调整分钟(+/-)" style="width:110px"><input id="ar" placeholder="原因"><button class="pri" onclick="adjust()">调整额度</button></p><div id="amsg"></div></div>\`}
async function adjust(){try{const j=await api("/usage/adjustments","POST",{user_id:$("au").value,month:new Date().toISOString().slice(0,7),minutes:Number($("am").value),reason:$("ar").value});$("amsg").innerHTML='<span class="ok">已调整</span>';usage()}catch(e){$("amsg").innerHTML='<span class="err">'+e.message+'</span>'}}
async function codes(){const j=await api("/codes/batches");
$("panel").innerHTML=\`<div class="card"><table><tr><th>批次</th><th>天数</th><th>状态</th><th>数量</th><th></th></tr>\${j.batches.map(c=>\`<tr><td>\${c.name}</td><td>\${c.grant_days}</td><td>\${c.status}</td><td>\${JSON.stringify(c.counts)}</td><td>\${c.status=="draft"?'<button onclick="actBatch(\\''+c.id+'\\')">启用</button>':""}\${c.status=="active"?'<button onclick="disBatch(\\''+c.id+'\\')">停用</button>':""}<button onclick="viewCodes(\\''+c.id+'\\',\\''+c.name+'\\')">码表</button></td></tr>\`).join("")}</table>
<p><input id="bn" placeholder="批次名"><input id="bc" placeholder="数量" value="100" style="width:70px"><input id="bd" placeholder="天数" value="30" style="width:70px"><button class="pri" onclick="mkBatch()">生成兑换码</button></p>
<div id="newcodes" style="display:none"><p class="err"><b>明文码仅此一次显示，立即导出：</b><button onclick="dlCodes()">下载 .txt</button><button onclick="cpCodes()">复制</button></p><pre id="bcodes"></pre></div><pre id="codetable"></pre></div>\`}
let lastCodes=[];
async function mkBatch(){try{const j=await api("/codes/batches","POST",{name:$("bn").value,count:Number($("bc").value),grant_days:Number($("bd").value)});lastCodes=j.codes;$("newcodes").style.display="";$("bcodes").textContent=j.codes.join("\\n")}catch(e){alert(e.message)}}
function dlCodes(){const a=document.createElement("a");a.href=URL.createObjectURL(new Blob([lastCodes.join("\\n")],{type:"text/plain"}));a.download="redeem-codes.txt";a.click()}
function cpCodes(){navigator.clipboard.writeText(lastCodes.join("\\n"))}
async function viewCodes(id,name){const j=await api("/codes/batches/"+id+"/codes");$("codetable").textContent="批次 "+name+" 码状态（服务端只存哈希，明文不可回看）：\\n"+j.codes.map(c=>c.id.slice(0,8)+"  "+c.status+(c.redeemed_by?"  <- "+c.redeemed_by+" @ "+ts(c.redeemed_at):"")).join("\\n")}
async function actBatch(id){await api("/codes/batches/"+id+"/activate","POST",{});codes()}
async function disBatch(id){if(!confirm("停用批次：未核销码全部作废？"))return;await api("/codes/batches/"+id+"/disable","POST",{});codes()}
async function audit(){const j=await api("/audit");
$("panel").innerHTML='<div class="card"><table><tr><th>时间</th><th>用户</th><th>事件</th><th>详情</th></tr>'+j.audit.map(a=>\`<tr><td>\${ts(a.at)}</td><td>\${a.username||""}</td><td>\${a.event}</td><td>\${a.detail||""}</td></tr>\`).join("")+"</table></div>"}
// ---------- R6.2 官方 AI ----------
const esc=s=>String(s??"").replace(/[&<>"]/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;"}[c]));
async function aioff(){const j=await api("/ai/providers");const rr=await api("/ai/rules");const r=rr.rules;const u=await api("/ai/usage");
$("panel").innerHTML='<div class="card"><b>运营控制</b> · 官方 AI：<b>'+(r.enabled?"已启用":"已停用")+'</b>'+(r.paused?' <span class="err">[已暂停]</span>':"")+(rr.key_configured?"":' <span class="err">AI_KEY_SECRET 未配置，密钥将无法保存</span>')
+'<button class="pri" onclick="aiRules({enabled:'+!r.enabled+'})">'+(r.enabled?"停用服务":"启用服务")+'</button> '
+'<button onclick="aiRules({paused:'+!r.paused+'})">'+(r.paused?"恢复运行":"一键暂停")+'</button></div>'
+'<div class="card"><b>服务商</b><table><tr><th>名称</th><th>API Base</th><th>模型</th><th>密钥</th><th>状态</th><th>操作</th></tr>'
+j.providers.map(p=>'<tr><td>'+esc(p.name)+(p.is_default?" ★默认":"")+'</td><td>'+esc(p.api_base)+'</td><td>'+esc(p.model)+'</td><td>'+(p.has_key?"已加密存储":"未配置")+'</td><td>'+p.status+'</td><td>'
+'<button onclick="aiTest(\\''+p.id+'\\',this)">测试</button><button onclick="aiDefault(\\''+p.id+'\\')">设默认</button><button onclick="aiToggle(\\''+p.id+'\\')">'+(p.status=="active"?"停用":"启用")+'</button><button onclick="aiDel(\\''+p.id+'\\')">删</button></td></tr>').join("")
+'</table><p><input id="pn" placeholder="名称"><input id="pb" placeholder="API Base (https://…)"><input id="pm2" placeholder="模型 ID"><input id="pk" type="password" placeholder="API Key（只写不读）"><button class="pri" onclick="aiAdd()">新增</button><span id="pmsg"></span></p></div>'
+'<div class="card"><b>点数规则</b><br>Free 每月 <input id="rf" value="'+r.free_monthly_points+'" style="width:70px"> · Pro 每周期 <input id="rp" value="'+r.pro_period_points+'" style="width:70px"> · 单任务上限 <input id="rc" value="'+r.task_point_cap+'" style="width:60px">'
+' · 输入/输出 <input id="ri" value="'+r.max_input_tokens+'" style="width:80px">/<input id="ro" value="'+r.max_output_tokens+'" style="width:70px"> · 每日任务 F/P <input id="rfd" value="'+r.free_daily_tasks+'" style="width:50px">/<input id="rpd" value="'+r.pro_daily_tasks+'" style="width:50px">'
+' · 自动整理 周期/日 <input id="rap" value="'+r.auto_period_points+'" style="width:60px">/<input id="rad" value="'+r.auto_daily_points+'" style="width:50px"> · 预算毫分 日/月 <input id="rbd" value="'+r.budget_daily_milli+'" style="width:90px">/<input id="rbm" value="'+r.budget_monthly_milli+'" style="width:90px">'
+'<br>允许域名（空格分隔；服务商 API Base 须命中其一，SSRF 白名单） <input id="rah" value="'+(r.allowed_hosts||[]).join(' ')+'" style="width:100%">'
+'<button class="pri" onclick="aiSaveRules()">保存</button><span id="rmsg"></span></div>'
+'<div class="card"><b>用量与成本</b> · 账号 <b>'+u.users+'</b> · 用过官方AI <b>'+u.ai_users+'</b> · 成功 <b>'+u.success+'</b> · 失败 <b>'+u.failed+'</b> · 入/出 Token <b>'+u.input_tokens+'</b>/<b>'+u.output_tokens+'</b> · 总扣点 <b>'+u.points+'</b> · 成本(毫分) <b>'+u.cost_milli+'</b>'
+'<table><tr><th>用户</th><th>任务数</th><th>扣点</th><th>成本</th></tr>'+u.top_users.map(x=>'<tr><td>'+esc(x.username||x.user_id.slice(0,8))+'</td><td>'+x.n+'</td><td>'+x.p+'</td><td>'+x.c+'</td></tr>').join("")+'</table></div>'
+'<div class="card"><b>用户点数</b><div id="aiusers">加载中…</div>'
+'<p><input id="aju" placeholder="用户ID"><input id="ajp" placeholder="点数(+/-)" style="width:80px"><input id="ajr" placeholder="原因"><button class="pri" onclick="aiAdjust()">调整</button><span id="amsg"></span></p></div>';
const w=await api("/ai/users");const el=$("aiusers");if(el)el.innerHTML='<table><tr><th>用户</th><th>计划</th><th>额度</th><th>已用</th><th>预留</th><th>剩余</th><th>周期止</th></tr>'+w.periods.map(p=>'<tr><td>'+esc(p.username||p.user_id.slice(0,8))+'</td><td>'+p.plan+'</td><td>'+p.granted+'</td><td>'+p.used+'</td><td>'+p.reserved+'</td><td>'+p.remaining+'</td><td>'+ts(p.period_end)+'</td></tr>').join("")+"</table>"}
async function aiRules(patch){await api("/ai/rules","PATCH",patch);aioff()}
async function aiSaveRules(){try{const hosts=$("rah").value.split(/[\\s,]+/).filter(Boolean);await api("/ai/rules","PATCH",{free_monthly_points:+$("rf").value,pro_period_points:+$("rp").value,task_point_cap:+$("rc").value,max_input_tokens:+$("ri").value,max_output_tokens:+$("ro").value,free_daily_tasks:+$("rfd").value,pro_daily_tasks:+$("rpd").value,auto_period_points:+$("rap").value,auto_daily_points:+$("rad").value,budget_daily_milli:+$("rbd").value,budget_monthly_milli:+$("rbm").value,...(hosts.length?{allowed_hosts:hosts}:{})});$("rmsg").innerHTML='<span class="ok">已保存</span>'}catch(e){$("rmsg").innerHTML='<span class="err">'+e.message+'</span>'}}
async function aiAdd(){try{await api("/ai/providers","POST",{name:$("pn").value,api_base:$("pb").value,model:$("pm2").value,api_key:$("pk").value||undefined});aioff()}catch(e){$("pmsg").innerHTML='<span class="err">'+e.message+'</span>'}}
async function aiTest(id,btn){btn.disabled=true;try{const j=await api("/ai/providers/"+id+"/test","POST",{});alert(j.ok?"连接成功 "+j.ms+"ms":"失败："+j.error)}finally{btn.disabled=false}}
async function aiDefault(id){await api("/ai/providers/"+id+"/default","POST",{});aioff()}
async function aiToggle(id){await api("/ai/providers/"+id+"/toggle","POST",{});aioff()}
async function aiDel(id){if(!confirm("停用该服务商？"))return;await api("/ai/providers/"+id,"DELETE");aioff()}
async function aiAdjust(){try{await api("/ai/users/adjust","POST",{user_id:$("aju").value,points:+$("ajp").value,reason:$("ajr").value});$("amsg").innerHTML='<span class="ok">已调整</span>';aioff()}catch(e){$("amsg").innerHTML='<span class="err">'+e.message+'</span>'}}
<\/script></body></html>`;
