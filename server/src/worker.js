// soundsound-auth — Cloudflare Workers + D1
// 账号/密码认证 + 会话管理 + ECDSA 签名离线授权凭证 + 审计。
// 不保存音频/转写/记忆/用户 AI Key；只保存账号、会话、授权与审计。
import { handleMembership } from "./membership.js";
import { handleAi } from "./ai.js";

const CAPS_DEFAULT = ["RECORD_AUDIO", "READ_PERSONAL_DATA", "AI_ANALYSIS", "EXPORT_BACKUP", "AGENT_ACCESS", "MCP_ACCESS"];
const PBKDF2_ITER = 100000;

// ---------- 小工具 ----------
const enc = new TextEncoder();
const b64 = (buf) => btoa(String.fromCharCode(...new Uint8Array(buf)));
const b64u = (buf) => b64(buf).replaceAll("+", "-").replaceAll("/", "_").replaceAll("=", "");
const ub64 = (s) => Uint8Array.from(atob(s.replaceAll("-", "+").replaceAll("_", "/")), (c) => c.charCodeAt(0)).buffer;
const uuid = () => crypto.randomUUID();
const nowS = () => Math.floor(Date.now() / 1000);

function json(obj, status = 200) {
  return new Response(JSON.stringify(obj), { status, headers: { "content-type": "application/json; charset=utf-8" } });
}
const err = (msg, status = 400) => json({ error: msg }, status);

function timingSafeEq(a, b) {
  const x = enc.encode(a), y = enc.encode(b);
  if (x.length !== y.length) return false;
  let d = 0;
  for (let i = 0; i < x.length; i++) d |= x[i] ^ y[i];
  return d === 0;
}

// ---------- 密码（PBKDF2-SHA256） ----------
async function hashPassword(pw) {
  const salt = crypto.getRandomValues(new Uint8Array(16));
  const key = await crypto.subtle.importKey("raw", enc.encode(pw), "PBKDF2", false, ["deriveBits"]);
  const bits = await crypto.subtle.deriveBits({ name: "PBKDF2", salt, iterations: PBKDF2_ITER, hash: "SHA-256" }, key, 256);
  return `pbkdf2:${PBKDF2_ITER}:${b64(salt)}:${b64(bits)}`;
}
async function verifyPassword(pw, stored) {
  const parts = stored.split(":");
  if (parts.length !== 4 || parts[0] !== "pbkdf2") return false;
  const key = await crypto.subtle.importKey("raw", enc.encode(pw), "PBKDF2", false, ["deriveBits"]);
  const bits = await crypto.subtle.deriveBits(
    { name: "PBKDF2", salt: new Uint8Array(ub64(parts[2])), iterations: Number(parts[1]), hash: "SHA-256" }, key, 256);
  return timingSafeEq(b64(bits), parts[3]);
}

// ---------- 签名密钥（ECDSA P-256） ----------
let _licKey = null;
async function licenseKey(env) {
  if (_licKey) return _licKey;
  _licKey = await crypto.subtle.importKey("pkcs8", ub64(env.LICENSE_PRIVATE_KEY),
    { name: "ECDSA", namedCurve: "P-256" }, false, ["sign"]);
  return _licKey;
}
// WebCrypto ECDSA 输出 IEEE-P1363 裸签名（r||s，64 字节）；Java 端验签要 DER。
function p1363ToDer(sig) {
  const b = new Uint8Array(sig);
  if (b.length !== 64) return b; // 非 P-256 长度原样返回，验签端自会拒绝
  const int = (x) => {
    let i = 0;
    while (i < x.length - 1 && x[i] === 0 && !(x[i + 1] & 0x80)) i++;
    const v = x.slice(i);
    return v[0] & 0x80 ? Uint8Array.from([0, ...v]) : v;
  };
  const rd = int(b.slice(0, 32)), sd = int(b.slice(32, 64));
  return Uint8Array.from([0x30, 4 + rd.length + sd.length, 0x02, rd.length, ...rd, 0x02, sd.length, ...sd]);
}
async function signLicense(env, payloadObj) {
  const payload = JSON.stringify(payloadObj);
  const key = await licenseKey(env);
  const sig = await crypto.subtle.sign({ name: "ECDSA", hash: "SHA-256" }, key, enc.encode(payload));
  return { payload, signature: b64(p1363ToDer(sig)) };
}

// ---------- Access Token：ES256 JWT，jti=session.id ----------
async function jwtSign(env, claims) {
  const head = b64u(enc.encode(JSON.stringify({ alg: "ES256", typ: "JWT" })));
  const body = b64u(enc.encode(JSON.stringify(claims)));
  const key = await licenseKey(env);
  const sig = await crypto.subtle.sign({ name: "ECDSA", hash: "SHA-256" }, key, enc.encode(`${head}.${body}`));
  return `${head}.${body}.${b64u(sig)}`;
}
let _licPub = null;
async function licensePubKey(env) {
  if (_licPub) return _licPub;
  if (!env.LICENSE_PUBLIC_JWK) throw new Error("LICENSE_PUBLIC_JWK not set");
  _licPub = await crypto.subtle.importKey("jwk", JSON.parse(env.LICENSE_PUBLIC_JWK),
    { name: "ECDSA", namedCurve: "P-256" }, false, ["verify"]);
  return _licPub;
}
async function jwtVerifyWithPublic(env, token) {
  const p = token.split(".");
  if (p.length !== 3) return null;
  try {
    const ok = await crypto.subtle.verify({ name: "ECDSA", hash: "SHA-256" },
      await licensePubKey(env), ub64(p[2]), enc.encode(`${p[0]}.${p[1]}`));
    if (!ok) return null;
    const claims = JSON.parse(new TextDecoder().decode(ub64(p[1])));
    if (claims.exp && claims.exp < nowS()) return null;
    return claims;
  } catch { return null; }
}

// ---------- 会话/授权签发 ----------
// 离线凭证能力 = 账号静态 capabilities ∪ entitlements 有效权益（含 pro）。
// 这样 Pro 授予/兑换后，下一次签发的离线凭证立刻带 pro，客户端可离线判定。
async function effectiveCaps(env, user) {
  const base = JSON.parse(user.capabilities || "[]");
  try {
    const r = await env.DB.prepare(
      "SELECT DISTINCT capability FROM entitlements WHERE user_id=? AND revoked_at IS NULL AND (expires_at IS NULL OR expires_at>?)")
      .bind(user.id, nowS()).all();
    return [...new Set([...base, ...r.results.map(x => x.capability)])];
  } catch { return base; } // entitlements 表未迁移时退化为静态能力
}
// 凭证内携带真实 Pro 到期：客户端离线也要按会员到期结束权益，
// 不能让 7 天离线凭证把已到期的 Pro 续命。expires_at IS NULL = 不限期 → -1 哨兵。
async function effectiveProUntil(env, user) {
  try {
    const r = await env.DB.prepare(
      "SELECT expires_at FROM entitlements WHERE user_id=? AND capability='pro' AND revoked_at IS NULL AND (expires_at IS NULL OR expires_at>?)")
      .bind(user.id, nowS()).all();
    if (!r.results.length) return null;
    if (r.results.some(x => x.expires_at == null)) return -1;
    return Math.max(...r.results.map(x => x.expires_at));
  } catch { return null; }
}
async function issueSession(env, user, deviceId, deviceName) {
  const sid = uuid();
  const refresh = b64u(crypto.getRandomValues(new Uint8Array(32)));
  const refreshHash = await sha256b64(refresh);
  const exp = nowS() + Number(env.REFRESH_TTL_SECONDS);
  await env.DB.prepare(
    "INSERT INTO sessions(id,user_id,device_id,device_name,refresh_hash,created_at,expires_at,last_seen_at) VALUES(?,?,?,?,?,?,?,?)")
    .bind(sid, user.id, deviceId, deviceName || null, refreshHash, nowS(), exp, nowS()).run();
  const lic = await signLicense(env, {
    v: 1, sub: user.id, name: user.username, dev: deviceId,
    caps: await effectiveCaps(env, user), pro_until: await effectiveProUntil(env, user),
    iat: nowS(), exp: nowS() + Number(env.OFFLINE_LICENSE_DAYS) * 86400,
  });
  await env.DB.prepare("INSERT INTO licenses(id,user_id,device_id,payload,signature,issued_at,expires_at) VALUES(?,?,?,?,?,?,?)")
    .bind(uuid(), user.id, deviceId, lic.payload, lic.signature, nowS(), nowS() + Number(env.OFFLINE_LICENSE_DAYS) * 86400).run();
  const access = await jwtSign(env, { sub: user.id, jti: sid, iat: nowS(), exp: nowS() + Number(env.ACCESS_TTL_SECONDS) });
  return { access, refresh, license: lic, sessionId: sid };
}
async function sha256b64(s) {
  return b64(await crypto.subtle.digest("SHA-256", enc.encode(s)));
}
async function audit(env, userId, event, detail, req) {
  await env.DB.prepare("INSERT INTO audit_log(user_id,event,detail,ip,at) VALUES(?,?,?,?,?)")
    .bind(userId, event, detail || null, req.headers.get("CF-Connecting-IP") || "", nowS()).run();
}
async function authed(env, req) {
  const h = req.headers.get("Authorization") || "";
  if (!h.startsWith("Bearer ")) return null;
  const claims = await jwtVerifyWithPublic(env, h.slice(7));
  if (!claims) return null;
  const sess = await env.DB.prepare("SELECT * FROM sessions WHERE id=? AND revoked_at IS NULL").bind(claims.jti).first();
  if (!sess) return null;
  const user = await env.DB.prepare("SELECT * FROM users WHERE id=?").bind(claims.sub).first();
  if (!user || user.status !== "active") return null;
  return { user, session: sess };
}
async function rateLimit(env, key, limit, window) {
  const row = await env.DB.prepare("SELECT * FROM login_attempts WHERE key=?").bind(key).first();
  const t = nowS();
  if (!row || row.reset_at < t) {
    await env.DB.prepare("INSERT OR REPLACE INTO login_attempts(key,count,reset_at) VALUES(?,1,?)").bind(key, t + window).run();
    return { blocked: false, remaining: limit - 1 };
  }
  const n = row.count + 1;
  await env.DB.prepare("UPDATE login_attempts SET count=? WHERE key=?").bind(n, key).run();
  return { blocked: n > limit, remaining: Math.max(0, limit - n) };
}

// ---------- 路由 ----------
export default {
  async fetch(req, env) {
    const url = new URL(req.url);
    const path = url.pathname;
    try {
      // ---- R6.2：官方 AI 网关与点数（/ai/official/* 与 /console/api/ai/*；先匹配，/console/api/* 会被会员模块 404 必须先走）----
      const ar = await handleAi(req, env,
        { json, err, timingSafeEq, uuid, nowS, sha256b64, hashPassword, audit, authed, CAPS_DEFAULT });
      if (ar) return ar;
      // ---- R4.3：管理台页面/会员/用量/兑换码（先匹配，不命中再走原认证路由）----
      const mr = await handleMembership(req, env,
        { json, err, timingSafeEq, uuid, nowS, sha256b64, hashPassword, audit, authed, CAPS_DEFAULT });
      if (mr) return mr;
      if (req.method === "GET" && path === "/") return Response.redirect(new URL("/console", req.url), 302);

      // ---- 应用更新（公开只读）：manifest 按通道下发，安装包文件 302 到真实托管地址 ----
      if (req.method === "GET" && (path === "/release/update.json" || path === "/release/preview.json")) {
        const ch = path.endsWith("preview.json") ? "preview" : "stable";
        const row = await env.DB.prepare("SELECT manifest FROM app_release WHERE channel=?").bind(ch).first();
        if (!row) return err("no release", 404);
        return new Response(row.manifest, {
          headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-cache" } });
      }
      if (req.method === "GET" && path.startsWith("/release/")) {
        const name = decodeURIComponent(path.slice("/release/".length));
        if (!name || name.length > 180 || name.includes("..") || name.includes("/")) return err("not found", 404);
        const rows = await env.DB.prepare("SELECT files FROM app_release").all();
        for (const r of rows.results) {
          const files = JSON.parse(r.files || "{}");
          if (files[name]) return Response.redirect(files[name], 302);
        }
        return err("not found", 404);
      }

      // ---- 管理端（ADMIN_TOKEN）----
      if (path.startsWith("/admin/")) {
        const tok = req.headers.get("Authorization") || "";
        if (!tok.startsWith("Bearer ") || !timingSafeEq(tok.slice(7), env.ADMIN_TOKEN)) return err("unauthorized", 401);
        if (req.method === "POST" && path === "/admin/accounts") {
          const b = await req.json();
          if (!b.username || !b.password) return err("username/password required");
          const id = uuid();
          const caps = b.capabilities || CAPS_DEFAULT;
          await env.DB.prepare(
            "INSERT INTO users(id,username,pass_hash,status,capabilities,activation_code,created_at) VALUES(?,?,?,?,?,?,?)")
            .bind(id, b.username, await hashPassword(b.password),
              b.activation_code ? "invited" : "active", JSON.stringify(caps), b.activation_code || null, nowS()).run();
          await audit(env, id, "admin.create", b.username, req);
          return json({ id, username: b.username, status: b.activation_code ? "invited" : "active", capabilities: caps });
        }
        if (req.method === "POST" && path === "/admin/accounts/status") {
          const b = await req.json();
          const st = b.disabled ? "disabled" : "active";
          const r = await env.DB.prepare("UPDATE users SET status=?, disabled_at=? WHERE username=? OR id=?")
            .bind(st, b.disabled ? nowS() : null, b.username || "", b.id || "").run();
          if (b.disabled) await env.DB.prepare("UPDATE sessions SET revoked_at=? WHERE user_id=(SELECT id FROM users WHERE username=? OR id=?) AND revoked_at IS NULL")
            .bind(nowS(), b.username || "", b.id || "").run();
          await audit(env, b.id || b.username, b.disabled ? "admin.disable" : "admin.enable", "", req);
          return json({ changed: r.meta.changes });
        }
        if (req.method === "GET" && path === "/admin/accounts") {
          const r = await env.DB.prepare("SELECT id,username,status,capabilities,created_at,disabled_at FROM users").all();
          return json({ accounts: r.results });
        }
        // 发布应用更新：{channel:'stable'|'preview', manifest:{versionCode,versionName,notes,apk:{file,sha256,size},patches:[…]}, files:{文件名:下载地址}}
        if (req.method === "POST" && path === "/admin/release") {
          const b = await req.json();
          const ch = b.channel === "preview" ? "preview" : "stable";
          const m = b.manifest;
          if (!m || !Number.isInteger(m.versionCode) || typeof m.versionName !== "string" || !m.apk) return err("manifest required");
          await env.DB.prepare("INSERT OR REPLACE INTO app_release(channel,manifest,files,updated_at) VALUES(?,?,?,?)")
            .bind(ch, JSON.stringify(m), JSON.stringify(b.files || {}), nowS()).run();
          await audit(env, null, "admin.release", `${ch}:${m.versionName}`, req);
          return json({ ok: true, channel: ch, version: m.versionName });
        }
        return err("not found", 404);
      }

      // ---- 登录 ----
      if (req.method === "POST" && path === "/auth/login") {
        const b = await req.json();
        if (!b.username || !b.password || !b.device_id) return err("username/password/device_id required");
        const ip = req.headers.get("CF-Connecting-IP") || "";
        const rl = await rateLimit(env, `${b.username}|${ip}`, Number(env.LOGIN_RATE_LIMIT), Number(env.LOGIN_RATE_WINDOW_SECONDS));
        if (rl.blocked) { await audit(env, null, "login.rate_limited", b.username, req); return err("too many attempts", 429); }
        const user = await env.DB.prepare("SELECT * FROM users WHERE username=?").bind(b.username).first();
        const okPw = user ? await verifyPassword(b.password, user.pass_hash) : false;
        if (!user || !okPw) { await audit(env, user?.id || null, "login.fail", b.username, req); return err("invalid credentials", 401); }
        if (user.status === "disabled") { await audit(env, user.id, "login.disabled", "", req); return err("account disabled", 403); }
        if (user.status === "invited") {
          if (!b.activation_code || !timingSafeEq(b.activation_code, user.activation_code || "")) {
            await audit(env, user.id, "login.need_activation", "", req); return err("activation code required", 403);
          }
          await env.DB.prepare("UPDATE users SET status='active', activation_code=NULL WHERE id=?").bind(user.id).run();
          user.status = "active";
        }
        const issued = await issueSession(env, user, b.device_id, b.device_name);
        await env.DB.prepare("DELETE FROM login_attempts WHERE key=?").bind(`${b.username}|${ip}`).run();
        await audit(env, user.id, "login.ok", b.device_id, req);
        return json({
          account: { id: user.id, username: user.username, capabilities: await effectiveCaps(env, user) },
          access_token: issued.access, refresh_token: issued.refresh,
          license: issued.license, offline_days: Number(env.OFFLINE_LICENSE_DAYS),
        });
      }

      // ---- 刷新（旋转 refresh token）----
      if (req.method === "POST" && path === "/auth/refresh") {
        const b = await req.json();
        if (!b.refresh_token) return err("refresh_token required");
        const rh = await sha256b64(b.refresh_token);
        const sess = await env.DB.prepare("SELECT * FROM sessions WHERE refresh_hash=? AND revoked_at IS NULL").bind(rh).first();
        if (!sess || sess.expires_at < nowS()) return err("invalid refresh token", 401);
        const user = await env.DB.prepare("SELECT * FROM users WHERE id=? AND status='active'").bind(sess.user_id).first();
        if (!user) return err("account disabled", 403);
        const refresh = b64u(crypto.getRandomValues(new Uint8Array(32)));
        const exp = nowS() + Number(env.REFRESH_TTL_SECONDS);
        await env.DB.prepare("UPDATE sessions SET refresh_hash=?, expires_at=?, last_seen_at=? WHERE id=?")
          .bind(await sha256b64(refresh), exp, nowS(), sess.id).run();
        const lic = await signLicense(env, {
          v: 1, sub: user.id, name: user.username, dev: sess.device_id,
          caps: await effectiveCaps(env, user), pro_until: await effectiveProUntil(env, user),
          iat: nowS(), exp: nowS() + Number(env.OFFLINE_LICENSE_DAYS) * 86400,
        });
        await env.DB.prepare("INSERT INTO licenses(id,user_id,device_id,payload,signature,issued_at,expires_at) VALUES(?,?,?,?,?,?,?)")
          .bind(uuid(), user.id, sess.device_id, lic.payload, lic.signature, nowS(), nowS() + Number(env.OFFLINE_LICENSE_DAYS) * 86400).run();
        const access = await jwtSign(env, { sub: user.id, jti: sess.id, iat: nowS(), exp: nowS() + Number(env.ACCESS_TTL_SECONDS) });
        await audit(env, user.id, "refresh.ok", sess.device_id, req);
        return json({ access_token: access, refresh_token: refresh, license: lic,
          account: { id: user.id, username: user.username, capabilities: await effectiveCaps(env, user) } });
      }

      // ---- 登出 ----
      if (req.method === "POST" && path === "/auth/logout") {
        const b = await req.json().catch(() => ({}));
        if (b.refresh_token) {
          await env.DB.prepare("UPDATE sessions SET revoked_at=? WHERE refresh_hash=?")
            .bind(nowS(), await sha256b64(b.refresh_token)).run();
        }
        return json({ ok: true });
      }

      // ---- 授权状态 ----
      if (req.method === "GET" && path === "/auth/status") {
        const a = await authed(env, req);
        if (!a) return err("unauthorized", 401);
        return json({ account: { id: a.user.id, username: a.user.username, status: a.user.status,
          capabilities: await effectiveCaps(env, a.user) }, session: { id: a.session.id, device_id: a.session.device_id } });
      }

      // ---- 设备会话列表/撤销 ----
      if (req.method === "GET" && path === "/auth/devices") {
        const a = await authed(env, req);
        if (!a) return err("unauthorized", 401);
        const r = await env.DB.prepare(
          "SELECT id,device_id,device_name,created_at,last_seen_at,revoked_at FROM sessions WHERE user_id=? ORDER BY last_seen_at DESC")
          .bind(a.user.id).all();
        return json({ sessions: r.results });
      }
      if (req.method === "POST" && path === "/auth/devices/revoke") {
        const a = await authed(env, req);
        if (!a) return err("unauthorized", 401);
        const b = await req.json();
        await env.DB.prepare("UPDATE sessions SET revoked_at=? WHERE id=? AND user_id=?")
          .bind(nowS(), b.session_id, a.user.id).run();
        await env.DB.prepare("UPDATE licenses SET revoked_at=? WHERE user_id=? AND device_id=(SELECT device_id FROM sessions WHERE id=?)")
          .bind(nowS(), a.user.id, b.session_id).run();
        await audit(env, a.user.id, "session.revoke", b.session_id, req);
        return json({ ok: true });
      }

      return err("not found", 404);
    } catch (e) {
      return err(`internal: ${e.message}`, 500);
    }
  },
};
