package app.resound.usage

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate

/*
 * 用量（卡 043）与界面之间的约定。界面（Claude）只依赖这里；底层（Codex）实现。改签名需双方确认。
 */

/** 大模型用在哪 */
enum class UsagePurpose(val title: String) {
    Digest("整理"), Ask("问问"), Review("回顾"), Skill("技能"), Draft("智能生成"),

    /** 分用途记录之前的老数据，以及其他零散调用 */
    Other("其他"),
}

/** 某天某用途的大模型用量 */
data class LlmUsageRow(val date: LocalDate, val purpose: UsagePurpose, val promptTokens: Long, val completionTokens: Long, val calls: Int)

/** 某天云端转写用了多久（本地转写不计） */
data class AsrUsageRow(val date: LocalDate, val cloudMillis: Long)

/** 单价（人民币），用来估算花费；null 表示没设，界面不显示花费 */
data class UsagePrice(
    /** 输入每百万 token */
    val promptPerMillion: Double? = null,
    /** 输出每百万 token */
    val completionPerMillion: Double? = null,
    /** 云端转写每小时 */
    val asrPerHour: Double? = null,
)

interface UsageRepository {
    /** from..to（含两端）每天每用途的大模型用量；没有用量的天不返回 */
    fun llm(from: LocalDate, to: LocalDate): Flow<List<LlmUsageRow>>

    /** from..to 每天云端转写时长；没有的天不返回 */
    fun asr(from: LocalDate, to: LocalDate): Flow<List<AsrUsageRow>>

    val price: StateFlow<UsagePrice>

    fun setPrice(price: UsagePrice)
}
