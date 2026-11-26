# CLA 2.0 交付说明

- 代码贡献的著作财产权在贡献者同意 CLA 2.0 并合入相应代码时无偿转让给 0xCoach 本人。协议保留署名权，历史贡献沿用原协议或许可，非代码贡献沿用许可方式。
- 贡献指南、中英文 README 和 CLA Assistant 已同步。新版使用明确同意转让的签署语句，签署记录写入 `signatures/version2/cla.json`。机器人给出绑定到提交的协议链接。
- 已验证工作流 YAML、文档与签署语句的一致性和版本化记录路径，并通过 `python3 tools/check_shared.py` 和 `./gradlew --offline --no-daemon :shared:check`。
- 上线后需在现有未合入 PR 重新运行 CLA 检查。维护者可在 PR 留言 `recheck`，核对旧签名不会通过新版检查，再用新版签署语句验证签署和状态。签署验证仅需 GitHub，无真机步骤。
- 受让人目前按 CLA 2.0 首次发布时的 0xCoach 账号界定。需要提交权属证明时，按协议补充双方真实身份及对应贡献记录。
