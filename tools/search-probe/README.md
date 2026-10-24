# 026 验证工具

`only026.gradle` 将 Kotlin 测试编译输入限定为 `app/src/test/java/app/resound/search`，避开旧卡测试中的接口编译错误。仅在显式 `-I` 时生效，不修改普通构建配置。

`probe.py` 只使用内置的三条合成记录，下载固定 SHA 的本地 BGE ONNX 模型，比较 LIKE 与向量检索。没有手机访问代码，不读取真实数据库。

```sh
python3.12 -m venv /tmp/resound-026-probe-venv
/tmp/resound-026-probe-venv/bin/pip install onnxruntime==1.23.2 tokenizers==0.22.2 numpy
/tmp/resound-026-probe-venv/bin/python tools/search-probe/probe.py \
  --cache /tmp/resound-026-model --output /tmp/resound-026-result.json
```

默认不调用云端。显式 `--cloud` 才读取 `~/.config/resound/deepseek.key` 发起一次问答，密钥不输出、不保存；只把首条合成记录和旧检索空结果发送给 DeepSeek。每次执行不会自动重试云端请求。本卡已执行 1 次，结果在 `result.json`，不需要为了确认重复调用。

这不是 Android 真机验收，也不是多轮工具协议测试。模型来源、许可、迁移及其他验证结果见 `docs/任务卡/026-交付说明.md`。
