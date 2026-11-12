# 卡 151：大模型与云端转写的网络层换成 Ktor（共用）

## 背景

0.3.0 iOS 准备第 3 张卡。卡 149/150 已建 `:shared`（门禁 `tools/check_shared.py`）和基础插口（`shared/.../platform/`：NowMillis、Logger、AppDispatchers、SharedJson 等）。OkHttp 只能在安卓用，iOS 需要 Ktor（安卓底下仍用 OkHttp 引擎，iOS 用 Darwin 引擎）。

本卡只换**最关键的两条链路**：调大模型、云端转写。推送、备份（WebDAV/S3）、更新下载、地点、录音上传、本地模型下载**不在本卡**，留后续卡。

**总原则：对用户行为完全不变**——同样的请求（方法、地址、请求头、请求体）、同样的超时、同样的错误分类和中文提示、同样的流式效果、同样的服务商自动切换。

## 范围（相对 `app/src/main/java/app/resound/`）

| 文件 | 行数约 | 内容 |
|---|---|---|
| `digest/LlmClient.kt` | 213 | chatJson（非流式）、streamChat（SSE 流式）、错误分类 LlmError |
| `digest/ServiceHeaders.kt` | 75 | 服务商自定义请求头 |
| `usage/LlmBalance.kt` | 184 | 余额查询 |
| `transcribe/ConfiguredAsrProvider.kt` | 77 | 自定义转写（上传音频，8 分钟读超时） |
| `transcribe/OpenAiEndpoint.kt` | 68 | 拉取模型列表 |
| `transcribe/DoubaoAsrProvider.kt` | 113 | 豆包转写（WebSocket 二进制协议） |
| `transcribe/HttpAwait.kt` | 17 | OkHttp 挂起辅助 |

## 要做的

### 1. 依赖与门禁
- shared commonMain 加 `io.ktor:ktor-client-core`、`ktor-client-websockets`（版本选与 Kotlin 2.2.20 兼容的最新稳定 3.x，交付说明写清版本和理由）；androidMain 加 `ktor-client-okhttp`；iOS 目标开启时 iosMain 加 `ktor-client-darwin`（`taosheng.ios=true` 才配置）。jvmTest 加 `ktor-client-mock`。
- `tools/check_shared.py` 白名单加入这几个坐标（只加这些）。

### 2. 共用网络层（`shared/src/commonMain/kotlin/app/resound/net/`）
- 一个 `HttpClient` 工厂：由平台传入引擎（安卓 OkHttp、iOS Darwin、测试 Mock），超时、是否跟随重定向等按调用方需要配置——**逐个对齐**原 OkHttp 设置（connect/read/call 超时、`followRedirects(false)`、`retryOnConnectionFailure` 等），对不齐的写进交付说明。
- 把上表的**请求拼装、响应解析、错误分类、SSE 行解析、豆包二进制帧编解码**等规则搬进 shared（包名不变或放 `app.resound.net`，自定），JSON 一律用 `SharedJson`/`JsonElement`，不用 org.json。
- 约定（`LlmProvidersContract` 等）签名不改；`LlmClient` 等对外函数签名不改（安卓调用方不动）。若必须改，先在交付说明提出。
- 取消：协程取消时必须中断网络请求和 WebSocket（与原行为一致）。

### 3. 新旧对照测试（最重要）
- 在 app 的 jvm 单测里，用现有 `mockwebserver` 起本地服务器，对同一批脚本化响应，分别走**旧 OkHttp 实现**（保留在测试里或作为 `legacy` 实现）和**新 Ktor 实现**，断言：
  - 发出的请求逐项相同：方法、路径与查询、请求头（顺序可不同，值相同）、请求体字节。
  - 解析结果相同：LlmReply 内容/思考/用量、余额数字、模型列表、转写文字与时间。
  - 错误相同：401/402/403/429/5xx、超时、断流、非 JSON、空回复 → 相同的 LlmError 与中文提示。
  - 流式：分片到达、`[DONE]`、中途断开、思考字段，逐段回调顺序相同。
  - 豆包：握手请求头、首包、音频分包、结束包、服务端错误帧，编码字节逐字节相同，结果相同。
- 每类 2～4 个用例，总共 15～25 个，不穷举。

### 4. 可退回开关（预览期用）
- 保留旧 OkHttp 实现一个版本：`AppGraph` 根据键值 `net.legacy_okhttp`（`KeyValueStore`，默认 false）选择新/旧实现，重启生效。开发者选项里的开关界面由 Claude 做，本卡只提供读取和 `AppGraph` 选择逻辑，在交付说明写清键名。
- 0.3.1 再删旧实现（不在本卡）。

## 测试与验证
- 只跑：`./gradlew :shared:check :shared:jvmTest :app:compileDebugKotlin`、`python3 tools/check_shared.py`、`python3 tools/check_devices.py`、本卡新增对照测试，以及引用了上表类的现有 app 单测（列清单跑一次）。
- 已知 10 个老失败（与本卡无关）：PushChannelsTest 1、Subscription042Test 4、ManualReview058Test 1、ReviewInputLimit101Test 1、Starred041Test 1、Usage043Test 1、AskToolsTest 1。名单变了要报告。
- **不要连真实服务商、不读任何密钥文件**；真机/真服务商实测由 Claude 之后做。

## 不做
- 不换推送、备份、更新、地点、录音上传、本地模型下载的网络代码。
- 不改界面（`ui/`）、数据库、不升其他依赖版本。

## 交付
分支 `codex/151-ktor-network`（已从 main 55953d8 建好，工作目录 `~/Developer/TaoSheng-151`），中文提交，不推送，不操作手机。写 `docs/任务卡/151-交付说明.md`：依赖版本、超时等设置对照表、搬了什么、对照测试清单与结果、开关键名、对不齐的地方、真机需要注意的、遗留问题、进度统计（`python3 tools/ios_progress.py`）。编译前 `export JAVA_HOME=/opt/homebrew/opt/openjdk@21`；Gradle 异常先 `./gradlew --stop` 再试，仍不行写进交付说明，不清缓存。
