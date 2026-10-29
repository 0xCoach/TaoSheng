# 061 环境音研究复现

仅本机研究，不写 `app/`，不连接手机，不提交 Git。原来的 `probe.py` 保持不变。

```bash
# 当前本机环境已具备依赖；新环境可安装 requirements-061.txt
bash tools/audio-tagging-probe/run_061.sh
# 已下载时避免联网；已有完整模型分数不会重复推理
bash tools/audio-tagging-probe/run_061.sh --offline
# 只检查结果完整性
~/.venvs/audiotag/bin/python tools/audio-tagging-probe/verify_061.py
```

默认数据目录 `~/Datasets/taosheng-audio/`；模型只读 `~/.claude/jobs/3b8dcfaa/tmp/at/` 的三套 int8 文件。可通过 `AUDIO_TAG_DATA`、`AUDIO_TAG_MODELS`、`AUDIO_TAG_PYTHON` 指定位置。需要允许写数据目录；在受限会话按单次审批执行。新环境需安装 ffmpeg。当前 Python 3.14，依赖版本见 requirements 文件。

顺序：download（仅下载、锁版本、保存许可与哈希，8 GB 累计上限）→ prepare（划分后生成模拟素材）→ evaluate（完整 527 类、2 线程、缓存同一窗口）→ tune（只看调参）→ validate（冻结后验证）→ report。任何下载失败都会报错并保留已完成文件；不把错误页当音频。当前下载来源已成功，无需全量重下。

`frozen-config.json` 存在时禁止重调。要改实验设定，用新的数据目录；不要删除冻结文件后用同一验证集反复调参。推理有完整结果时校验清单和模型哈希再跳过。断在某模型中途会重新运行该未完成模型，不影响完整模型。数据版本和源码均应保留，结果 CSV 不提交。

公开源文件和派生文件全部留在数据目录；私人文件只引用原路径，未复制。报告只展示其文件名和分数。`segment-142` 未知标签不进入指标。数据集主标签并非逐秒完整标注；模拟语音、模拟影视不能当真实场景验收。

报告：[061-评测报告.md](../../docs/任务卡/061-评测报告.md)。精确阈值与标签全集在数据目录 `results/frozen-config.json`、`results/label-map.csv`，研究候选配置同时写到本目录 `recommended-061.json`。
