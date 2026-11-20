-- 应用更新发布表：channel('stable'|'preview') → manifest 原文 + 文件下载地址表。
-- manifest 即客户端 UpdateManifest.parse 需要的 update.json 全文；
-- files 为 {文件名: 真实下载地址}，/release/<file> 302 跳转过去（APK 可托管在 R2/任意 HTTPS）。
CREATE TABLE IF NOT EXISTS app_release (
  channel    TEXT PRIMARY KEY,
  manifest   TEXT NOT NULL,
  files      TEXT NOT NULL DEFAULT '{}',
  updated_at INTEGER NOT NULL
);
