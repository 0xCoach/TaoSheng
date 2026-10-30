# 卡 072 寻物离线验证

提示词直接读取 `app/src/main/assets/prompts/things.md`。请求使用与 App 一样的 `chat/completions`、`temperature=0.2` 和 JSON 输出格式；API 模型名为 `deepseek-chat`，响应中的实际模型另存。

密钥只由脚本读取 `~/.config/resound/deepseek.key`，不打印、不写报告。`private/` 中的数据库、原话、请求与响应均由 `.gitignore` 排除。

```bash
# 手机仅执行 adb exec-out run-as app.resound cat databases/capture.db[-wal/-shm]
# 双读主库/WAL稳定且 integrity_check=ok 才在本机建立快照；不复用手机SHM锁。
python3 tools/things-probe/probe.py --pull --stats

# 授权向 DeepSeek 发送真实原话后运行
python3 tools/things-probe/probe.py --run real --jobs 4

# 20句独立自编难例
python3 tools/things-probe/probe.py --run cases --jobs 4
```

也支持 `--local-snapshot /明确指定的/capture.db --stats` 复制已有本机数据库及 WAL。报告必须明确该副本的截止时间，不能当作当前手机的完整数据；来源元数据保存在 `private/provenance.json`。老版本没有 `env_sounds` 时注明无法按卡 070 标记过滤，仍由同一模型提示词判断影视内容。

真实回放在当前最近七天窗口按原话 id 升序分批，每80条原话为一批，排除导入、疑似/标记媒体及已删除事件；上文为之前20条合格原话。模型没有关键词预过滤。已确认物品最多200样，按最近更新时间排序；离线产生的未确认结果不会自动成为下一批已知物品。

完成后 `results.md` 先生成逐条待审核表。人工对每条判断对错，并查看 `private/audit-candidates.json` 中含“放、搁、塞、借、拿走、在哪”等候选及上下文，记录明显漏检。报告中的准确率仅是命中精确率，不能冒充全量召回率。修改提示词要写明一般化根因，重新验证受影响数据，不写物品/单句特例。

`cases.md` 是已完成的20句实测及人工判定。`results.md` 会明确真实数据验证是否实际运行，未运行时没有准确率。
