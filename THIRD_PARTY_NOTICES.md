# 第三方声明 · Third-Party Notices

本文件列出「声声不息」（app.resound）包含或派生的第三方代码、库与资源的授权信息。
声声不息自有品牌与原创内容不属本文件范围；以下条目按其各自许可使用。

---

## 1. 上游项目：涛声 TaoSheng（FSL-1.1-MIT）

本项目基于 **涛声 TaoSheng v0.4.0** 源码开发（https://github.com/0xCoach/TaoSheng）。

```
涛声 TaoSheng
Copyright 2026 0xCoach
Licensed under Functional Source License, Version 1.1, MIT Future License (FSL-1.1-MIT)
```

- v0.3.1 之后发布的版本按 FSL-1.1-MIT 提供；每个版本发布满两年后自动转为 MIT。
- v0.3.1 及之前版本按 MIT 许可发布，继续适用 MIT。
- 完整许可文本见仓库根目录 [LICENSE.md](LICENSE.md)。

FSL-1.1-MIT Redistribution 条款要求：分发本软件（含修改版）必须附带许可条款副本或链接，且不得移除随软件提供的版权声明。本文件与 LICENSE.md 即为该声明的保留方式。

### 按 MIT 许可贡献的代码（保留声明）

以下贡献在项目采用 MIT 许可期间合入，按 MIT 许可贡献，其代码继续保留 MIT 许可声明：

| 贡献者 | 内容 |
|---|---|
| @ethansunqing | PR #1 升级 ONNX Runtime，修复部分 Oryon 机型闪退 |
| @JunWeiUp | PR #9 本地声纹样本管理与声音清理；PR #19 足迹 |
| @SoukNow | PR #10 自定义转写完整请求地址；PR #11 合并多条记录 |
| @sheacoding | PR #13 移除机器专属 JDK 路径、补齐 Windows 构建入口 |
| @feitian124 | PR #27 README Star 历史曲线 |
| @xhp1211 | PR #39 D3200 迁移版音频与补传修复 |

```
MIT License

Copyright (c) 2026 0xCoach and contributors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
```

---

## 2. 随包分发的二进制与模型

| 组件 | 位置 | 许可 |
|---|---|---|
| sherpa-onnx（语音识别 JNI，`libsherpa-onnx-jni.so`） | `app/src/main/jniLibs/` | Apache License 2.0 — https://github.com/k2-fsa/sherpa-onnx |
| ONNX Runtime（`onnxruntime-android`、`libresound_asr_ort.so`） | gradle + `app/src/main/jniLibs/` | MIT — https://github.com/microsoft/onnxruntime |
| Silero VAD（`silero_vad.onnx`） | `app/src/main/assets/` | MIT — https://github.com/snakers4/silero-vad（许可副本随包：`assets/SILERO_LICENSE.txt`） |
| Qualcomm QNN HTP 库（`libQnn*.so`） | `app/src/main/jniLibs/arm64-v8a/` | Qualcomm 专有许可（QNN/Hexagon SDK），仅二进制分发 |

本地 ASR / 声纹模型（SenseVoice、CAM++ 等）由用户在应用内按需下载，不随 APK 分发；其许可以各模型发布页为准（SenseVoice：MIT；CAM++/3D-Speaker：Apache-2.0；Paraformer 等 ModelScope 模型见其页面声明）。

---

## 3. 构建依赖（Gradle）

Apache License 2.0：

- AndroidX（core、activity-compose、lifecycle、compose-bom/material3、room、sqlite、work、documentfile、test、datastore）
- Kotlin / kotlinx-coroutines / kotlinx-serialization / kotlinx-datetime（JetBrains）
- Ktor client（io.ktor）
- OkHttp、MockWebServer（Square）
- commons-compress、commons-io（Apache Software Foundation）
- ahocorasick（org.ahocorasick，许可副本随包：`assets/homophones/aho-corasick-LICENSE.md`）
- haze（dev.chrisbanes.haze）、reorderable（sh.calvin.reorderable）
- Robolectric（测试依赖）、WorkManager testing

MIT：

- TinyPinyin（同音词数据，许可副本随包：`assets/homophones/TinyPinyin-LICENSE.txt`）
- jieba 分词数据（许可副本随包：`assets/homophones/jieba-LICENSE.txt`）
- 拼音数据（`assets/homophones/pinyin-data-LICENSE.txt`）

其他：

- Google ML Kit 中文文字识别（`com.google.mlkit:text-recognition-chinese`）— Google ML Kit SDK 服务条款（专有 SDK）
- JUnit 4 — Eclipse Public License 1.0（仅测试）
- org.json — JSON.org 许可（仅测试）

---

## 4. 说明

- 声声不息自有品牌、图标与新增代码不主张上述第三方版权；第三方代码版权与许可按上表归属各自作者。
- 本应用不回传数据到上游项目；上游商标仅用于标识来源，不用于宣传。
- 若分发表中任何组件的副本、修改版或派生品，请携带对应许可条款副本或链接。
