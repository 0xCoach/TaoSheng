package app.resound.skills.core

import kotlinx.datetime.LocalDate

/** firstDay 为首次启动日的 epoch day；完成凭据由平台持久化，恢复时沿用同一日期。 */
object MeetingBackfill207 {
    suspend fun run(
        firstDay: Long,
        completed: (Long) -> Boolean,
        complete: (Long) -> Boolean,
        runDay: suspend (LocalDate) -> Unit,
    ): Boolean {
        for (day in listOf(firstDay - 1, firstDay)) {
            if (completed(day)) continue
            runDay(LocalDate.fromEpochDays(day.toInt()))
            if (!complete(day)) return false
        }
        return true
    }
}
