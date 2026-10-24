package app.resound.push

import org.json.JSONObject

internal data class SubscriptionReminder(val key: String, val at: Long, val text: String)
internal object SubscriptionReminders {
    fun candidates(data: PushSnapshot, s: PushSubscription, sent: JSONObject): List<SubscriptionReminder> {
        val events = data.events.filter { !it.deleted && (!s.excludeMedia || it.scene !in setOf("媒体", "旁人")) }
        val ids = events.map { it.id }.toSet()
        val items = data.items.filter { it.eventId in ids && it.mergedInto == null && it.status in setOf("Pending", "Accepted") }
        val due = items.filter { it.category in setOf("待办", "约定") && it.dueAt != null }.map {
            SubscriptionReminder("item:${it.id}:${it.dueAt}", it.dueAt!!, "待办到期 · I${it.id} ${it.content}")
        }
        val stars = events.filter { it.starred && it.starredAt != null }.filter { e ->
            data.items.none { it.eventId == e.id && maxOf(it.doneAt ?: 0L, it.statusChangedAt ?: 0L) > e.starredAt!! }
        }.map { SubscriptionReminder("star:${it.id}:${it.starredAt}", it.starredAt!! + 3 * 86_400_000L, "重点三天没进展 · E${it.id} ${it.title}") }
        return (due + stars).filter { !sent.has(it.key) }.sortedBy { it.at }
    }
}
