# 本地 ASR 运行组件来源

- sherpa-onnx Kotlin API / JNI：v1.13.8，Apache-2.0，https://github.com/k2-fsa/sherpa-onnx
- 原生库取自该版本官方 QNN Android 示例 APK，发布路径：
  https://huggingface.co/csukuangfj2/sherpa-onnx-apk/resolve/main/qnn-vad-asr-simulated-streaming/1.13.8/sherpa-onnx-1.13.8-qnn-arm64-v8a-simulated_streaming_asr-zh-30-seconds-zipformer_ctc_2025_07_03_int8.apk
- 内含新 ASR ONNX Runtime 1.28.2（MIT）、Qualcomm QNN HTP/System 与 V75 Stub/Skel。仅保留 SM8650 用到的库，不含模型图编译组件。
- Java ONNX Runtime 用于 Silero VAD / CAM++ 和本地搜索；本测试分支由 1.23.2 升至 1.25.1，验证情况见 `docs/任务卡/ORT1251-交付说明.md`。不能以 pickFirst 混用这两个库：原生符号版本不同会造成启动崩溃。
- tools/local-asr/prepare-native.sh 将新 ORT SONAME 和 sherpa DT_NEEDED 改为 libresound_asr_ort.so，不更改计算逻辑。两者独立打包；ASR 运行于 :local_asr 进程。
- SenseVoice 模型来源与固定 SHA-256 见 LocalModels.kt，原始 LICENSE 随下载保留在模型目录。本次仅自用验证版，不改变模型或 QNN 上游许可。
- APK 的模型权重仍仅包含已有 VAD / 声纹权重；新增 ASR 权重在 noBackupFilesDir 下载，不进入录音备份。

## 多人分段

- 新增 sherpa-onnx v1.13.8 的 OfflineSpeakerDiarization Kotlin API，复用现有 JNI。进度回调使用显式 Function3 对象以保留 JNI 要求的 invoke(IIJ)Integer 方法。
- 按需下载官方 `sherpa-onnx-pyannote-segmentation-3-0.tar.bz2`，下载 6,958,444 字节，安装浮点模型 5,992,913 字节。固定 SHA-256 见 LocalModels.speakers；LICENSE 随下载提取。模型 MIT，Copyright (c) 2022 CNRS。
- 官方说明：https://k2-fsa.github.io/sherpa/onnx/speaker-diarization/models.html 。声纹嵌入复用已有 CAM++，不新增另一份声纹模型。
- 分段及本地 ASR 在隔离进程执行，CPU 做分段，CPU/NPU 做 ASR。说话人簇数自动估计，不硬设为 2 或 3；重叠范围保留未知，不把多出的簇强行并入前三人。
