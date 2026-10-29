package app.resound.digest

import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

/*
 * 卡 064 今日节奏的“平时”：界面（Claude）只依赖这里，底层（Codex）实现。改签名需双方确认。
 * 只用数得准的东西：原话句子的时长、事件数、整理里提到的人名、会议场景；不用声纹。媒体场景不计。
 */

/** 最近 7 天（不含当天）每项的中位数；有记录的天数不足 3 天时整体为 null */
data class RhythmBaseline(
    val days: Int,
    val speechMinutes: Int,
    val events: Int,
    val people: Int,
    val meetings: Int,
)

interface RhythmStats {
    /** [date] 之前 7 天的中位数；“有记录”指当天至少有一句非媒体的原话 */
    fun baseline(date: LocalDate): Flow<RhythmBaseline?>
}
