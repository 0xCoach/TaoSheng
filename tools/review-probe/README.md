# 每日回顾离线验证

在仓库根目录运行 `python3 tools/review-probe/probe.py --pull --run --date 2026-10-23`。

前提：负责人授权的手机已连接 ADB、DeepSeek 密钥已在 `~/.config/resound/deepseek.key`。脚本不打印密钥或服务端异常正文。

手机操作仅为 `adb exec-out run-as app.resound cat databases/capture.db` 及 `-wal` / `-shm`；复用 ask-probe 的双读快照与本机完整性检查，不安装、不点屏、不改手机数据。原始数据保留在已忽略的 `private/`。

调用只发送指定日期未删除且非媒体/旁人的事件摘要和关联提取项（含状态），不读原话。共用 App 的 review.md；Python 输入序列化和解析规则与 Kotlin 对齐。真实输出写到 private/results.md（已忽略，不进仓库），随后人工核对出处、遗漏和语气。离线调用的 token 仅记在报告，不写回手机。
