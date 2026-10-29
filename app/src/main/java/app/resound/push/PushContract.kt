package app.resound.push

import kotlinx.coroutines.flow.Flow

/*
 * 推送通道与界面之间的约定。界面（Claude）只依赖这里；底层（Codex）实现。
 * 密钥类字段只进系统安全区，读回界面时只给脱敏摘要。
 */

enum class PushKind(val title: String) {
    // 顺序即界面顺序（按名字存储，调顺序不影响已存的通道）：消息推送 → 文档与笔记 → 自定义接口放最后
    PushPlus("PushPlus"), WeCom("企业微信机器人"), ServerChan("Server酱"), Feishu("飞书机器人"), DingTalk("钉钉机器人"), Telegram("Telegram"),
    Email("邮件"), Notion("Notion"), MarkdownFolder("坚果云同步文件夹"),
    Webhook("自定义接口（Webhook）"),
}

/** 推什么 */
enum class PushTopic(val title: String) {
    DailyReview("每日回顾"), TodoReminder("待办提醒"), NewEvent("新整理的事"), SkillCard("技能卡片"),

    /** 每晚生成回顾后，推当天完整的记忆文档（Markdown + 结构化数据），给用户自己的 Agent 再加工 */
    DailyMemory("每日记忆文档"),
}

enum class PushFormat(val title: String) { Text("纯文本"), Markdown("Markdown"), Json("JSON") }

/** 某种通道要填的项 */
data class PushField(val key: String, val label: String, val hint: String, val secret: Boolean)

data class PushChannel(
    val id: Long,
    val kind: PushKind,
    val name: String,
    val enabled: Boolean,
    val topics: Set<PushTopic>,
    val format: PushFormat,
    /** 脱敏后的配置摘要，如“token ••••3f9a” */
    val summary: String,
    /** 每日记忆文档是否附上当天原话（默认不附）；卡 042 后由 subscription.lines 取代，保留兼容 */
    val memoryWithLines: Boolean = false,
    /** 推送订阅（卡 042） */
    val subscription: PushSubscription = PushSubscription(),
    /** 坚果云同步文件夹：用数据备份里已连接的坚果云账号（不用另填账号密码） */
    val useLinkedNutstore: Boolean = false,
    /** 已保存的非密钥配置（地址、Chat ID、文件夹等），编辑时原样填回，改格式不用重填；密钥不在这里 */
    val plainValues: Map<String, String> = emptyMap(),
)

// ---------- 推送订阅（卡 042）：每个通道单独设置 推什么 / 什么时候推 / 增量还是全量 / 推成什么样 ----------

/** 整理结果里可选的几类 */
enum class RecordKind(val title: String) {
    Timeline("时间线"), Todos("待办与约定"), Ideas("灵感"), Decisions("决定与关键信息"), Starred("我的重点"),
}

/** 原话：默认不推 */
enum class LinesMode(val title: String) { Off("不推"), MeOnly("只推我说的"), All("全部") }

/** 整理结果和原话什么时候推（摘要类生成即推、提醒类到点推，不受这里影响） */
sealed interface RecordTrigger {
    /** 有新内容就推；两次之间至少隔 minGapMinutes 分钟 */
    data class OnChange(val minGapMinutes: Int = 30) : RecordTrigger

    /** 按时间推：每天这些时刻（一天里的分钟数）；weekdays 空表示每天，否则只在这些星期几（1=周一…7=周日） */
    data class Schedule(val times: List<Int>, val weekdays: Set<Int> = emptySet()) : RecordTrigger

    /** 只在点“现在推一次”时推 */
    data object Manual : RecordTrigger
}

/** 文件夹类通道怎么放文件 */
enum class FolderLayout(val title: String) { ByCategory("按类别分文件"), ByDay("一天一个文件") }

data class PushSubscription(
    /** 摘要类：每日回顾、每周回顾、技能卡片 */
    val summaries: Boolean = true,
    /** 提醒类：待办到期、标星 3 天没进展 */
    val reminders: Boolean = false,
    /** 整理结果里推哪几类；空 = 不推整理结果 */
    val records: Set<RecordKind> = emptySet(),
    val lines: LinesMode = LinesMode.Off,
    val trigger: RecordTrigger = RecordTrigger.OnChange(),
    /** true 增量（只推变化，带新增 / 修改 / 删除）；false 每次全量 */
    val incremental: Boolean = true,
    /** 每天全量校对一次的时刻（分钟数）；null 不校对。只对增量有意义 */
    val fullCheckAt: Int? = 22 * 60,
    val layout: FolderLayout = FolderLayout.ByCategory,
    /** 只推最近多少天；null 不限 */
    val rangeDays: Int? = null,
    /** 排除电视、视频等媒体和旁人的内容 */
    val excludeMedia: Boolean = true,
    /** 第一次开启时补多少天：null 全部，0 从今天开始；默认 30 */
    val backfillDays: Int? = 30,
    /** 卡 048：推原话时带上语音输入的上下文（别人的消息、AI 回复等） */
    val lineContext: Boolean = true,
) {
    companion object {
        /** 一键模板：选了再细调 */
        val PhoneNotice = PushSubscription(summaries = true, reminders = true, records = emptySet(), incremental = false, fullCheckAt = null)
        val ForAgents = PushSubscription(summaries = true, reminders = false, records = RecordKind.entries.toSet(),
            lines = LinesMode.Off, trigger = RecordTrigger.OnChange(30), incremental = true, fullCheckAt = 22 * 60, layout = FolderLayout.ByCategory)
        val FullArchive = PushSubscription(summaries = true, reminders = false, records = RecordKind.entries.toSet(),
            lines = LinesMode.All, trigger = RecordTrigger.Schedule(listOf(22 * 60)), incremental = false, fullCheckAt = null, layout = FolderLayout.ByDay)
    }
}

/** 这种通道支持什么，界面据此只显示能用的选项 */
data class PushCapabilities(
    /** 能推整理结果和原话（聊天类不能，只能摘要和提醒） */
    val records: Boolean,
    /** 支持增量 */
    val incremental: Boolean,
    /** 有文件夹布局可选 */
    val folder: Boolean,
    val formats: Set<PushFormat>,
)

/** 推送状态，通道详情里显示 */
data class PushChannelStatus(
    val lastPushAt: Long?,
    /** 上次推了什么，如“更新 3 个文件” */
    val lastResult: String?,
    val lastError: String?,
    /** 正在推（包括“全部重新生成”） */
    val running: Boolean,
)

data class PushLog(val id: Long, val channelId: Long, val channelName: String, val at: Long, val topic: PushTopic?, val title: String, val ok: Boolean, val error: String?)

interface PushChannels {
    val channels: Flow<List<PushChannel>>

    fun fields(kind: PushKind): List<PushField>

    /** 这种通道能推哪些内容；聊天类通道有长度限制，不支持每日记忆文档 */
    fun topics(kind: PushKind): Set<PushTopic>

    /** id 为 null 时新建。values 里密钥字段留空表示“不改原值”（编辑时） */
    suspend fun save(id: Long?, kind: PushKind, name: String, values: Map<String, String>, topics: Set<PushTopic>, format: PushFormat,
                     memoryWithLines: Boolean = false): Result<Long>

    fun setEnabled(id: Long, enabled: Boolean)

    // ---------- 卡 042 ----------

    fun capabilities(kind: PushKind): PushCapabilities

    /** 已连接的坚果云账号（数据备份里的）；没连为 null。坚果云同步文件夹可以一键用它 */
    val linkedNutstoreAccount: Flow<String?>

    /**
     * 新建或修改通道（含订阅）。useLinkedNutstore=true 时 values 里账号密码可以不填。
     * 文件夹已被别的通道占用时返回失败（“这个文件夹已经给“XX”通道用了，换个名字”）。
     */
    suspend fun saveWithSubscription(id: Long?, kind: PushKind, name: String, values: Map<String, String>,
                                     subscription: PushSubscription, format: PushFormat, useLinkedNutstore: Boolean): Result<Long>

    /** 现在推一次（按订阅推整理结果和原话；增量通道只推变化） */
    suspend fun pushNow(id: Long): Result<String>

    /** 全部重新生成：文件夹类清空回响写的文件后整个重建（my-notes/ 不碰） */
    suspend fun rebuild(id: Long): Result<String>

    fun status(id: Long): Flow<PushChannelStatus>

    fun delete(id: Long)

    /** 发一条测试消息；成功返回给人看的一句话 */
    suspend fun test(id: Long): Result<String>

    /** 最近的推送记录（新的在前） */
    fun logs(limit: Int = 50): Flow<List<PushLog>>
}
