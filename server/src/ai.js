// R6.2 官方 AI 网关与点数体系：用户端 /ai/official/* + 管理台 /console/api/ai/*
// 约定：密钥 AES-GCM 加密存 D1（主密钥 = Worker Secret AI_KEY_SECRET）；点数是资源额度非人民币；
// 账本不存转写原文；预留/结算/释放全部走数据库原子操作，不依赖 Worker 实例内存。

const TASK_TYPES = new Set(["digest", "auto_digest", "ask", "analysis", "manual_todo",
  "review", "diary", "topic", "skill", "idea", "meeting", "generic"]);
// 产出必须是可解析 JSON 的任务类（契约校验）；ask/generic 允许自由文本。
const JSON_TASKS = new Set(["digest", "auto_digest", "manual_todo", "review", "diary", "topic", "analysis", "idea", "meeting", "skill"]);
const DEFAULT_HOSTS = ["api.deepseek.com", "api.siliconflow.cn", "api.moonshot.cn", "open.bigmodel.cn",
  "ark.cn-beijing.volces.com", "api.minimax.cn", "qianfan.baidubce.com", "tokenhub.tencentmaas.com",
  "api.openai.com", "*.maas.aliyuncs.com"];

const DEFAULT_SETTINGS = {
  enabled: false, paused: false,
  free_monthly_points: 100, pro_period_points: 1000,
  max_input_tokens: 32000, max_output_tokens: 4000, task_point_cap: 100,
  free_daily_tasks: 0, pro_daily_tasks: 0, // 仅按点数使用；管理员可另设每日次数
  auto_period_points: 300, auto_daily_points: 40,
  budget_daily_milli: 0, budget_monthly_milli: 0, // 0 = 不限
  allowed_hosts: DEFAULT_HOSTS,
};

const encAi = new TextEncoder();
const b64buf = (b) => btoa(String.fromCharCode(...new Uint8Array(b)));
const ub64buf = (s) => Uint8Array.from(atob(s.replaceAll("-", "+").replaceAll("_", "/")), (c) => c.charCodeAt(0)).buffer;
const points = (tin, tout) => Math.max(1, Math.ceil(tin / 1000 + tout / 250));
const estTokens = (chars) => Math.ceil(chars / 2.2); // 保守高估：中英混排约 1.6~2.6 字符/token
const RETRY_ONCE = Symbol("retry-once");
// 上游偶发返回 markdown 围栏或夹带解释文字的 JSON；客户端本就剥围栏再解析，服务端提取首个完整 JSON 值，
// 否则合约校验会把真实可用输出误判成 TASK_FAILED:contract_json。
function extractJson(content) {
  const s = content.trim().replace(/^```(?:json)?\s*/i, "").replace(/\s*```$/, "").trim();
  try { JSON.parse(s); return s; } catch { /* 继续提取 */ }
  const start = s.search(/[{[]/);
  if (start < 0) return null;
  const close = s[start] === "{" ? "}" : "]";
  const end = s.lastIndexOf(close);
  if (end <= start) return null;
  const cand = s.slice(start, end + 1);
  try { JSON.parse(cand); return cand; } catch { return null; }
}
// 流式读取上游：健康但慢的生成按「闲置 30s」判生死，不再被固定超时被误杀；
// 真断流/挂死的连接 30s 内出局。把 SSE 增量重组成非流式 JSON，供既有校验逻辑复用。
const UPSTREAM_IDLE_MS = 30_000;
async function readUpstreamText(resp) {
  const ct = (resp.headers.get("content-type") || "").toLowerCase();
  if (!ct.includes("event-stream") || !resp.body) return resp.text();
  const reader = resp.body.getReader();
  const dec = new TextDecoder();
  let buf = "", content = "", finishReason = null, usage = null;
  let idleTimer;
  const idle = () => new Promise((_, rej) => {
    clearTimeout(idleTimer);
    idleTimer = setTimeout(() => rej(new DOMException("idle", "AbortError")), UPSTREAM_IDLE_MS);
  });
  try {
    for (;;) {
      const { value, done } = await Promise.race([reader.read(), idle()]);
      if (done) break;
      buf += dec.decode(value, { stream: true });
      let nl;
      while ((nl = buf.search(/\r?\n\r?\n/)) >= 0) {
        const block = buf.slice(0, nl);
        buf = buf.slice(nl).replace(/^\r?\n\r?\n/, "");
        for (const line of block.split(/\r?\n/)) {
          if (!line.startsWith("data:")) continue;
          const data = line.slice(5).trim();
          if (data === "[DONE]") continue;
          try {
            const o = JSON.parse(data);
            const c = o?.choices?.[0];
            if (c?.delta?.content) content += c.delta.content;
            if (c?.finish_reason) finishReason = c.finish_reason;
            if (o?.usage) usage = o.usage;
          } catch { /* 坏块跳过，等下一块 */ }
        }
      }
    }
  } finally { clearTimeout(idleTimer); try { reader.releaseLock(); } catch {} }
  return JSON.stringify({ choices: [{ message: { content }, finish_reason: finishReason }], usage: usage || {} });
}
const dayStart = (t) => Math.floor(t / 86400) * 86400;
const monthStart = (t) => { const d = new Date(t * 1000); return Math.floor(Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), 1) / 1000); };
const monthEnd = (t) => { const d = new Date(t * 1000); return Math.floor(Date.UTC(d.getUTCFullYear(), d.getUTCMonth() + 1, 1) / 1000); };

async function settings(env) {
  const row = await env.DB.prepare("SELECT value FROM ai_settings WHERE key='official'").first();
  let v = {};
  try { v = row ? JSON.parse(row.value) : {}; } catch { /* 损坏回默认 */ }
  return { ...DEFAULT_SETTINGS, ...v, allowed_hosts: v.allowed_hosts || DEFAULT_HOSTS };
}
async function saveSettings(env, patch) {
  const cur = await settings(env);
  const next = { ...cur, ...patch };
  await env.DB.prepare("INSERT OR REPLACE INTO ai_settings(key,value) VALUES('official',?)").bind(JSON.stringify(next)).run();
  return next;
}

// ---------- 服务商密钥（AES-GCM；主密钥由 Worker Secret 注入，永不入 Git/明文返回） ----------
async function secretKey(env) {
  if (!env.AI_KEY_SECRET) throw new Error("AI_KEY_SECRET not configured");
  const h = await crypto.subtle.digest("SHA-256", encAi.encode(env.AI_KEY_SECRET));
  return crypto.subtle.importKey("raw", h, { name: "AES-GCM" }, false, ["encrypt", "decrypt"]);
}
async function encKey(env, plain) {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const ct = await crypto.subtle.encrypt({ name: "AES-GCM", iv }, await secretKey(env), encAi.encode(plain));
  const buf = new Uint8Array(12 + ct.byteLength); buf.set(iv); buf.set(new Uint8Array(ct), 12);
  return b64buf(buf);
}
async function decKey(env, packed) {
  if (!packed) return "";
  const b = new Uint8Array(ub64buf(packed));
  const pt = await crypto.subtle.decrypt({ name: "AES-GCM", iv: b.slice(0, 12) }, await secretKey(env), b.slice(12));
  return new TextDecoder().decode(pt);
}

// ---------- 会员期判定（复用 entitlements，pro 链式顺延取最早 starts_at 为锚） ----------
async function proUntil(env, userId, t) {
  const r = await env.DB.prepare(
    "SELECT MAX(expires_at) e FROM entitlements WHERE user_id=? AND capability='pro' AND revoked_at IS NULL AND (expires_at IS NULL OR expires_at>?)")
    .bind(userId, t).first();
  return r?.e == null ? null : (r.e === null ? Infinity : r.e);
}
async function proAnchor(env, userId, t) {
  const r = await env.DB.prepare(
    "SELECT MIN(starts_at) s FROM entitlements WHERE user_id=? AND capability='pro' AND revoked_at IS NULL AND (expires_at IS NULL OR expires_at>?)")
    .bind(userId, t).first();
  return r?.s ?? t;
}

/**
 * 懒发放当期点数期：
 *  - Pro 有效 → 30 天滚动周期（年付 365 天自然落成 11×30 + 35 = 12 期；续费顺延）
 *  - 否则 → UTC 自然月（每月 1 日重置）
 * period id 确定性（user:plan:start）→ 并发/重复激活天然幂等，同周期不重复发点。
 */
async function ensurePeriod(env, userId, t, cfg) {
  const pUntil = await proUntil(env, userId, t);
  if (pUntil != null && pUntil > t) {
    const current = await env.DB.prepare(
      "SELECT * FROM ai_quota_periods WHERE user_id=? AND plan='pro' AND period_start<=? AND period_end>? ORDER BY period_start DESC").bind(userId, t, t).first();
    if (current) return current;
    const last = await env.DB.prepare(
      "SELECT * FROM ai_quota_periods WHERE user_id=? AND plan='pro' ORDER BY period_end DESC").bind(userId).first();
    const anchor = last ? last.period_end : await proAnchor(env, userId, t);
    const k = Math.max(0, Math.floor((t - anchor) / (30 * 86400)));
    const start = anchor + k * 30 * 86400;
    let end = Math.min(start + 30 * 86400, pUntil === Infinity ? start + 30 * 86400 : pUntil);
    // 不足 30 天的残尾并入本期（年付 365 = 11×30 + 35，第 12 期 35 天而非 30+5）
    if (pUntil !== Infinity && pUntil - end > 0 && pUntil - end < 30 * 86400) end = pUntil;
    const id = `${userId}:pro:${start}`;
    const r = await env.DB.prepare(
      "INSERT OR IGNORE INTO ai_quota_periods(id,user_id,plan,period_start,period_end,granted,created_at) VALUES(?,?,?,?,?,?,?)")
      .bind(id, userId, "pro", start, end, cfg.pro_period_points, t).run();
    if (r.meta.changes) await ledger(env, { user_id: userId, period_id: id, kind: "grant", delta: cfg.pro_period_points, actor: "system" });
    return env.DB.prepare("SELECT * FROM ai_quota_periods WHERE id=?").bind(id).first();
  }
  const start = monthStart(t), end = monthEnd(t), id = `${userId}:free:${start}`;
  const r = await env.DB.prepare(
    "INSERT OR IGNORE INTO ai_quota_periods(id,user_id,plan,period_start,period_end,granted,created_at) VALUES(?,?,?,?,?,?,?)")
    .bind(id, userId, "free", start, end, cfg.free_monthly_points, t).run();
  if (r.meta.changes) await ledger(env, { user_id: userId, period_id: id, kind: "grant", delta: cfg.free_monthly_points, actor: "system" });
  return env.DB.prepare("SELECT * FROM ai_quota_periods WHERE id=?").bind(id).first();
}

async function periodAdjust(env, periodId) {
  const r = await env.DB.prepare("SELECT COALESCE(SUM(points),0) p FROM ai_point_adjustments WHERE period_id=?").bind(periodId).first();
  return r?.p || 0;
}
async function balance(env, period) {
  const adj = await periodAdjust(env, period.id);
  const total = period.granted + adj;
  return { plan: period.plan, period_start: period.period_start, period_end: period.period_end,
    granted: total, used: period.used, reserved: period.reserved, remaining: Math.max(0, total - period.used - period.reserved) };
}
async function ledger(env, row) {
  await env.DB.prepare("INSERT INTO ai_usage_ledger(id,user_id,period_id,request_id,kind,delta,actor,at) VALUES(?,?,?,?,?,?,?,?)")
    .bind(crypto.randomUUID(), row.user_id, row.period_id || null, row.request_id || null, row.kind, row.delta, row.actor || null, Math.floor(Date.now() / 1000)).run();
}

function hostAllowed(base, hosts) {
  let u;
  try { u = new URL(base); } catch { return false; }
  if (u.protocol !== "https:") return false;
  return hosts.some(h => h.startsWith("*.") ? (u.hostname === h.slice(2) || u.hostname.endsWith(h.slice(1))) : u.hostname === h);
}

export async function handleAi(req, env, H) {
  const url = new URL(req.url);
  const path = url.pathname;
  const { json, err, timingSafeEq, uuid, nowS, sha256b64, audit, authed } = H;
  const isUser = path.startsWith("/ai/official/");
  const isAdmin = path.startsWith("/console/api/ai/");
  if (!isUser && !isAdmin) return null;

  // ---------- 管理台会话（与 membership.js 同一套 cookie+csrf 约定） ----------
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
  async function admin() {
    const s = await adminSession();
    if (!s) return null;
    if (req.method !== "GET" && req.headers.get("x-csrf") !== s.csrf) return "csrf";
    return s;
  }

  // ===================== 用户端 =====================
  if (isUser) {
    const a = await authed(env, req);
    if (!a) return err("unauthorized", 401);
    const uid = a.user.id;
    const t = nowS();
    const cfg = await settings(env);
    const consent = await env.DB.prepare("SELECT * FROM ai_consents WHERE user_id=?").bind(uid).first();
    const quotaView = async () => balance(env, await ensurePeriod(env, uid, t, cfg));
    const providerRow = async () => (await env.DB.prepare(
      "SELECT * FROM ai_provider_configs WHERE status='active' ORDER BY is_default DESC, created_at LIMIT 1").first());

    if (req.method === "GET" && path === "/ai/official/status") {
      const p = await providerRow();
      const b = await quotaView();
      return json({ enabled: cfg.enabled && !cfg.paused, configured: !!p, consented: !!consent,
        plan: b.plan, remaining: b.remaining });
    }
    if (req.method === "POST" && path === "/ai/official/consent") {
      const b = await req.json().catch(() => ({}));
      await env.DB.prepare("INSERT OR REPLACE INTO ai_consents(user_id,version,at) VALUES(?,?,?)")
        .bind(uid, Number(b.version) || 1, t).run();
      await audit(env, uid, "ai.consent", `v${b.version || 1}`, req);
      return json({ ok: true });
    }
    if (req.method === "GET" && path === "/ai/official/quota") {
      const b = await quotaView();
      const p = await providerRow();
      const autoUsed = await env.DB.prepare(
        "SELECT COALESCE(SUM(points),0) p FROM ai_usage_requests WHERE user_id=? AND task_type='auto_digest' AND status='settled' AND created_at>=?")
        .bind(uid, b.period_start).first();
      const autoToday = await env.DB.prepare(
        "SELECT COALESCE(SUM(points),0) p FROM ai_usage_requests WHERE user_id=? AND task_type='auto_digest' AND status='settled' AND created_at>=?")
        .bind(uid, dayStart(t)).first();
      return json({ enabled: cfg.enabled && !cfg.paused, configured: !!p, ...b,
        refresh_at: b.period_end,
        auto: { period_used: autoUsed?.p || 0, daily_used: autoToday?.p || 0,
          period_cap: cfg.auto_period_points, daily_cap: cfg.auto_daily_points, allowed: b.plan === "pro" } });
    }
    if (req.method === "GET" && path === "/ai/official/usage") {
      const limit = Math.min(Number(url.searchParams.get("limit")) || 20, 100);
      const r = await env.DB.prepare(
        "SELECT task_type,points,status,created_at,settled_at FROM ai_usage_requests WHERE user_id=? ORDER BY created_at DESC LIMIT ?")
        .bind(uid, limit).all();
      return json({ items: r.results.map(x => ({ task_type: x.task_type, points: x.points, status: x.status, at: x.settled_at || x.created_at })) });
    }
    if (req.method === "POST" && path === "/ai/official/estimate") {
      const b = await req.json().catch(() => ({}));
      const chars = Math.max(0, Number(b.chars) || 0);
      const tin = Math.min(estTokens(chars), cfg.max_input_tokens);
      const lo = points(tin, Math.min(500, cfg.max_output_tokens));
      const hi = Math.min(cfg.task_point_cap, points(tin, cfg.max_output_tokens));
      return json({ estimate: [lo, hi], remaining: (await quotaView()).remaining });
    }

    if (req.method === "POST" && path === "/ai/official/generate") {
      const b = await req.json().catch(() => ({}));
      if (!consent) return err("CONSENT_REQUIRED", 403);
      if (!cfg.enabled || cfg.paused) return err("PROVIDER_UNAVAILABLE", 503);
      const task = String(b.task_type || "");
      if (!TASK_TYPES.has(task)) return err("unknown task_type");
      const system = String(b.system || ""), userText = String(b.user || "");
      if (!userText) return err("user required");
      const reqKey = String(b.request_id || "");
      if (!reqKey || reqKey.length > 120) return err("request_id required");

      // 幂等回放：同 request_id 直接返回已存结果，不重扣
      const dup = await env.DB.prepare("SELECT * FROM ai_usage_requests WHERE user_id=? AND request_key=?").bind(uid, reqKey).first();
      if (dup && dup.status === "settled") {
        const pv = await quotaView();
        return json({ request_id: reqKey, content: dup.result || "", points: dup.points, duplicated: true, balance: pv });
      }
      // reserved 锁龄超过上游调用上限（55s×2 + 余量）即视为僵尸：Worker 部署/被杀
      // 会留下永不释放的 reserved，不回收则该 request_id 永久 409、点数永久占压。
      const STALE_RESERVED_S = 240;
      if (dup && dup.status === "reserved" && t - Number(dup.created_at) <= STALE_RESERVED_S)
        return err("request in flight", 409);
      // 超时可能已在上游执行，不能以同一凭据立刻再次发起。
      // 但上游调用有 85s 总时限，超龄（>300s）的 UNKNOWN_PROVIDER_OUTCOME 必定已死透：
      // 永久封禁只会让该批内容永远无法重试整理。代价最多是多付一次上游成本，用户点不重复扣。
      if (dup?.error_code?.startsWith("UNKNOWN_PROVIDER_OUTCOME") &&
          t - Number(dup.created_at) <= 300) return err("UNKNOWN_PROVIDER_OUTCOME", 504);

      const inChars = system.length + userText.length;
      const tin = estTokens(inChars);
      if (tin > cfg.max_input_tokens) return err("REQUEST_TOO_LARGE", 413);
      const maxOut = Math.min(Number(b.max_output_tokens) || cfg.max_output_tokens, cfg.max_output_tokens);
      const reservePts = Math.min(cfg.task_point_cap, points(tin, maxOut));

      const provider = await providerRow();
      if (!provider) return err("PROVIDER_UNAVAILABLE", 503);

      const period = await ensurePeriod(env, uid, t, cfg);

      // 僵尸 reserved 回收：先带状态守卫原子抢占（并发重试只有一个能改状态），
      // 赢的那个才归还占用预留；随后统一走 status IN ('failed','rejected') 的重取。
      if (dup && dup.status === "reserved") {
        const claim = await env.DB.prepare(
          "UPDATE ai_usage_requests SET status='failed',error_code='STALE_RESERVED' WHERE id=? AND status='reserved'")
          .bind(dup.id).run();
        if (claim.meta.changes) {
          await env.DB.batch([
            env.DB.prepare("UPDATE ai_quota_periods SET reserved=MAX(0,reserved-?) WHERE id=?").bind(dup.reserved_points, period.id),
            env.DB.prepare("INSERT INTO ai_usage_ledger(id,user_id,period_id,request_id,kind,delta,actor,at) VALUES(?,?,?,?,?,?,?,?)")
              .bind(uuid(), uid, period.id, dup.id, "release", dup.reserved_points, "system", t),
          ]);
        }
      }

      // 每日成功任务上限（Free/Pro 分开）
      const todayCount = await env.DB.prepare(
        "SELECT COUNT(*) n FROM ai_usage_requests WHERE user_id=? AND status='settled' AND created_at>=?").bind(uid, dayStart(t)).first();
      const dailyCap = period.plan === "pro" ? cfg.pro_daily_tasks : cfg.free_daily_tasks;
      // 0 表示仅按点数使用；每日额度与上游短时限流必须区分。
      if (dailyCap > 0 && (todayCount?.n || 0) >= dailyCap) {
        await audit(env, uid, "ai.daily_task_limit", `${period.plan}:${dailyCap}`, req);
        return json({ error: "DAILY_TASK_LIMIT", source: "daily_task_limit", limit: dailyCap,
          used: todayCount.n, reset_at: dayStart(t) + 86400 }, 429);
      }

      // 自动整理独立额度（仅 Pro 可发起；上限另计且计入总池）
      if (task === "auto_digest") {
        if (period.plan !== "pro") return err("QUOTA_EXHAUSTED", 402);
        const ap = await env.DB.prepare(
          "SELECT COALESCE(SUM(points),0) p FROM ai_usage_requests WHERE user_id=? AND task_type='auto_digest' AND status='settled' AND created_at>=?")
          .bind(uid, period.period_start).first();
        const ad = await env.DB.prepare(
          "SELECT COALESCE(SUM(points),0) p FROM ai_usage_requests WHERE user_id=? AND task_type='auto_digest' AND status='settled' AND created_at>=?")
          .bind(uid, dayStart(t)).first();
        if ((ap?.p || 0) + reservePts > cfg.auto_period_points || (ad?.p || 0) + reservePts > cfg.auto_daily_points)
          return err("QUOTA_EXHAUSTED", 402);
      }

      // 预算闸（记录实际成本，超预算停服不扣用户点）
      if (cfg.budget_daily_milli > 0) {
        const c = await env.DB.prepare("SELECT COALESCE(SUM(cost_milli),0) c FROM ai_usage_requests WHERE created_at>=?").bind(dayStart(t)).first();
        if ((c?.c || 0) >= cfg.budget_daily_milli) return err("PROVIDER_UNAVAILABLE", 503);
      }
      if (cfg.budget_monthly_milli > 0) {
        const c = await env.DB.prepare("SELECT COALESCE(SUM(cost_milli),0) c FROM ai_usage_requests WHERE created_at>=?").bind(monthStart(t)).first();
        if ((c?.c || 0) >= cfg.budget_monthly_milli) return err("PROVIDER_UNAVAILABLE", 503);
      }

      // 原子预留：条件 UPDATE 保证不超扣；0 行即额度不足
      const adj = await periodAdjust(env, period.id);
      const reqId = dup?.id || uuid();
      // 已明确失败/拒绝的任务可以恢复；比较状态抢占，只有一个调用能预留和访问上游。
      const ins = dup ? await env.DB.prepare(
        "UPDATE ai_usage_requests SET status='reserved',error_code=NULL,provider_id=?,model=?,reserved_points=?,created_at=? WHERE id=? AND status IN ('failed','rejected')")
        .bind(provider.id, provider.model, reservePts, t, reqId).run()
        : await env.DB.prepare(
          "INSERT OR IGNORE INTO ai_usage_requests(id,user_id,request_key,task_type,status,provider_id,model,reserved_points,created_at) VALUES(?,?,?,?,?,?,?,?,?)")
          .bind(reqId, uid, reqKey, task, "reserved", provider.id, provider.model, reservePts, t).run();
      if (!ins.meta.changes) return err("request in flight", 409);
      const rs = await env.DB.prepare(
        "UPDATE ai_quota_periods SET reserved=reserved+? WHERE id=? AND ? - used - reserved >= ?")
        .bind(reservePts, period.id, period.granted + adj, reservePts).run();
      if (!rs.meta.changes) {
        await env.DB.prepare("UPDATE ai_usage_requests SET status='rejected',error_code='QUOTA_EXHAUSTED' WHERE id=?").bind(reqId).run();
        return err("QUOTA_EXHAUSTED", 402);
      }

      // 调服务商（OpenAI 兼容 chat/completions；密钥服务端解密，不出 Worker）
      // 上游偶发返回空 content（推理耗尽 max_tokens 等）：同请求重试一次，不重复预留/扣点。
      // 总时限 85s 预算：App longReadClient callTimeout=100s，客户端断开会让 isolate 被杀、
      // 留下永不释放的 reserved 僵尸锁。服务端必须抢在客户端超时前给出定论。
      const CALL_DEADLINE_MS = 85_000;
      const callStarted = Date.now();
      const finish = async (okCall) => {
        const key = provider.api_key_enc ? await decKey(env, provider.api_key_enc) : "";
        for (let attempt = 0; attempt < 2; attempt++) {
          const remaining = CALL_DEADLINE_MS - (Date.now() - callStarted);
          if (remaining < 15_000) { await release("TASK_FAILED", "deadline"); return err("TASK_FAILED", 502); }
          const ctl = new AbortController();
          // 流式后单次尝试上限 75s：活流几乎给满 85s 预算（实测成功 batch 需 ~55s），
          // 死流由闲置 30s 检测提前出局、剩余预算再战；超时烧完才干净失败。
          const timer = setTimeout(() => ctl.abort(), Math.min(75_000, remaining));
          try {
            const resp = await fetch(provider.api_base.replace(/\/+$/, "") + "/chat/completions", {
              method: "POST",
              headers: { "content-type": "application/json", ...(key ? { authorization: "Bearer " + key } : {}) },
              body: JSON.stringify({ model: provider.model, stream: true,
                // 推理型上游 max_tokens 计入思考量，按答案上限给会 length 截断 JSON
                // （线上实测 contract_json）。max_tokens 只是上限不是目标，直接发满
                // provider 额度一次解决：思考+正文都由模型自行分配，点数按实际 usage 结算。
                max_tokens: provider.max_output_tokens,
                messages: [{ role: "system", content: system }, { role: "user", content: userText }] }),
              signal: ctl.signal });
            const text = await readUpstreamText(resp);
            const out = await okCall(resp, text, attempt);
            if (out !== RETRY_ONCE) return out;
          } catch (e) {
            // 上游超时：预算内重试一次（同请求身份，不重复扣用户点；
            // 上游可能已执行，代价最多一次服务商成本——好过整批失败）。
            if (e?.name === "AbortError" && attempt === 0) continue;
            throw e;
          } finally { clearTimeout(timer); }
        }
      };
      let settled = false, released = false;
      const settle = async (pts, tin2, tout2, content, estimated, costMilli) => {
        const pv = await env.DB.batch([
          env.DB.prepare("UPDATE ai_quota_periods SET used=used+?, reserved=reserved-? WHERE id=?").bind(pts, reservePts, period.id),
          env.DB.prepare("UPDATE ai_usage_requests SET status='settled',points=?,input_tokens=?,output_tokens=?,estimated=?,cost_milli=?,result=?,settled_at=? WHERE id=?")
            .bind(pts, tin2, tout2, estimated ? 1 : 0, costMilli || 0, content, nowS(), reqId),
          env.DB.prepare("INSERT INTO ai_usage_ledger(id,user_id,period_id,request_id,kind,delta,actor,at) VALUES(?,?,?,?,?,?,?,?)")
            .bind(uuid(), uid, period.id, reqId, "charge", -pts, "system", nowS()),
        ]);
        settled = true;
        return pv;
      };
      const release = async (code, detail) => {
        // 结算后的余额查询/审计失败不能撤销成功记录；一次失败也只能释放一次。
        if (settled || released) return;
        await env.DB.batch([
          env.DB.prepare("UPDATE ai_quota_periods SET reserved=MAX(0,reserved-?) WHERE id=?").bind(reservePts, period.id),
          env.DB.prepare("UPDATE ai_usage_requests SET status='failed',error_code=? WHERE id=?").bind(`${code}${detail ? ":" + String(detail).slice(0, 60) : ""}`, reqId),
          env.DB.prepare("INSERT INTO ai_usage_ledger(id,user_id,period_id,request_id,kind,delta,actor,at) VALUES(?,?,?,?,?,?,?,?)")
            .bind(uuid(), uid, period.id, reqId, "release", reservePts, "system", nowS()),
        ]);
        released = true;
      };

      try {
        const inFlight = finish(async (resp, text, attempt) => {
          if (resp.status === 429) {
            await release("RATE_LIMITED");
            await audit(env, uid, "ai.provider_rate_limited", provider.id, req);
            return json({ error: "RATE_LIMITED", source: "upstream" }, 429);
          }
          if (!resp.ok) { await release("PROVIDER_UNAVAILABLE", resp.status); await audit(env, uid, "ai.provider_error", `${provider.id}:${resp.status}`, req); return err("PROVIDER_UNAVAILABLE", 503); }
          let obj; try { obj = JSON.parse(text); } catch { await release("TASK_FAILED", "bad_json"); return err("TASK_FAILED", 502); }
          let content = obj?.choices?.[0]?.message?.content;
          if (typeof content !== "string" || !content.trim()) {
            if (attempt === 0) return RETRY_ONCE;
            // finish_reason 诊断：length=推理烧光 max_tokens；stop=上游吐空内容
            await release("TASK_FAILED", "empty:" + String(obj?.choices?.[0]?.finish_reason || "?")); return err("TASK_FAILED", 502);
          }
          if (JSON_TASKS.has(task)) {
            const extracted = extractJson(content);
            if (extracted == null) {
              if (attempt === 0) return RETRY_ONCE;
              await release("TASK_FAILED", "contract_json:" + String(content).slice(0, 36).replace(/\s+/g, " ")); return err("TASK_FAILED", 502);
            }
            content = extracted;
          }
          const u = obj.usage || {};
          let tin2 = u.prompt_tokens, tout2 = u.completion_tokens, estimated = 0;
          if (!(tin2 > 0) || !(tout2 >= 0)) { tin2 = tin; tout2 = estTokens(content.length); estimated = 1; }
          const actual = points(tin2, tout2);
          const charged = Math.min(actual, reservePts); // 实际高于预留时按预留结算，余额不为负
          const cost = costMeta(provider, tin2, tout2);
          await settle(charged, tin2, tout2, content, estimated, cost);
          const pv = await balance(env, await env.DB.prepare("SELECT * FROM ai_quota_periods WHERE id=?").bind(period.id).first());
          return json({ request_id: reqKey, content, points: charged, balance: pv });
        });
        // 客户端断连（App 超时/进程被杀）时，waitUntil 让 isolate 活到把上游调用和
        // settle/release 跑完：不再留 reserved 僵尸锁，App 重试还能命中幂等回放免费拿结果。
        if (H.ctx?.waitUntil) H.ctx.waitUntil(inFlight.catch(() => {}));
        return await inFlight;
      } catch (e) {
        if (e?.name === "AbortError") { await release("UNKNOWN_PROVIDER_OUTCOME", "timeout"); return err("UNKNOWN_PROVIDER_OUTCOME", 504); }
        await release("PROVIDER_UNAVAILABLE", "fetch").catch(() => {});
        return err("PROVIDER_UNAVAILABLE", 503);
      }
    }
    return err("not found", 404);
  }

  // ===================== 管理台 =====================
  const adm = await admin();
  if (adm === "csrf") return err("bad csrf", 403);
  if (!adm) return err("unauthorized", 401);
  const t2 = nowS();

  if (req.method === "GET" && path === "/console/api/ai/providers") {
    const r = await env.DB.prepare("SELECT id,name,api_base,model,protocol,max_input_tokens,max_output_tokens,cost_meta,status,is_default,created_at,updated_at,api_key_enc!='' has_key FROM ai_provider_configs ORDER BY created_at").all();
    return json({ providers: r.results.map(({ api_key_enc, ...x }) => ({ ...x, has_key: x.has_key === 1 || x.has_key === true })) });
  }
  if (req.method === "POST" && path === "/console/api/ai/providers") {
    const b = await req.json().catch(() => ({}));
    const cfg = await settings(env);
    if (!b.name || !b.api_base || !b.model) return err("name/api_base/model required");
    if (!hostAllowed(String(b.api_base), cfg.allowed_hosts)) return err("api_base 不在允许域名内（SSRF 防护；请先在下方「点数规则-允许域名」加入该域名并保存）", 403);
    const id = uuid();
    const keyEnc = b.api_key ? await encKey(env, String(b.api_key)) : "";
    await env.DB.prepare(
      "INSERT INTO ai_provider_configs(id,name,api_base,model,api_key_enc,max_input_tokens,max_output_tokens,cost_meta,status,is_default,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)")
      .bind(id, String(b.name), String(b.api_base).replace(/\/+$/, ""), String(b.model), keyEnc,
        Number(b.max_input_tokens) || 32000, Number(b.max_output_tokens) || 4000,
        JSON.stringify(b.cost_meta || {}), "active", 0, t2, t2).run();
    await audit(env, null, "admin.ai_provider_add", b.name, req);
    return json({ id });
  }
  const pm = path.match(/^\/console\/api\/ai\/providers\/([^/]+)(\/(test|default|toggle))?$/);
  if (pm) {
    const id = pm[1], op = pm[3];
    const row = await env.DB.prepare("SELECT * FROM ai_provider_configs WHERE id=?").bind(id).first();
    if (!row) return err("not found", 404);
    if (req.method === "PATCH" && !op) {
      const b = await req.json().catch(() => ({}));
      const cfg = await settings(env);
      if (b.api_base && !hostAllowed(String(b.api_base), cfg.allowed_hosts)) return err("api_base 不在允许域名内（SSRF 防护；请先在下方「点数规则-允许域名」加入该域名并保存）", 403);
      const keyEnc = b.api_key ? await encKey(env, String(b.api_key)) : row.api_key_enc;
      await env.DB.prepare("UPDATE ai_provider_configs SET name=?,api_base=?,model=?,api_key_enc=?,max_input_tokens=?,max_output_tokens=?,cost_meta=?,updated_at=? WHERE id=?")
        .bind(b.name ?? row.name, (b.api_base ?? row.api_base).replace(/\/+$/, ""), b.model ?? row.model, keyEnc,
          Number(b.max_input_tokens) || row.max_input_tokens, Number(b.max_output_tokens) || row.max_output_tokens,
          b.cost_meta ? JSON.stringify(b.cost_meta) : row.cost_meta, t2, id).run();
      await audit(env, null, "admin.ai_provider_edit", id, req);
      return json({ ok: true });
    }
    if (req.method === "DELETE" && !op) {
      // 真删除：停用/启用由 toggle 负责；删=连密钥配置一起移除（用量记录保留 provider_id 引用不受影响）
      await env.DB.prepare("DELETE FROM ai_provider_configs WHERE id=?").bind(id).run();
      await audit(env, null, "admin.ai_provider_delete", id, req);
      return json({ ok: true });
    }
    if (req.method === "POST" && op === "default") {
      await env.DB.batch([
        env.DB.prepare("UPDATE ai_provider_configs SET is_default=0"),
        env.DB.prepare("UPDATE ai_provider_configs SET is_default=1,status='active',updated_at=? WHERE id=?").bind(t2, id),
      ]);
      await audit(env, null, "admin.ai_provider_default", `${row.name}/${row.model}`, req);
      return json({ ok: true });
    }
    if (req.method === "POST" && op === "toggle") {
      await env.DB.prepare("UPDATE ai_provider_configs SET status=?,updated_at=? WHERE id=?")
        .bind(row.status === "active" ? "disabled" : "active", t2, id).run();
      await audit(env, null, "admin.ai_provider_toggle", id, req);
      return json({ ok: true });
    }
    if (req.method === "POST" && op === "test") {
      const started = Date.now();
      try {
        const key = row.api_key_enc ? await decKey(env, row.api_key_enc) : "";
        const ctl = new AbortController(); const timer = setTimeout(() => ctl.abort(), 20_000);
        const resp = await fetch(row.api_base.replace(/\/+$/, "") + "/chat/completions", {
          method: "POST",
          headers: { "content-type": "application/json", ...(key ? { authorization: "Bearer " + key } : {}) },
          body: JSON.stringify({ model: row.model, max_tokens: 8, messages: [{ role: "user", content: "ping" }] }),
          signal: ctl.signal }).finally(() => clearTimeout(timer));
        const ms = Date.now() - started;
        if (!resp.ok) return json({ ok: false, ms, error: `HTTP ${resp.status}` });
        const obj = await resp.json().catch(() => ({}));
        return json({ ok: true, ms, usage: obj.usage || null });
      } catch (e) {
        return json({ ok: false, ms: Date.now() - started, error: e?.name === "AbortError" ? "timeout" : "connect" });
      }
    }
    return err("not found", 404);
  }

  if (path === "/console/api/ai/rules") {
    if (req.method === "GET") return json({ rules: await settings(env), key_configured: !!env.AI_KEY_SECRET });
    if (req.method === "PATCH") {
      const b = await req.json().catch(() => ({}));
      for (const k of ["free_daily_tasks", "pro_daily_tasks"]) {
        if (b[k] !== undefined && (!Number.isSafeInteger(b[k]) || b[k] < 0)) return err(`${k} must be a non-negative integer`);
      }
      const allow = ["enabled", "paused", "free_monthly_points", "pro_period_points", "max_input_tokens", "max_output_tokens",
        "task_point_cap", "free_daily_tasks", "pro_daily_tasks", "auto_period_points", "auto_daily_points",
        "budget_daily_milli", "budget_monthly_milli", "allowed_hosts"];
      const patch = {};
      for (const k of allow) if (b[k] !== undefined) patch[k] = b[k];
      const next = await saveSettings(env, patch);
      await audit(env, null, "admin.ai_rules", JSON.stringify(patch), req);
      return json({ rules: next });
    }
  }

  if (req.method === "GET" && path === "/console/api/ai/usage") {
    const c = async (sql, ...b) => (await env.DB.prepare(sql).bind(...b).first());
    const since = t2 - 30 * 86400;
    const users = await c("SELECT COUNT(*) n FROM users");
    const aiUsers = await c("SELECT COUNT(DISTINCT user_id) n FROM ai_usage_requests WHERE status='settled'");
    const sums = await c("SELECT COALESCE(SUM(input_tokens),0) i, COALESCE(SUM(output_tokens),0) o, COALESCE(SUM(points),0) p, COALESCE(SUM(cost_milli),0) c FROM ai_usage_requests WHERE status='settled'");
    const okc = await c("SELECT COUNT(*) n FROM ai_usage_requests WHERE status='settled'");
    const failc = await c("SELECT COUNT(*) n FROM ai_usage_requests WHERE status='failed'");
    const byPlan = await env.DB.prepare(
      "SELECT q.plan, COALESCE(SUM(r.points),0) p FROM ai_usage_requests r JOIN ai_quota_periods q ON q.user_id=r.user_id AND r.created_at>=q.period_start AND r.created_at<q.period_end WHERE r.status='settled' GROUP BY q.plan").all();
    const daily = await env.DB.prepare(
      "SELECT created_at/86400 d, COUNT(*) n, SUM(points) p, SUM(input_tokens) i, SUM(output_tokens) o FROM ai_usage_requests WHERE status='settled' AND created_at>=? GROUP BY d ORDER BY d DESC LIMIT 30").bind(since).all();
    const top = await env.DB.prepare(
      "SELECT r.user_id,u.username,COUNT(*) n,SUM(r.points) p,SUM(r.cost_milli) c FROM ai_usage_requests r LEFT JOIN users u ON u.id=r.user_id WHERE r.status='settled' GROUP BY r.user_id ORDER BY p DESC LIMIT 20").all();
    const providers = await env.DB.prepare("SELECT COUNT(*) n FROM ai_provider_configs WHERE status='active'").first();
    return json({ users: users?.n || 0, ai_users: aiUsers?.n || 0, providers: providers?.n || 0,
      success: okc?.n || 0, failed: failc?.n || 0, input_tokens: sums?.i || 0, output_tokens: sums?.o || 0,
      points: sums?.p || 0, cost_milli: sums?.c || 0,
      by_plan: byPlan.results, daily: daily.results.map(d => ({ day: d.d * 86400, tasks: d.n, points: d.p, input: d.i, output: d.o })),
      top_users: top.results });
  }

  if (req.method === "GET" && path === "/console/api/ai/users") {
    const rows = await env.DB.prepare(
      "SELECT q.*, u.username FROM ai_quota_periods q LEFT JOIN users u ON u.id=q.user_id ORDER BY q.period_start DESC LIMIT 300").all();
    const out = [];
    for (const q of rows.results) {
      const adj = await periodAdjust(env, q.id);
      out.push({ user_id: q.user_id, username: q.username, plan: q.plan, period_start: q.period_start, period_end: q.period_end,
        granted: q.granted + adj, used: q.used, reserved: q.reserved, remaining: Math.max(0, q.granted + adj - q.used - q.reserved) });
    }
    return json({ periods: out });
  }
  if (req.method === "POST" && path === "/console/api/ai/users/adjust") {
    const b = await req.json().catch(() => ({}));
    if (!b.user_id || !Number.isInteger(Number(b.points)) || Number(b.points) === 0) return err("user_id/points required");
    const cfg = await settings(env);
    const period = await ensurePeriod(env, String(b.user_id), t2, cfg);
    await env.DB.batch([
      env.DB.prepare("INSERT INTO ai_point_adjustments(id,user_id,period_id,points,reason,actor,at) VALUES(?,?,?,?,?,?,?)")
        .bind(uuid(), b.user_id, period.id, Number(b.points), String(b.reason || ""), "console", t2),
      env.DB.prepare("INSERT INTO ai_usage_ledger(id,user_id,period_id,request_id,kind,delta,actor,at) VALUES(?,?,?,?,?,?,?,?)")
        .bind(uuid(), b.user_id, period.id, null, "adjust", Number(b.points), "console", t2),
    ]);
    await audit(env, b.user_id, "admin.ai_adjust", `${b.points} ${b.reason || ""}`, req);
    return json({ ok: true, period_id: period.id });
  }
  if (req.method === "GET" && path === "/console/api/ai/requests") {
    const uid = url.searchParams.get("user_id") || "";
    const sql = uid
      ? "SELECT id,user_id,request_key,task_type,status,model,reserved_points,points,input_tokens,output_tokens,estimated,error_code,created_at,settled_at FROM ai_usage_requests WHERE user_id=? ORDER BY created_at DESC LIMIT 100"
      : "SELECT id,user_id,request_key,task_type,status,model,reserved_points,points,input_tokens,output_tokens,estimated,error_code,created_at,settled_at FROM ai_usage_requests ORDER BY created_at DESC LIMIT 100";
    const r = await env.DB.prepare(sql).bind(...(uid ? [uid] : [])).all();
    return json({ requests: r.results });
  }

  return err("not found", 404);
}

function costMeta(provider, tin, tout) {
  try {
    const m = JSON.parse(provider.cost_meta || "{}");
    const ip = Number(m.in_per_1k) || 0, op = Number(m.out_per_1k) || 0;
    return Math.round((tin * ip + tout * op)); // cost_meta 以「毫分/千token」记
  } catch { return 0; }
}
