# 006 声纹离线验证

先在本目录完成 Python 推理、真实录音检查和 Kotlin 特征对齐，再接入 App；不调用云端声纹服务。

## 复现

环境：macOS（say）、ffmpeg、Python 3.14、JDK 21、项目 Gradle 缓存。Python 依赖锁在 `requirements.txt`。

```sh
python3 -m venv tools/voiceprint-probe/.venv
tools/voiceprint-probe/.venv/bin/pip install -r tools/voiceprint-probe/requirements.txt
cp app/src/main/assets/voiceprint/campplus.onnx tools/voiceprint-probe/model.onnx
tools/voiceprint-probe/.venv/bin/python tools/voiceprint-probe/probe.py
tools/voiceprint-probe/.venv/bin/python tools/voiceprint-probe/check_fbank.py
```

可选 `probe.py --real-dir tools/voiceprint-probe/private`：目录里放通过任务卡允许的 `adb exec-out run-as app.resound cat files/segments/...ogg` 只读取得的录音。没有真实录音也可复现合成实验。不要把个人录音加入 Git。本目录 `.gitignore` 已排除 `private/`、合成缓存、模型副本与 Python 环境。

- 三种有效中文音色 Tingting、Meijia、Sinji，各五段；Tingting 前三段注册，后二段留出验证。
- 最初也测过 Eddy 中文音色，但本机实际输出与 Tingting 的 PCM 相同，不能当作不同人，已剔除。
- 20ms 帧 RMS ≥ 0.002 保留，其余视为静音。对应小米低增益采集，未改录音服务 VAD。
- CAM++ 元数据规定输入幅值归一化到 [-1,1]，80 维 fbank，减全段每维均值。
- 按 sherpa-onnx v1.13.8 实际配置：16k、25ms/10ms、Povey、preemphasis 0.97、去 DC、dither 0、snip_edges=false（首尾反射补边）、mel 20～7600Hz。
- 上游 `scripts/3dspeaker/test-onnx.py` 使用不同的 snip_edges/high_freq；不能直接用它的默认参数代表 sherpa 当前实现。

`check_fbank.py` 调用缓存 Kotlin 编译器编译 App 的真实 `KaldiFbank.kt`，逐值比较原始特征及 Kotlin 均值归一化后的特征，然后分别交给 Python ONNX Runtime 计算向量。`fixtures/` 只含静音、固定随机噪声、say 合成声音，供离线与单元测试使用。

## 结果文件

- `results/offline.json`：版本、模型 hash、全部同人/异人分数、平均与最大匹配比较、三段无标签真实录音的统计。
- `results/fbank.json`：21,440 个原始特征值、均值归一化误差及向量余弦。
- `results/verification.json`：APK 字节数、包内模型 hash、原生库清单、定向测试摘要。
- `results/baseline-apk-bytes.txt`：接入前同一工作树 `assembleDebug` 产物字节数。

测试没有人工标注的负责人/其他人真实音频对，不能输出真实误识率。合成实验建议候选阈值 MAYBE=0.75、SURE=0.85；实际应用仍遵守 Contract 的 0.50/0.62，需 Claude 确认后改约定。详情见 `docs/任务卡/006-交付说明.md`。

## 固定来源

- 模型发布标签：[speaker-recongition-models](https://github.com/k2-fsa/sherpa-onnx/releases/tag/speaker-recongition-models)，文件 `3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx`。
- 原模型：[iic/speech_campplus_sv_zh-cn_16k-common](https://www.modelscope.cn/models/iic/speech_campplus_sv_zh-cn_16k-common/summary)。
- SHA-256：`f682b514c05d947ee3fa91cd6ec6c5c7543479a128373fa29b1faedccd21fd11`。
- 许可：[3D-Speaker Apache-2.0](https://github.com/modelscope/3D-Speaker/blob/main/LICENSE)，原文在 `MODEL_LICENSE.txt`，随 App 打包在 `assets/voiceprint/LICENSE.txt`。
- sherpa 配置：[v1.13.8 features.h](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/sherpa-onnx/csrc/features.h)、[声纹实现](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/sherpa-onnx/csrc/speaker-embedding-extractor-general-impl.h)。
