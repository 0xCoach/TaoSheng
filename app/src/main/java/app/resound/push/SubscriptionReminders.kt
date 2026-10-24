package app.resound.push

import app.resound.digest.AppointmentReminders
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal data class SubscriptionReminder(val key: String, val at: Long, val text: String, val keys: List<String> = listOf(key))
internal object SubscriptionReminders {
    fun candidates(data: PushSnapshot, s: PushSubscription, sent: JSONObject,
                   settings: AppointmentReminders = AppointmentReminders(),
                   zone: ZoneId = ZoneId.systemDefault(), now: Long = System.currentTimeMillis()): List<SubscriptionReminder> {
        val events = data.events.filter { !it.deleted && (!s.excludeMedia || it.scene !in setOf("媒体", "旁人")) }
        val ids = events.map { it.id }.toSet()
        val items = data.items.filter { !it.deleted && it.eventId in ids && it.mergedInto == null && it.status == "Accepted" }
        val timed = items.filter { it.category in setOf("待办", "约定") && it.dueAt != null }
        fun identity(id: Long, due: Long?, version: Long) = if (version == 0L) "$id:$due" else "$id:$due:$version"
        val due = timed.map {
            SubscriptionReminder("item:${identity(it.id, it.dueAt, it.reminderVersion)}", it.dueAt!!, "待办到期 · I${it.id} ${it.content}")
        }
        val lead = settings.leadMinutes?.let { minutes -> timed.filter { (it.dueText?.length ?: 0) > 10 && it.dueAt!! > now }.map {
            SubscriptionReminder("lead:${identity(it.id, it.dueAt, it.reminderVersion)}", it.dueAt!! - minutes * 60_000L,
                "即将开始 · ${it.dueText} ${it.content}")
        } }.orEmpty()
        // 按条目/时间版本记已发送，按日期合成一条；新增或改期不会重发已提醒过的其他条目。
        val evenings = if (!settings.eveningBefore) emptyList() else timed.groupBy { day(it.dueAt!!, zone) }.mapNotNull { (date, rows) ->
            if (date.atStartOfDay(zone).toInstant().toEpochMilli() <= now) return@mapNotNull null
            val remaining = rows.filter { !sent.has("evening:${identity(it.id, it.dueAt, it.reminderVersion)}") }.sortedBy { it.dueAt }
            if (remaining.isEmpty()) return@mapNotNull null
            val keys = remaining.map { "evening:${identity(it.id, it.dueAt, it.reminderVersion)}" }
            SubscriptionReminder("evening:$date", date.minusDays(1).atStartOfDay(zone).plusMinutes(settings.eveningAt.toLong()).toInstant().toEpochMilli(),
                "明天：" + remaining.joinToString("；") {
                    (if ((it.dueText?.length ?: 0) > 10) Instant.ofEpochMilli(it.dueAt!!).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm")) + " " else "") + it.content
                }, keys)
        }
        val stars = events.filter { it.starred && it.starredAt != null }.filter { e ->
            items.any { it.eventId == e.id && it.category in setOf("待办", "约定") } &&
                data.items.none { !it.deleted && it.eventId == e.id && maxOf(it.doneAt ?: 0L, it.statusChangedAt ?: 0L) > e.starredAt!! }
        }.map { SubscriptionReminder("star:${it.id}:${it.starredAt}", it.starredAt!! + 3 * 86_400_000L, "重点三天没进展 · E${it.id} ${it.title}") }
        return (due + lead + evenings + stars).filter { reminder -> reminder.keys.any { !sent.has(it) } }.sortedBy { it.at }
    }
}
