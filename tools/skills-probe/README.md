# 031 技能离线实测

只读取已经存在的本机库副本，不连接手机、不回写原库。真实数据与响应只放 `private/`，已加入 `.gitignore`。密钥只由脚本读取 `~/.config/resound/deepseek.key`，不打印、不写文件。

1. 用 SQLite backup 将已经离线的源库复制到 `private/capture.db`（源库有 WAL 时必须同时读取 WAL，不能直接只拷主文件）。
2. 跑本卡测试时设置 `RESOUND_SKILL_PROBE_DB`、`RESOUND_SKILL_PROBE_OUT` 两个绝对路径，`exportsBoundedProbeRequestsUsingProductionPromptAndLocalCopyWhenProvided` 在隔离副本上迁移，使用生产 `SkillInputs`、`SkillPrompt` 为三个技能导出请求。
3. `python3 tools/skills-probe/probe.py` 各发送一次。默认和 App 相同的 `deepseek-flash`，已有响应则跳过。请求范围、引用表、回复只留本机；人工对照输入核对归属，结果摘要写交付说明。

本次使用 2026-10-23 样本；导出测试中日期固定，以便复现。日后更换样本须同时改日期；该测试不连接云端，普通 CI 没有上述环境变量时仅验证固定规则存在。
