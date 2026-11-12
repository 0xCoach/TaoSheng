package app.resound.settingsearch

class DefaultSettingsSearch : SettingsSearch {
    override val entries: List<SettingsEntry> = SettingsIndex.entries

    override fun search(query: String, limit: Int): List<SettingsEntry> {
        val needle = normalize(query)
        if (needle.isEmpty() || limit <= 0) return emptyList()
        return entries.mapIndexedNotNull { index, entry ->
            val title = normalize(entry.title)
            val keywords = entry.keywords.map(::normalize)
            val score = when {
                title == needle -> 0
                title.startsWith(needle) -> 1
                needle in title -> 2
                keywords.any { it == needle } -> 3
                keywords.any { needle in it } -> 4
                else -> return@mapIndexedNotNull null
            }
            Triple(score, index, entry)
        }.sortedWith(compareBy({ it.first }, { it.second })).take(limit).map { it.third }
    }

    private fun normalize(text: String) = text.filterNot(Char::isWhitespace).lowercase()
}

/** 页面行在前，页内常用选项随后；同分时保留这个顺序。 */
internal object SettingsIndex {
    val entries = buildList {
        fun page(key: String, title: String, words: String, options: SettingsPageIndex.() -> Unit) {
            add(SettingsEntry(title, key, title, keywords = words.split(' ')))
            SettingsPageIndex(key, title, this).options()
        }
        page("Capture", "录音与后台", "收音 听声音 麦克风 录音设置") {
            item("后台保活", "battery", "耗电 电池 省电 被杀 中断 后台")
            item("录音权限", "permissions", "授权 麦克风权限 通知权限 录不了")
            item("免打扰时段", "quiet", "不录 静音 睡觉 午休 勿扰 停录时段")
            item("暂停时长", "pause-length", "暂停多久 半小时 一小时 两小时")
            item("录音输入", "input", "手机麦克风 蓝牙耳机 领夹麦 Type-C 切换麦克风")
            item("语音输入记录", "voice-input", "输入法 无障碍 微信打字 口述")
            item("外放跳过", "playback", "听歌 看视频 扬声器 外放不录")
        }
        page("Transcript", "原话与转写", "文字 识别 听写 转文字") {
            item("转写服务", "asr", "豆包 SenseVoice 本地转写 云端转写")
            item("本地模型", "local-model", "模型下载 离线 没网 NPU 加速")
            item("转写间隔", "asr-interval", "多久转一次 实时转写 等文字 转写时间")
            item("失败重试", "retry", "没转成 转写失败 重转 卡住")
            item("热词识别", "hotwords", "热词 纠错 错字 人名 术语")
            item("原声保留", "audio-retention", "录音保留 音频删除 存多久 空间")
            item("区分说话人", "speakers", "多人分段 谁说的 分人 说话人")
            item("文件夹导入", "folder-import", "自动导入 扫描文件夹 导入录音 外部录音")
        }
        page("EnvSound", "环境音识别", "背景声音 声音分类 环境识别") {
            item("开启环境音识别", "env-enabled", "环境模型 环境开关 声音模型")
            item("跳过纯音乐和环境音", "skip-noise", "音乐 噪音 不转写 鸟叫")
            item("疑似影视外放", "tv-audio", "电视剧 电影 配乐 外放人声")
            item("记下环境", "env-tags", "下雨 键盘 宠物 车流")
            item("记下特别声音", "special-sounds", "婴儿哭 敲门 狗叫 警笛")
            item("在卡片和回顾里显示", "env-summary", "显示环境 隐藏环境 回顾声音")
        }
        page("Location", "足迹", "位置 定位 去过哪里 轨迹") {
            item("记录全天位置", "location-enabled", "位置开关 GPS 行踪")
            item("定位权限", "location-permission", "始终允许 后台定位 授权位置")
            item("显示地名", "place-names", "高德 地址 地理编码 地名")
            item("常去地点", "places", "家 公司 地点名称 停留")
        }
        page("Devices", "外接设备", "录音卡 硬件 外设 蓝牙设备") {
            item("扫描设备", "device-scan", "找设备 连接 配对 蓝牙扫描")
            item("设备说明卡", "device-cards", "驱动 插件 导入说明卡 支持设备")
            item("设备录音同步", "device-sync", "同步进度 导入设备录音 补传 离线录音")
        }
        page("Model", "整理模型", "模型 大模型 DeepSeek 密钥 余额 充值") {
            item("服务商与密钥", "providers", "APIKey key 接口 换模型 服务商")
            item("余额提醒", "balance", "多少钱 没钱 余额不足 提醒线 充值")
            item("什么时候整理", "digest-timing", "整理间隔 定时整理 几点整理 多久整理一次")
            item("停多久算说完了", "conversation-gap", "对话停顿 合并对话 说完 整理等待")
            item("整理前先思考", "thinking", "深度思考 推理 reasoning 思考开关")
            item("自动切换服务商", "failover", "备用模型 失败切换 故障转移 备用密钥")
            item("整理时参考地点和环境音", "context", "场景 地点参考 环境参考 上下文")
        }
        page("Skills", "整理技能", "技能 卡片 自动提取 智能整理") {
            item("开启整理技能", "skill-enabled", "技能开关 启用技能 关闭技能")
            item("导入技能", "skill-import", "安装技能 自定义技能 导入文件")
            item("技能运行时间", "skill-timing", "技能定时 什么时候运行 自动生成")
            item("技能模型与推送", "skill-model", "技能模型 生成后推送 单独模型")
        }
        page("Push", "推送通道", "发送 通知 消息 同步") {
            item("添加推送通道", "push-channels", "微信 飞书 钉钉 邮件 Webhook")
            item("同步文件夹", "sync-folder", "智能体 文件同步 WebDAV 本地文件夹")
            item("推送内容", "push-content", "原话推送 只推我说的 记忆文档 录音上传")
            item("最近推送", "push-history", "发送失败 推送记录 测试推送")
        }
        page("Voiceprint", "声纹与人物", "认人 说话人 是谁 名字 声纹") {
            item("录入声音样本", "voice-samples", "认识我 朗读样本 录声纹 学习声音")
            item("人物管理", "people", "改名字 添加人物 合并人物 人物详情")
            item("重新认一遍", "recognize-again", "重新识别 认错人 重跑声纹")
            item("样本声音", "sample-audio", "样本回放 清除样本音频 声音空间")
        }
        page("Review", "每日回顾", "日记 总结 每天回顾 一天总结") {
            item("每天几点生成", "review-time", "回顾时间 晚间总结 几点总结")
            item("回顾推送通知", "review-push", "总结通知 回顾消息 自动推送")
            item("手动生成回顾", "review-now", "重新总结 补回顾 生成昨天回顾")
        }
        page("Backup", "数据备份", "保存数据 恢复 换手机 防丢") {
            item("本机备份位置", "local-folder", "下载目录 备份文件夹 本地备份")
            item("云端存储", "cloud-storage", "坚果云 S3 WebDAV 阿里云 网盘")
            item("每晚自动备份", "backup-auto", "自动保存 凌晨备份 定时备份")
            item("立即备份", "backup-now", "马上保存 备份一次 手动备份")
            item("备份加密", "backup-encryption", "备份密码 口令 加密")
            item("历史版本保留", "backup-keep", "保留份数 清理备份 恢复点")
            item("恢复数据", "restore", "导入备份 找回数据 换机恢复")
        }
        page("Advanced", "高级与诊断", "排查 故障 日志 高级设置") {
            item("整理要求", "prompts", "整理指令 提示词 prompt 语气 整理规则")
            item("运行记录", "runs", "任务记录 处理记录 整理失败")
            item("守护记录", "watchdog", "被系统杀 重启记录 中断记录 自动恢复")
            item("麦克风占用", "mic-busy", "谁占麦 其他应用 没声音 占用记录")
            item("导出日志", "export-logs", "问题记录 闪退 反馈 诊断文件")
            item("日间 / 夜间", "appearance", "主题 深色模式 浅色模式 跟随系统 外观")
        }
        page("Update", "版本更新", "升级 新版本 下载更新") {
            item("检查更新", "check-update", "最新版 检查版本 手动更新")
            item("自动检查更新", "auto-update", "自动升级 更新开关 后台检查")
            item("跳过这个版本", "skip-version", "暂不更新 忽略版本 不升级")
        }
        page("About", "关于涛声", "关于 版本信息 帮助") {
            item("隐私说明", "privacy", "数据安全 密钥安全 上传哪些")
            item("项目主页", "homepage", "开源 GitHub 源码 MIT")
            item("接收预览版", "preview", "测试版 抢先体验 preview 内测")
        }
    }

    private class SettingsPageIndex(val page: String, val title: String, val target: MutableList<SettingsEntry>) {
        fun item(name: String, anchor: String, words: String) { target += SettingsEntry(name, page, title, anchor, words.split(' ')) }
    }
}
