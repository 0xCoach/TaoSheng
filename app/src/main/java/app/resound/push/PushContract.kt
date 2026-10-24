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
    /** 每日记忆文档是否附上当天原话（默认不附） */
    val memoryWithLines: Boolean = false,
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

    fun delete(id: Long)

    /** 发一条测试消息；成功返回给人看的一句话 */
    suspend fun test(id: Long): Result<String>

    /** 最近的推送记录（新的在前） */
    fun logs(limit: Int = 50): Flow<List<PushLog>>
}
