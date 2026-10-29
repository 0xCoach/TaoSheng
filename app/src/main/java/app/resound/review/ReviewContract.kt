package app.resound.review

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate

/*
 * 每日回顾与界面之间的约定。界面（Claude）只依赖这里；底层（Codex）实现。改签名需双方确认。
 */

/** 回顾里的一个栏目：一句话 / 要紧的 / 发生了什么 / 待办 / 值得留意 / 灵感 / 心力。空栏目不出现 */
data class ReviewSection(
    val title: String,
    /** 每条一句话 */
    val lines: List<String>,
    /** 每条对应的事件 id（和 lines 一一对应，没有出处为 null），界面点了能跳到那件事 */
    val eventIds: List<Long?>,
)

data class DailyReview(
    val date: LocalDate,
    /** 推送用的简洁版（≤ 60 字） */
    val brief: String,
    /** App 内的详细版，按固定顺序，空栏目已去掉 */
    val sections: List<ReviewSection>,
    val generatedAt: Long,
)

data class ReviewState(
    val generating: Boolean,
    val lastError: String?,
    /** 每天几点生成并推送，默认 22:00（分钟数，如 22*60） */
    val dailyAtMinutes: Int,
    val pushEnabled: Boolean,
    /** 卡 058：正在生成时当前这一步，如“正在整理还没处理的录音…”“正在写回顾…”；不在生成为 null */
    val stage: String? = null,
    /** 卡 058：[lastError] 属于哪一天的回顾（界面只在那天显示）；没有错误为 null */
    val lastErrorDate: LocalDate? = null,
)

interface ReviewRepository {
    /** 某天的回顾；还没生成为 null */
    fun daily(date: LocalDate): Flow<DailyReview?>

    /** 最近的回顾（新的在前，最多 30 天），给记忆页“回顾”分类用 */
    fun recent(): Flow<List<DailyReview>>

    val state: StateFlow<ReviewState>

    /** 立即（重新）生成某天的回顾，不推送 */
    fun generateNow(date: LocalDate)

    /** 卡 058：这天的回顾窗口（上一日截止时刻，当日截止时刻]里有没有能写进回顾的内容；与生成时的判断完全一致。界面据此决定显示“生成回顾” */
    fun hasMaterial(date: LocalDate): Flow<Boolean>

    /** 卡 058：这天回顾覆盖的时间段，给用户看，如“10月28日 22:00 – 10月29日 22:00” */
    fun windowText(date: LocalDate): String

    fun setDailyAt(minutes: Int)

    fun setPushEnabled(enabled: Boolean)
}
