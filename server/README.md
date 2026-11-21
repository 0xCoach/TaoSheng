# soundsound-auth 服务端（Cloudflare Workers + D1）

账号登录 + 会话管理 + ECDSA 签名离线授权凭证 + 审计。**不保存**音频/转写/记忆/用户 AI Key。

## 部署

```bash
cd server
npx wrangler d1 create soundsound-auth          # 把返回的 database_id 填进 wrangler.toml
npx wrangler d1 execute soundsound-auth --file=schema.sql
# 生成授权签名密钥（ECDSA P-256）：任选其一
openssl ecparam -genkey -name prime256v1 -noout -out lic.pem
openssl pkcs8 -topk8 -nocrypt -in lic.pem -out lic_pkcs8.pem
openssl pkcs8 -topk8 -nocrypt -in lic.pem -outform DER | base64   # → LICENSE_PRIVATE_KEY
# 公钥 JWK（x,y 各取 uncompressed point）：
openssl ec -in lic.pem -pubout -outform DER | tail -c 65 | xxd -p  # 04||X||Y → 拆成 x/y 填 JWK
npx wrangler secret put ADMIN_TOKEN
npx wrangler secret put LICENSE_PRIVATE_KEY    # PKCS8 DER 的 base64
npx wrangler secret put LICENSE_PUBLIC_JWK     # {"kty":"EC","crv":"P-256","x":"...","y":"..."}
npx wrangler deploy
```

App 侧把同一个 **公钥（SPKI DER base64 或 x||y）** 内置到 `AuthLicenseVerifier`；把 worker 地址填进 设置→账号→服务地址。

## 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | /admin/accounts | 建账号（Bearer ADMIN_TOKEN；可带 activation_code 走 invited→首登激活） |
| POST | /admin/accounts/status | 停用/启用（停用即吊销全部会话） |
| GET  | /admin/accounts | 列表 |
| POST | /auth/login | {username,password,device_id,device_name,activation_code?} → access+refresh+license |
| POST | /auth/refresh | {refresh_token} → 旋转 refresh + 新 access + 新 license |
| POST | /auth/logout | {refresh_token} → 吊销会话 |
| GET  | /auth/status | Bearer access → 账号状态/能力 |
| GET  | /auth/devices | Bearer access → 本会话列表 |
| POST | /auth/devices/revoke | Bearer access + {session_id} |

## 安全边界

- 密码 PBKDF2-SHA256 100k 迭代（Workers 运行时审查方案，未自造算法）；登录按 账号+IP 限流。
- refresh token 只存 SHA-256 哈希，旋转签发。
- access token = ES256 JWT（15min），验签同时查 `sessions.revoked_at` 与 `users.status` → 停用/撤销立即生效。
- 离线授权凭证 = ECDSA P-256 签名 JSON（默认 7 天，可配）；**服务端撤销对离线设备不是即时生效**——凭证到期后必须联网续期，这是离线授权固有限制，已在客户端如实提示。
- 免费层额度有限：超额请求会失败，客户端对「网络/配额失败」按「沿用未过期授权凭证」处理，不误判为账号失效。
