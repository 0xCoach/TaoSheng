package app.resound.capture

import java.time.DayOfWeek
import kotlinx.coroutines.flow.StateFlow

/*
 * 卡 056 停录时段：界面与底层之间的约定。界面（Claude）只依赖这里的类型；底层（Codex）负责实现。
 * 改动这里的签名需要双方确认。
 */

/** 一条停录时段。[endMinute] 小于等于 [startMinute] 表示跨午夜（如 23:00–7:00）；[days] 按开始那天算。 */
data class QuietRule(
    val id: String,
    /** 给用户看的名字，如“睡眠” */
    val name: String,
    /** 一天里的分钟数 0..1439 */
    val startMinute: Int,
    val endMinute: Int,
    val days: Set<DayOfWeek> = DayOfWeek.entries.toSet(),
    val enabled: Boolean = true,
)

/** 当前正处在的停录时段 */
data class QuietWindow(
    val rule: QuietRule,
    /** 这一段结束、自动恢复录音的时间（毫秒时间戳） */
    val endsAt: Long,
)

interface QuietSchedule {
    /** 全部规则；首次安装预置一条 id = [SLEEP_ID] 的“睡眠 23:00–7:00”，默认关闭 */
    val rules: StateFlow<List<QuietRule>>

    /** 正处在哪一段；不在任何启用的时段、或本段已提前恢复，为 null */
    val active: StateFlow<QuietWindow?>

    /** 按 id 新增或替换；保存后立刻按新规则判断当前是否该停录 */
    fun save(rule: QuietRule)

    fun delete(id: String)

    companion object {
        const val SLEEP_ID = "sleep"
    }
}
