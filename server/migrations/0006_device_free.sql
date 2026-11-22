-- 一机一免费：设备指纹首次触达即记录，同指纹再注册免费账号被拒。
-- device_fp 为客户端 ANDROID_ID 的 SHA256，重装 App 不变（恢复出厂才变），
-- 登录与注册都会记录指纹；只有注册被拦截——登录自己的账号永远放行。
CREATE TABLE IF NOT EXISTS device_free(
  device_fp TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  first_seen_at INTEGER NOT NULL
);
