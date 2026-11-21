-- R6.2b：自助注册 + refresh 轮换宽限
-- 刷新响应丢失时，客户端握着的旧 refresh 会被轮换作废→下次刷新 401→误登出。
-- prev_refresh_hash/prev_valid_until：旧票在轮换后宽限期内仍可换一次（重放安全），
-- 拿到的新票仍是新的轮换结果；宽限外照旧拒绝。
ALTER TABLE sessions ADD COLUMN prev_refresh_hash TEXT;
ALTER TABLE sessions ADD COLUMN prev_valid_until INTEGER;
