package app.resound.push

import app.resound.digest.AppointmentReminders
import org.json.JSONObject
import java.time.ZoneId

/** [strong]：到点那一条用强提醒（弹出、响铃、震动）；提前提醒和头天晚上的汇总都是普通通知 */
internal data class SubscriptionReminder(val key: String, val at: Long, val text: String, val keys: List<String> = listOf(key), val strong: Boolean = false)
internal object SubscriptionReminders {
    fun candidates(data: PushSnapshot, s: PushSubscription, sent: JSONObject,
                   settings: AppointmentReminders = AppointmentReminders(),
                   zone: ZoneId = ZoneId.systemDefault(), now: Long = System.currentTimeMillis()): List<SubscriptionReminder> {
        val outputTime = app.resound.OutputTime(zone)
        val events = data.events.filter { !it.deleted && (!s.excludeMedia || it.scene !in setOf("媒体", "旁人")) }
        val ids = events.map { it.id }.toSet()
        val items = data.items.filter { !it.deleted && (it.eventId == null || it.eventId in ids) && it.mergedInto == null && it.status == "Accepted" }
        val timed = items.filter { it.category in setOf("待办", "约定") && it.dueAt != null }
        // 记下（或改过、接受）这一条之前就已经过去的提醒时刻不再补发：如 5 分钟后的事，不该立刻弹“1 小时后开始”
        fun known(it: app.resound.data.MemoryItemEntity) = maxOf(it.createdAt, it.editedAt ?: 0L, it.statusChangedAt ?: 0L)
        fun identity(id: Long, due: Long?, version: Long) = if (version == 0L) "$id:$due" else "$id:$due:$version"
        val due = timed.filter { it.dueAt!! >= known(it) }.map {
            // 只有日期没有钟点的，手机上别半夜 0 点弹，改成当天早上 9 点
            val at = if (s == PushSubscription.PhoneNotice && (it.dueText?.length ?: 0) <= 10) it.dueAt!! + 9 * 3_600_000L else it.dueAt!!
            SubscriptionReminder("item:${identity(it.id, it.dueAt, it.reminderVersion)}", at, "待办到期 · I${it.id} ${outputTime.moment(it.dueAt!!)} ${it.content}", strong = it.strongReminder)
        }
        val lead = settings.leadMinutes?.let { minutes -> timed.filter { (it.dueText?.length ?: 0) > 10 && it.dueAt!! > now && it.dueAt!! - minutes * 60_000L > known(it) }.map {
            SubscriptionReminder("lead:${identity(it.id, it.dueAt, it.reminderVersion)}", it.dueAt!! - minutes * 60_000L,
                "即将开始 · ${outputTime.moment(it.dueAt!!)} ${it.content}")
        } }.orEmpty()
        // 按条目/时间版本记已发送，按日期合成一条；新增或改期不会重发已提醒过的其他条目。
        val evenings = if (!settings.eveningBefore) emptyList() else timed.groupBy { day(it.dueAt!!, zone) }.mapNotNull { (date, rows) ->
            if (date.atStartOfDay(zone).toInstant().toEpochMilli() <= now) return@mapNotNull null
            val eveningAt = date.minusDays(1).atStartOfDay(zone).plusMinutes(settings.eveningAt.toLong()).toInstant().toEpochMilli()
            val remaining = rows.filter { !sent.has("evening:${identity(it.id, it.dueAt, it.reminderVersion)}") && eveningAt > known(it) }.sortedBy { it.dueAt }
            if (remaining.isEmpty()) return@mapNotNull null
            val keys = remaining.map { "evening:${identity(it.id, it.dueAt, it.reminderVersion)}" }
            SubscriptionReminder("evening:$date", date.minusDays(1).atStartOfDay(zone).plusMinutes(settings.eveningAt.toLong()).toInstant().toEpochMilli(),
                "明天：" + remaining.joinToString("；") {
                    (if ((it.dueText?.length ?: 0) > 10) outputTime.moment(it.dueAt!!) + " " else "${day(it.dueAt!!, zone)} ") + it.content
                }, keys)
        }
        val stars = events.filter { it.starred && it.starredAt != null }.filter { e ->
            items.any { it.eventId == e.id && it.category in setOf("待办", "约定") } &&
                data.items.none { !it.deleted && it.eventId == e.id && maxOf(it.doneAt ?: 0L, it.statusChangedAt ?: 0L) > e.starredAt!! }
        }.map { SubscriptionReminder("star:${it.id}:${it.starredAt}", it.starredAt!! + 3 * 86_400_000L, "重点三天没进展 · E${it.id} ${it.title}") }
        return (due + lead + evenings + stars).filter { reminder -> reminder.keys.any { !sent.has(it) } }.sortedBy { it.at }
    }
}
