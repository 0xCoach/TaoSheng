package app.resound.push

import kotlinx.datetime.*

object PushRecordRules {
    fun lower(s: PushSubscription, since: LocalDate?, today: LocalDate): LocalDate? =
        listOfNotNull(since, s.rangeDays?.let { today.minus(it - 1, DateTimeUnit.DAY) }).maxOrNull()
    fun event(deleted: Boolean, scene: String, excludeMedia: Boolean) = !deleted && (!excludeMedia || scene != "媒体")
    fun item(s: PushSubscription, category: String, status: String, starred: Boolean, doneDate: LocalDate?, today: LocalDate): Boolean =
        starred || when (category) {
            "待办", "约定", "备忘" -> RecordKind.Todos in s.records && (status != "Done" || doneDate?.let { it >= today.minus(29, DateTimeUnit.DAY) } == true)
            "灵感" -> RecordKind.Ideas in s.records
            else -> RecordKind.Decisions in s.records
        }
    fun line(mode: LinesMode, speaker: String?, text: String) = (mode != LinesMode.MeOnly || speaker == "我") && text.isNotBlank()
}
