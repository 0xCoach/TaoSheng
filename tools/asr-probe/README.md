# 转写方案实测脚本

任务卡 002 的实测结论见 `../../docs/任务卡/002-实测报告.md`；012 长录音实测见文末。

- `generate_samples.py`：macOS `say`（Eddy / Flo，中文中国大陆），ffmpeg 转换；双人 40 秒、独白 20 秒；逐句原文与时间保存在 `samples/*.json`。
- `probe.py`：HTTP base64 / WebSocket 探测，只在运行时读取任务卡指定的凭证文件。不支持命令行、环境变量或其他凭证来源。
- `analyze.py`：完全离线，从最终包提取结果、核算字错率和说话人时间范围，写 `results/summary.json`。
- `test_probe.py`：完全离线的预算、脱敏、协议边界和字错率测试，不读取真实凭证。
- `results/*.json`：服务端响应证据，保留 logid，不保存请求头或音频 base64。响应经过凭证及其组成部分脱敏。

本次环境版本见 `results/environment.json`。可用现有 Python + requests/websockets，或 `uv run probe.py ...`；uv 首次可能下载依赖。

```bash
python3 tools/asr-probe/analyze.py
python3 -m unittest discover -s tools/asr-probe -p test_probe.py -v
```

以下是带费用的调用示例，**本次实测已完成，不要为查看结果重新执行**：

```bash
python3 tools/asr-probe/probe.py 新的唯一标签 --scheme B --sample dialogue --format ogg --mode nostream --pace fast
```

参数：A/C 用 `--format wav` 或 `ogg`；B 可用 `pcm`（剥离 WAV 头）、`wav` 或 `ogg`，`--mode async|nostream`，`--pace fast|realtime`。所有测试均开启 `request.enable_speaker_info` 与 `show_utterances`；B 增加 `ssd_version="200"`，async 增加 `enable_nonstream=true`。

预算先以独占锁写入 `results/budget.json` 并 fsync，再发请求；异常不返还预算、不重试；重复标签拒绝。本次已保留 **280.0065 秒**，普通 20 秒/40 秒样本都无法再次发送。不要删除、编辑账本来重跑本次任务。依赖或网络异常时，日志只保留异常类型和允许的服务端响应字段，不输出请求对象或 traceback。HTTP 禁止跳转。

生成样本不联网，但会覆盖同名样本；不要在实测证据交付后再生成并混用。哈希见 `results/summary.json`。OGG 由 16kHz 单声道源以 `libopus -ar 16000 -ac 1 -b:a 24k` 编码，ffprobe 显示的解码采样率为 Opus 的 48kHz；服务端请求元数据为 16kHz，实测返回 40 秒完整结果。OGG 不等于裸 Opus 包。

## 012 长录音

- `long012.py generate`：只用 macOS say 生成 70/150/290 秒中文样本，存入 `samples/long012/`；不覆盖既有文件。声音、文本、拼接方式见 `source.json`。
- `long012.py 70|150|290`：同 App 的 `bigmodel_nostream` 配置、24kbps OGG Opus、原始 OGG 页传输和最后包标志。与旧 `probe.py` 的等字节分块不同，012 按页发送。
- 凭证仅由脚本调用 `load_key()` 从既定文件读取。输出只有脱敏元数据，不存凭证、请求头或转写原文。
- `results/long012/` 保存各次包时间、最终音频时长、尾句结束时间、文件哈希和独立预算。时间均从建连前开始计算，发完时间包含握手；客户端发完不等于服务端已处理完。探针的观察超时为 `30 + 2 × 时长` 秒，用来判断旧 90 秒上限；App 选择的超时策略另见交付说明。
- 本卡已经完成调用：实际发送 **510.0195 秒**；首次 DNS 失败也不退预算，保守计账 **580.026 秒 / 600 秒**，估算实际费用约 0.142 元（按任务卡约 1 元/小时）。不得删账重跑。`--retry` 只允许另记一次预算，不自动重试。
- 本次没有手机操作、没有读取或上传私人录音。macOS libopus 与 Android MediaCodec 的容器实现不相同，实测不能代替手机编码器及网络验收。

只读结果或运行离线预算测试：

```bash
python3 -m unittest discover -s tools/asr-probe -p test_long012.py -v
```
