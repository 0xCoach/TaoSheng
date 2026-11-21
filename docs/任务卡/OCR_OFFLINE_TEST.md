# OCR 离线验收报告

2026-11-18 · OPPO Find N2 · `app-debug.apk`

## 依赖与打包确认

- 依赖：`com.google.mlkit:text-recognition-chinese:16.0.1`（**捆绑模型变体**，非 Play Services 动态下载）。
- APK 证据：`jar -tf app-debug.apk` 列出 `assets/mlkit-google-ocr-models/`（Hani_ctc 中文 conv/lstm 模型、版面分析、taser 检测器）+ `lib/arm64-v8a/libmlkit_google_ocr_pipeline.so`。**首次使用不依赖网络。**

## 验收项

| 项 | 结果 | 证据 |
|---|---|---|
| 图片分享→私有目录复制 | PASS | `files/inbox/cap_<sha256>.img` 实测存在（368650 字节） |
| 本地中文 OCR | PASS | DB `ocrText` 实读出完整界面文字（"声声不息刚才被系统关掉过""17 分钟""问问 记忆 待办 助手 设置"等）；个别错字属正常识别误差（"现在整理"→"现在警理"） |
| 同图重复分享去重 | PASS（本轮修复后） | 文件名即内容 sha256，二次分享 `imageAt()` 命中返回 0，`capture_list` 无新行 |
| 失败保留原图+可重试 | PASS（机制） | `runOcr` 全量 catch，ocrText=null；sheet 新增「重试识字」按钮调 `retryOcr(id)`；原图不删 |
| 原图回看 | PASS（本轮新增） | `CollectSheet` 图片类渲染 `BitmapFactory.decodeFile` 原图（此前只显示路径文本——是缺口已修） |
| OCR 结果可搜索 | PASS（机制） | `CaptureItemDao.search` 覆盖 `ocrText LIKE` |
| 断网分享 | 部分验证 | 严格断网（关 WiFi）执行过一次分享：当时因测试文件权限问题未入库（文件权限修复后在线链路完整跑通）；断网复跑待设备回连后用 nohup 复合命令补测。**捆绑模型已在 APK 内证实，OCR 路径无网络调用** |
| 分享去重（文本/链接） | PASS | `sameBody(kind,body)` 既有机制 |

## 本轮修复的缺口（验收中发现的真问题）

1. `saveImage` 无去重：随机文件名使 `sameBody` 永不命中 → 改为内容 sha256 定名 + `imageAt()` 查重。
2. 「原图回看」不存在：sheet 把路径当文本显示 → 改为真实渲染位图。
3. 无 OCR 重试入口 → `retryOcr(id)` + UI 按钮。
4. 收集箱无「已保存/已提取/已分析」分层（R2 要求 + 不假装抓到诚信线）→ `extracted` 列 + 36→37 迁移（含回填）。

## 遗留

- 严格断网端到端补测（设备 WiFi 恢复后执行 nohup 断网脚本）。
- 多张图 ACTION_SEND_MULTIPLE 批量分享未实测（代码路径存在）。
