-- R6.2 官方 AI 服务与点数体系（migration 0004）
-- 约定：密钥只存 AES-GCM 密文；账本不存用户转写原文；生成结果仅存 requests 表用于幂等回放。
CREATE TABLE IF NOT EXISTS ai_provider_configs (
  id           TEXT PRIMARY KEY,
  name         TEXT NOT NULL,               -- 后台显示名
  api_base     TEXT NOT NULL,               -- OpenAI 兼容 base（https）
  model        TEXT NOT NULL,
  api_key_enc  TEXT NOT NULL DEFAULT '',    -- AES-GCM(iv||ct) base64，空=未配置
  protocol     TEXT NOT NULL DEFAULT 'openai',
  max_input_tokens  INTEGER NOT NULL DEFAULT 32000,
  max_output_tokens INTEGER NOT NULL DEFAULT 4000,
  cost_meta    TEXT NOT NULL DEFAULT '{}',  -- {in_per_1k,out_per_1k,note} 内部成本参考
  status       TEXT NOT NULL DEFAULT 'active',
  is_default   INTEGER NOT NULL DEFAULT 0,
  created_at   INTEGER NOT NULL,
  updated_at   INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS ai_quota_periods (
  id           TEXT PRIMARY KEY,            -- userId:plan:startEpoch 确定性 → 天然幂等
  user_id      TEXT NOT NULL,
  plan         TEXT NOT NULL,               -- free / pro
  period_start INTEGER NOT NULL,
  period_end   INTEGER NOT NULL,
  granted      INTEGER NOT NULL,
  used         INTEGER NOT NULL DEFAULT 0,
  reserved     INTEGER NOT NULL DEFAULT 0,
  status       TEXT NOT NULL DEFAULT 'active',
  created_at   INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_aiqp_user ON ai_quota_periods(user_id, period_start, period_end);

CREATE TABLE IF NOT EXISTS ai_usage_requests (
  id            TEXT PRIMARY KEY,
  user_id       TEXT NOT NULL,
  request_key   TEXT NOT NULL,              -- 用户作用域幂等键
  task_type     TEXT NOT NULL,
  status        TEXT NOT NULL,              -- reserved / settled / failed / rejected / unknown
  provider_id   TEXT,
  model         TEXT,
  reserved_points INTEGER NOT NULL DEFAULT 0,
  points        INTEGER NOT NULL DEFAULT 0, -- 实际扣点（≤ reserved）
  input_tokens  INTEGER NOT NULL DEFAULT 0,
  output_tokens INTEGER NOT NULL DEFAULT 0,
  estimated     INTEGER NOT NULL DEFAULT 0, -- 用量为估算（服务商未回 usage）
  cost_milli    INTEGER NOT NULL DEFAULT 0, -- 内部成本估计（毫分/千分之一分）
  error_code    TEXT,
  result        TEXT,                       -- 幂等回放用生成内容（非原话；不入账本）
  created_at    INTEGER NOT NULL,
  settled_at    INTEGER,
  UNIQUE(user_id, request_key)
);
CREATE INDEX IF NOT EXISTS idx_aiur_user_day ON ai_usage_requests(user_id, created_at);

CREATE TABLE IF NOT EXISTS ai_usage_ledger (
  id         TEXT PRIMARY KEY,
  user_id    TEXT NOT NULL,
  period_id  TEXT,
  request_id TEXT,
  kind       TEXT NOT NULL,                 -- grant / charge / release / adjust
  delta      INTEGER NOT NULL,
  actor      TEXT,
  at         INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_aiul_user ON ai_usage_ledger(user_id, at);

CREATE TABLE IF NOT EXISTS ai_point_adjustments (
  id        TEXT PRIMARY KEY,
  user_id   TEXT NOT NULL,
  period_id TEXT NOT NULL,
  points    INTEGER NOT NULL,               -- 可正可负
  reason    TEXT,
  actor     TEXT NOT NULL,
  at        INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_aipa_period ON ai_point_adjustments(period_id);

CREATE TABLE IF NOT EXISTS ai_consents (
  user_id TEXT PRIMARY KEY,
  version INTEGER NOT NULL,
  at      INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS ai_settings (
  key   TEXT PRIMARY KEY,
  value TEXT NOT NULL
);
