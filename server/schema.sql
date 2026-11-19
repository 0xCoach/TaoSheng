-- soundsound-auth D1 schema (migration 0001)
CREATE TABLE IF NOT EXISTS users (
  id            TEXT PRIMARY KEY,
  username      TEXT NOT NULL UNIQUE,
  pass_hash     TEXT NOT NULL,            -- pbkdf2:iterations:saltB64:hashB64
  status        TEXT NOT NULL DEFAULT 'active',  -- invited/active/disabled
  capabilities  TEXT NOT NULL DEFAULT '[]',      -- JSON array
  activation_code TEXT,                        -- invited 态首次激活用
  created_at    INTEGER NOT NULL,
  disabled_at   INTEGER
);

CREATE TABLE IF NOT EXISTS sessions (
  id           TEXT PRIMARY KEY,
  user_id      TEXT NOT NULL REFERENCES users(id),
  device_id    TEXT NOT NULL,
  device_name  TEXT,
  refresh_hash TEXT NOT NULL,
  created_at   INTEGER NOT NULL,
  expires_at   INTEGER NOT NULL,
  revoked_at   INTEGER,
  last_seen_at INTEGER
);
CREATE INDEX IF NOT EXISTS idx_sessions_user ON sessions(user_id);
CREATE INDEX IF NOT EXISTS idx_sessions_device ON sessions(user_id, device_id);

CREATE TABLE IF NOT EXISTS licenses (
  id         TEXT PRIMARY KEY,
  user_id    TEXT NOT NULL REFERENCES users(id),
  device_id  TEXT NOT NULL,
  payload    TEXT NOT NULL,      -- 被签名的 JSON（账号/能力/设备/起止时间）
  signature  TEXT NOT NULL,      -- ECDSA P-256 ASN.1 DER base64
  issued_at  INTEGER NOT NULL,
  expires_at INTEGER NOT NULL,
  revoked_at INTEGER
);
CREATE INDEX IF NOT EXISTS idx_licenses_device ON licenses(user_id, device_id);

CREATE TABLE IF NOT EXISTS audit_log (
  id      INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id TEXT,
  event   TEXT NOT NULL,
  detail  TEXT,
  ip      TEXT,
  at      INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS login_attempts (
  key      TEXT PRIMARY KEY,     -- username|ip
  count    INTEGER NOT NULL,
  reset_at INTEGER NOT NULL
);
