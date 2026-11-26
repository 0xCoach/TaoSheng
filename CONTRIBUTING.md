# 参与贡献 · Contributing

感谢你愿意帮涛声变得更好！提议题、提 PR、真机测试反馈都非常欢迎。

## 提交 PR 之前

1. **签署贡献者协议（CLA）**：请在提交代码前阅读 [CLA.md](CLA.md)。机器人会在 PR 下提示尚未签署本版本的贡献者，同意的话回复：

   ```
   I agree to the TaoSheng CLA v2.0 and its copyright assignment terms
   ```

   每个 GitHub 账号只需签署 2.0 版一次。**提交的代码经项目合入时，你享有且依法可转让的著作财产权无偿转让给维护者 0xCoach 本人。** 旧版签署记录不能代替 2.0 版签署。

2. **许可**：涛声按 [FSL-1.1-MIT](LICENSE.md) 提供（v0.3.1 及之前为 MIT，见 [NOTICE.md](NOTICE.md)）。你的贡献会随项目按该许可发布。

3. **开发约定**：请先读 [AGENTS.md](AGENTS.md)。要点：
   - 新的业务规则放 `:shared` 共用模块，不引用安卓专属接口；
   - 只写、只跑与改动相关的测试，提交前跑 `python3 tools/check_shared.py` 和 `./gradlew :shared:check`；
   - 提交信息用中文，说明改了什么、为什么。

4. **大改动先开议题讨论**，避免方向不一致白做。

## 议题

- 报问题时请附上“设置 → 高级与诊断 → 导出问题记录”导出的文件（已自动脱敏，不含原话、待办和密钥），并写明手机型号、系统版本和涛声版本。
- 不要在议题里贴任何 API 密钥。

---

## English

Thanks for helping improve TaoSheng! Issues, pull requests and on-device test reports are all welcome.

1. **Sign the CLA**: read [CLA.md](CLA.md) before submitting code. If you agree, reply to the bot on your pull request with `I agree to the TaoSheng CLA v2.0 and its copyright assignment terms`. Each GitHub account signs v2.0 once. **When your code is merged, the copyright economic rights you own and can legally transfer in it pass to 0xCoach personally, free of charge.** A previous version’s signature does not count for v2.0.
2. **License**: TaoSheng is available under [FSL-1.1-MIT](LICENSE.md) (versions up to v0.3.1 are MIT; see [NOTICE.md](NOTICE.md)).
3. **Conventions**: read [AGENTS.md](AGENTS.md) first. Shared business rules go in the `:shared` module; run only the tests relevant to your change plus `python3 tools/check_shared.py` and `./gradlew :shared:check`.
4. **Discuss large changes in an issue first.**

When reporting a bug, attach the problem report exported from Settings → Advanced & Diagnostics (it is redacted and contains no transcripts, to-dos or keys). Never post API keys.
