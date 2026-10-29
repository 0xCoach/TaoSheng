# 环境音识别组件（卡 070）

- 模型：`sherpa-onnx-zipformer-small-audio-tagging-2024-04-15`，AudioSet 527 类，只使用 `model.int8.onnx` 和 `class_labels_indices.csv`。
- 官方包：https://github.com/k2-fsa/sherpa-onnx/releases/download/audio-tagging-models/sherpa-onnx-zipformer-small-audio-tagging-2024-04-15.tar.bz2
- 模型许可：Apache-2.0；官方包 README 的 YAML 明示 `license: apache-2.0`。训练实现来源：https://github.com/k2-fsa/icefall/pull/1421 。
- Kotlin API：直接取自 sherpa-onnx v1.13.8 的 `sherpa-onnx/kotlin-api/AudioTagging.kt`，保留数据类和 JNI 接口，去掉示例配置函数；Apache-2.0。
- API 原文：https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/sherpa-onnx/kotlin-api/AudioTagging.kt
- 许可原文：https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/LICENSE
- JNI：复用项目现有 v1.13.8 `libsherpa-onnx-jni.so`，已核实 `AudioTagging_{newFromAsset,newFromFile,createStream,compute,delete}` 和 OfflineStream 入口均导出。没有更新任何 `.so`，没有改变本地 ASR 的进程或模型。
- 正式镜像（待 Claude 上传）：`https://modelscope.cn/models/OpenRecode/TaoSheng-models/resolve/master/audio-tagging/zipformer-small-2024-04-15/`。文件不可因镜像同路径更新而绕过固定 SHA-256 校验。
- 权重按需存入 `noBackupFilesDir/env-sound/zipformer-small-2024-04-15/`，不打进 APK。镜像下载两个文件约 27.05 MB；镜像不可用时从 GitHub 下载上游原始包 111,034,072 字节，校验压缩包，再提取所需两个文件并分别校验。不提取浮点模型和测试音频。
- 下载复用 `LocalModels.downloadModelFile` 的 Range 续传、停滞检测与完整文件 SHA-256 校验；仓库层在国内源失败后切到官方压缩包。进程退出保留 `.part`，下次启用/启动/重试继续下载。

| 文件 | 字节数 | SHA-256 |
| --- | ---: | --- |
| model.int8.onnx | 27038066 | 69304b8a1b96bbe6b7d16c24079f0732c65faf3568a14cb82a8238c709afe76c |
| class_labels_indices.csv | 14675 | cdd1049833c4b86127c2773ac0d14a2754b6a6d0d1798002ed5c66e699708429 |
| sherpa-onnx-zipformer-small-audio-tagging-2024-04-15.tar.bz2（仅备用源） | 111034072 | 07e2fafcdcbc461f2816188d9b0bbafced12584030cf67d5652e549ef256a2c6 |

上述哈希为本次下载后逐文件计算；061 provenance 的 `69304b8a…` 实际是 int8 单文件哈希，不是目录哈希。私人样本和评测数据没有复制入仓库。
