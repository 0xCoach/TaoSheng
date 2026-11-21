# R4 授权审查 · License Audit

## 结论：`LICENSE_GO`

上游许可已恢复并建立防删除门禁；第三方组件声明完整；App 内可查看。

## 依据来源

从 `v0.4.0` 原始 tag 提取（非上游 main 猜测）：

- `LICENSE.md`：Functional Source License 1.1, MIT Future License，`Copyright 2026 0xCoach`（已恢复至仓库根目录，与 tag 内容逐字一致）
- `NOTICE.md`：上游许可说明 + MIT 期间贡献者清单 → 已并入 `THIRD_PARTY_NOTICES.md` 第 1 节

## 本次交付

| 项 | 文件 | 说明 |
|---|---|---|
| 上游许可恢复 | `LICENSE.md` | FSL-1.1-MIT 完整文本，来自 v0.4.0 tag |
| 第三方声明 | `THIRD_PARTY_NOTICES.md`（仓库根） | 上游版权+许可、MIT 贡献者清单、随包二进制（sherpa-onnx/onnxruntime/silero/QNN）、全部 Gradle 依赖分类 |
| App 内可查 | `app/src/main/assets/THIRD_PARTY_NOTICES.md` + `AboutPage.kt`「开源许可」卡片 | 设置 → 关于 → 开源许可 → 查看完整声明 |
| 防删除门禁 | `app/src/test/.../license/LicenseGateTest.kt` | 4 用例：LICENSE 存在且完整 / NOTICE 保留上游版权 / assets 随包 / 组件许可副本在位 |

## 关键判定

- FSL-1.1-MIT Redistribution：分发修改版须附许可条款副本或链接、不得移除版权 → `LICENSE.md`+`THIRD_PARTY_NOTICES.md`+APK 内 assets 三处满足。
- MIT 期间合入的贡献（ethansunqing/JunWeiUp/SoukNow/sheacoding/feitian124/xhp1211）保留 MIT 声明全文，见 THIRD_PARTY_NOTICES 第 1 节。
- 品牌分离：未恢复任何涛声产品宣传入口；上游商标仅作为来源标识出现，声声不息自有品牌独立。
- 专有组件：QNN HTP 库（Qualcomm 专有，仅二进制）、ML Kit 中文识别（Google SDK 条款）已明确标注；ASR/声纹模型为应用内按需下载、不随包分发，声明指向各模型页。

## 验证

```
gradlew :app:testDebugUnitTest --tests app.resound.license.LicenseGateTest
→ BUILD SUCCESSFUL（4/4 通过）
```

## 遗留

- 商业分发（R5）前需复核 FSL「Competing Use」边界——当前个人自用分发无竞争用途，合规。
- QNN .so 属 Qualcomm 专有再分发，若公开发布需确认 QNN SDK 再分发条款。
