-- 0002 membership：会员/权益/用量/兑换码/管理台会话（增量，不动现有 5 张认证表）
CREATE TABLE IF NOT EXISTS plans (
  id          TEXT PRIMARY KEY,
  name        TEXT NOT NULL,
  price_cents INTEGER NOT NULL DEFAULT 0,
  config      TEXT NOT NULL DEFAULT '{}'       -- JSON：权益与限制
);

-- 权益按能力逐项存，不用 is_pro 布尔；expires_at NULL=不限期
CREATE TABLE IF NOT EXISTS entitlements (
  id          TEXT PRIMARY KEY,
  user_id     TEXT NOT NULL REFERENCES users(id),
  capability  TEXT NOT NULL,                   -- pro / local_asr_minutes / cross_meeting_tracking / advanced_project_analysis / agent_workflow
  source      TEXT NOT NULL,                   -- plan:free / redeem:<code_id> / admin
  starts_at   INTEGER NOT NULL,
  expires_at  INTEGER,
  revoked_at  INTEGER,
  meta        TEXT NOT NULL DEFAULT '{}',
  created_at  INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_ent_user ON entitlements(user_id, capability, revoked_at, expires_at);

-- 用量账本：成功转写分钟；id 为客户端幂等键（segment 级去重）
CREATE TABLE IF NOT EXISTS usage_ledger (
  id        TEXT NOT NULL,
  user_id   TEXT NOT NULL,
  device_id TEXT,
  minutes   REAL NOT NULL,
  month     TEXT NOT NULL,                     -- YYYY-MM（账期口径与客户端一致）
  at        INTEGER NOT NULL,
  PRIMARY KEY (user_id, id)
);
CREATE INDEX IF NOT EXISTS idx_usage_month ON usage_ledger(user_id, month);

CREATE TABLE IF NOT EXISTS quota_adjustments (
  id       TEXT PRIMARY KEY,
  user_id  TEXT NOT NULL,
  month    TEXT NOT NULL,
  minutes  REAL NOT NULL,
  reason   TEXT,
  actor    TEXT,
  at       INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS redeem_campaigns (
  id           TEXT PRIMARY KEY,
  name         TEXT NOT NULL,
  grant_days   INTEGER NOT NULL,
  capability   TEXT NOT NULL DEFAULT 'pro',
  status       TEXT NOT NULL DEFAULT 'draft',  -- draft/active/disabled
  created_at   INTEGER NOT NULL,
  activated_at INTEGER,
  disabled_at  INTEGER
);

-- 兑换码只存哈希，明文仅在创建响应中返回一次
CREATE TABLE IF NOT EXISTS redeem_codes (
  id           TEXT PRIMARY KEY,
  campaign_id  TEXT NOT NULL REFERENCES redeem_campaigns(id),
  code_hash    TEXT NOT NULL UNIQUE,
  status       TEXT NOT NULL DEFAULT 'draft',  -- draft/active/redeemed/revoked
  redeemed_by  TEXT,
  redeemed_at  INTEGER,
  created_at   INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_codes_campaign ON redeem_codes(campaign_id, status);

-- request_id UNIQUE = 幂等键：重复请求返回原记录不重复发权益
CREATE TABLE IF NOT EXISTS redeem_records (
  id           TEXT PRIMARY KEY,
  code_id      TEXT NOT NULL,
  campaign_id  TEXT NOT NULL,
  user_id      TEXT NOT NULL,
  request_id   TEXT UNIQUE,
  granted_days INTEGER NOT NULL,
  at           INTEGER NOT NULL
);

-- 管理台会话（HttpOnly Cookie + CSRF，独立于 ADMIN_TOKEN 每次请求携带的方式）
CREATE TABLE IF NOT EXISTS admin_sessions (
  id         TEXT PRIMARY KEY,
  token_hash TEXT NOT NULL UNIQUE,
  csrf       TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL,
  revoked_at INTEGER
);

-- 内置套餐：Free=每月1800分钟成功转写；Pro=本地ASR不限时+高级能力（云端AI用量另计）
INSERT OR IGNORE INTO plans(id,name,price_cents,config) VALUES
  ('free','免费版',0,'{"monthly_asr_minutes":1800}'),
  ('pro','Pro',0,'{"local_asr_minutes":-1,"cross_meeting_tracking":true,"advanced_project_analysis":true,"agent_workflow":true}');
