package app.resound.backup

import kotlinx.serialization.json.*

/** 设置与运行状态的跨平台分类；同一文件混合存储时按键过滤，恢复旧包也执行同一规则。 */
object PreferenceBackupPolicy {
    val settings = mapOf(
        "marks" to "新打点前后范围（毫秒）",
        "transcribe" to "识别来源、模型、语言、热词、间隔及网络偏好；加密字段另走密钥导出",
        "local-asr" to "本地模型用户选项；排除本机 NPU 检查和下载状态",
        "capture" to "录音用户配置及扩展键；仅排除明确的心跳、暂停、故障和恢复标记",
        "ui" to "主题、显示、复制和已完成向导等界面偏好",
        "review" to "回顾时间和推送开关；排除最近失败",
        "push" to "通道及订阅配置；排除发送、重试、订阅游标和诊断",
        "diary" to "日记云同步开关",
        "digest" to "整理、备用模型、约定提醒设置；排除时间点完成凭据",
        "backup" to "本机/云端目的地、保留份数和自动备份设置；排除统计和错误",
        "ask-selection" to "用户选择的问答模型",
        "usage" to "用户填写的计费单价",
        "inputs" to "用户选择的录音来源、蓝牙播放让出及手机续录选项；系统设备授权仍需新手机重新确认",
        "voice-input" to "语音输入开关、排除应用和截屏识字选项",
        "llm-providers" to "模型服务商及当前选择",
        "llm-balance" to "余额阈值；排除余额缓存和已提醒标记",
        "asr-speed" to "分人和课堂模式的用户变更历史，供原录音按当时设置解释",
        "env-sound" to "环境音过滤选项及用户启用时间边界，避免分析启用前录音",
        "redo-choices" to "用户上次选择的识别来源和整理模型",
        "import-kinds" to "用户上次选择的导入类型",
        "app-update" to "自动更新、预览通道及用户跳过/稍后选择；排除下载与检查缓存",
        "folder-imports" to "用户选择的监听目录和导入类型；排除扫描、权限和导入计数",
        "devices" to "记住的设备及解释旧录音、补传所需的校时偏移和收音缺口",
        "device-cards" to "仅用户启用的说明卡；排除远端说明卡缓存和下载时间",
        "device-drivers" to "设备选择和插件私有持久数据（含凭据/进度）；仅排除本机状态展示缓存",
        "location" to "定位和地点解析开关",
        "quick_prefs" to "快捷暂停时长和课堂类型",
        "device-ui" to "用户确认过的关灯提示",
        "playback-title" to "记录播放标题开关及开启时刻",
    )
    val secretPrefs = setOf(
        "transcribe", "digest-secrets", "push-secrets", "backup-secrets",
        "transcribe-header-secrets", "location-secrets", "backup-encryption-secrets",
    )
    val excluded = mapOf(
        "onboarding" to "首次转写、向导计时、诊断计数及提示已读均是本机引导状态，沿用既有不备份约定",
        "ask-compatibility" to "服务端能力探测缓存和失败冷却",
        "ask-suggestions" to "问答推荐缓存",
        "backup-diagnostics" to "本机备份诊断",
        "diary-sync" to "日记上传失败状态",
        "digest-state" to "进行中整理和防重复付费状态，不属于用户设置",
        "export-jobs" to "导出任务队列及进度",
        "export-speeds" to "本机导出测速缓存",
        "home-notices" to "本机后台受限提示的关闭时间，与当前手机诊断绑定",
        "imports" to "导入任务及本机路径队列",
        "local-asr-process" to "本机识别进程连接和诊断",
        "llm-diagnostics" to "模型调用诊断和最近异常",
        "phone-reminders" to "手机提醒去重和发送状态",
        "pipeline-runs" to "自动转写/整理每轮执行记录（诊断）",
        "pipeline-schedule" to "流水线到期/重试状态，属于本机运行状态",
        "pipeline-times" to "流水线和片段诊断记录",
        "platform" to "平台 KeyValueStore 预留；当前没有用户配置键，今后使用需重新审查",
        "privacy-spool" to "待删除录音的本机路径暂存",
        "problem-reports" to "本机闪退提示已读标记",
        "push-retries" to "发送失败的发件箱队列，恢复后不能重发旧消息",
        "recording-pack" to "录音打包任务及本机 URI",
        "redo-jobs" to "重做任务和执行进度",
        "segment-codec" to "本机编码器兼容性探测和崩溃标记",
        "setup" to "本机后台权限的人工确认；不能代替新手机授权",
        "strong-reminders" to "正在响铃的提醒内容和重复响铃状态",
        "things" to "寻物历史扫描初始化水位",
        "transcribe-queue" to "上次转写运行时间与错误",
        "update-popup" to "当前版本更新弹窗去重时间，真正的跳过/稍后设置在 app-update",
        "update-times" to "更新检查和安装诊断",
        "screen-time" to "旧版屏幕时间开关，本版本不采集、不备份",
    )
    val backedUp: Set<String> get() = settings.keys + secretPrefs
    val asrKeys = setOf("encryptedKey", "customEncryptedKey")

    /** 只解密生产代码使用的凭据键；容器内其他元数据不是密文，不能尝试 Base64 解码。 */
    fun secret(store: String, key: String): Boolean = when (store) {
        "transcribe" -> key in asrKeys
        "digest-secrets" -> key.startsWith("key-") || key.startsWith("headers-") || key == "opencode-session"
        "transcribe-header-secrets" -> key.startsWith("headers-") || key == "opencode-session"
        "push-secrets" -> uuidReference(key)
        "backup-secrets" -> key in setOf("password", "webDavPassword", "s3Secret")
        "location-secrets" -> key == "amap"
        "backup-encryption-secrets" -> key == "passphrase"
        else -> false
    }

    private fun uuidReference(key: String): Boolean = key.length == 36 && key.indices.all { index ->
        if (index in setOf(8, 13, 18, 23)) key[index] == '-'
        else key[index] in "0123456789abcdefABCDEF"
    }

    fun setting(store: String, key: String): Boolean {
        if (store !in settings || store == "transcribe" && key in asrKeys) return false
        return when (store) {
            "capture" -> key !in setOf(
                "heartbeat", "unclean", "pausedUntil", "manualPausedUntil", "quietName", "quietSkipUntil", "lastSegmentError",
            )
            "review" -> key in setOf("dailyAtMinutes", "pushEnabled")
            "push" -> key == "channels"
            "digest" -> !key.startsWith("timed")
            "device-cards" -> key.startsWith("enabled:")
            // DriverStore 的键由插件定义，可能是授权、解码或续做必需数据，不能在平台层猜测或白名单丢弃。
            "device-drivers" -> !key.endsWith("/s/lastStatus")
            "redo-choices" -> key in setOf("asr", "llm")
            "app-update" -> key in setOf("autoOff", "previewChannel", "skipVersion", "laterVersion", "laterUntil")
            "backup" -> !key.startsWith("base-") && key !in setOf(
                "lastAt", "lastBytes", "count", "error", "localLastAt", "localLastBytes", "localCount", "localError", "localFolderError",
            )
            "local-asr" -> key !in setOf("actual", "npuCheck") && !key.startsWith("download-")
            "llm-balance" -> !key.startsWith("cached") && !key.startsWith("alerted")
            "folder-imports" -> key == "folders"
            else -> true
        }
    }

    /** 结构里的用户选择仍保留；旧包也剥离本机权限/计时/扫描状态。 */
    fun stringValue(store: String, key: String, value: String): String {
        val removed = when {
            store == "folder-imports" && key == "folders" ->
                setOf("accessible", "lastScanAt", "imported", "error", "baseline")
            else -> return value
        }
        val rows = Json.parseToJsonElement(value).jsonArray
        return JsonArray(rows.map { row -> JsonObject(row.jsonObject.filterKeys { it !in removed }) }).toString()
    }
}
