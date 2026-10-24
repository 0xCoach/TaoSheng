# 问问离线验证

先执行 `python3 tools/ask-probe/probe.py --pull`，再执行 `python3 tools/ask-probe/probe.py --run`。

- 手机端仅执行 `adb exec-out run-as app.resound cat databases/capture.db`（及 `-wal`、`-shm`）。不安装、不停 App、不点屏幕、不写数据。
- 连续两次主库与 WAL 一致才接收快照；在电脑重建 SHM、检查完整性并备份为 snapshot.db。忙碌时最多试 4 次，不能获得稳定快照就退出。
- 密钥由脚本读取 `~/.config/resound/deepseek.key`，不打印、不写报告。请求目标为 `https://api.deepseek.com/chat/completions`，模型 `deepseek-flash`。
- 网络请求只发送问题、提示词和工具检索出的片段，每个工具最多 40 条 / 6000 字。原始数据库和完整工具轨迹仅在 `private/`，不提交。
- App 与 Python 使用相同的 `app/src/main/assets/prompts/ask.md`、`ask-tools.json`、`ask-sql.json`。日历与 `DigestPrompt.calendar` 一致；from 含起点，to 含整天或该分钟。
- `private/results.md` 保存六问、工具参数、回答、实际引用的摘录及复核意见。含实测原话，放在已忽略的 private/ 里，不进仓库。
- 本地验证：`python3 -m unittest discover -s tools/ask-probe -p test_probe.py`（真实快照测试需要先拉库）。

定向复核可使用 `--run --question '手机日志的事后来怎么样了'`，保留其他已完成问题，避免重复调用。最终报告包含人工语义复核意见；重新生成报告后必须重新复核。出处的事件/提取项摘录是同事件按时间排列的原话上下文，不是机器摘要。
