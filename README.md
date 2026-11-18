# 声声不息

个人 Android AI 语音助手与"第二大脑"：全天聆听 → 人声片段 → 本地转写 → 大模型整理成事件、记忆与可追问的知识。

- Kotlin + Jetpack Compose，minSdk 29，包名 `app.resound`
- 本地 sherpa-onnx / SenseVoice 转写，本地 Room 数据，用户数据不出机（除自配 AI/备份通道）
- 灵感收集箱、项目记忆、决策与跟进、有出处问答、规则工作流、本地 MCP 只读接口
- 构建：Windows `.\gradlew.bat assembleDebug`，JDK 21；开发约定见 `AGENTS.md`
