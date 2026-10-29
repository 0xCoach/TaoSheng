# 卡 055 · 自定义模型支持 http 局域网地址（后端）

App 名叫“涛声”。给用户看的文字一律写“涛声”，只说结果、不讲机制。
约定（签名不要改；确需改，在交付说明里提）：
- `transcribe/OpenAiEndpoint.kt` 里的 `EndpointKind` 和 `OpenAiEndpoint.kind()`（负责人已写好，可修 bug，不改签名）。

界面（服务地址下方的提示行、保存按钮在无密钥时可点）由 Claude 做，**本卡不改 `ui/` 和 `MainActivity.kt`**。

## 一、已定方案（负责人已确认）

- 自定义服务（整理模型、转写模型两处“自定义”）的地址 **http 和 https 都接受**，不按局域网/公网拦截；公网 http 只由界面给提醒。
- **密钥可以留空**：本地模型（Ollama、LM Studio、vLLM 等）常常不需要密钥。留空时**请求不带 `Authorization` 头**。
- 本地模型首次加载慢，局域网地址要等得更久。
- 连不上时，报错要说清楚是哪种情况。

## 二、实现要点

1. `OpenAiEndpoint.bases()`：协议允许 `http`/`https`（用 `kind()!=Invalid` 判断），其余检查不变。错误文案改为“请输入 http:// 或 https:// 开头的服务地址，不要包含密钥或查询参数”。
2. `ConfiguredAsrProvider.kt:26` 去掉只认 `https://` 的判断，改用同样的判断。全局搜一遍 `transcribe/`、`digest/`、`ask/`、`skills/` 以及“模型检测”（逐项检查连接与密钥那套）里只认 https 的地方，一并放开。**推送通道、备份、更新等固定地址不改。**
3. **密钥可空**（`AndroidDigestSettings`、`AndroidTranscribeSettings`）：
   - `saveCustomAll` 不再要求密钥；填了就存，留空时沿用已存的；都没有就按“无密钥”保存。
   - 自定义来源下，`hasKey` / `ready` 的含义改为“**已可用**”：有密钥，或者自定义地址和模型都已保存（密钥可无）。首页、原话页靠它判断“是否配置好”，界面不用改。
   - `fetchRemoteModels`、`LlmClient`、`CustomAsrProvider` 以及模型检测：密钥为空时不加 `Authorization` 头；`require(key.isNotBlank())` 一类的检查对自定义服务去掉。
   - 内置的豆包、DeepSeek 等预设照旧必须有密钥，行为不变。
4. **等待时间**：`kind()==LocalHttp` 时，拉取模型列表的总超时 25 秒改为 60 秒；整理、问问的读超时至少 180 秒；转写保持现有 8 分钟。连接超时 10 秒。其他情况不变。
5. **报错分类**（拉取模型、模型检测、整理/转写失败时的 `lastError`，统一一个小函数）：
   - 拒绝连接 / 无路由：“连不上 {主机:端口}：确认服务已启动，手机和它在同一网络”；如果是局域网地址，再加一句“Ollama 需在电脑上设置 OLLAMA_HOST=0.0.0.0 才能被局域网访问”。
   - 超时：“等待超时：本地模型首次加载可能较慢，稍后再试”。
   - 域名解析失败：“找不到这个地址，请检查拼写”。
   - 401/403：沿用“鉴权失败，请检查密钥”。
6. **新版安卓的局域网权限**：查一下 targetSdk 35 / compileSdk 36 下，访问局域网是否需要声明新权限（如 `ACCESS_LOCAL_NETWORK`）。需要就在 Manifest 里声明，并在交付说明里写清楚运行时要不要用户授权；不需要就写明结论。`usesCleartextTraffic` 已是 true，不用改。

## 三、必测（适度，一共 5～8 个）

- `kind()`：https / 192.168.1.5:11434 / 公网 http 各 1 个。
- `bases()` 接受 `http://192.168.1.5:11434` 并补 `/v1`。
- 自定义来源无密钥保存后 `ready == true`；请求不带 `Authorization`（用 MockWebServer 抓请求头，整理和转写各 1 个即可）。
- 拒绝连接时报错文案正确（1 个）。

测试规矩见 `AGENTS.md`：只跑本卡测试，跑通一次就停。

## 四、禁区

- 不改 `ui/`、`MainActivity.kt`。
- 不改推送、备份、更新的地址校验。
- 不碰手机（不 adb 安装、不点手机），真机由负责人测。

## 五、交付

`docs/任务卡/055-交付说明.md`：改了哪些文件、新的报错文案清单、局域网权限的结论、真机注意事项。**不要 git commit**，负责人审核后统一提交。
