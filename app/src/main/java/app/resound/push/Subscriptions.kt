package app.resound.push

import org.json.JSONArray
import org.json.JSONObject
import java.time.ZonedDateTime

internal object Subscriptions {
    fun migrate(topics: Set<PushTopic>, lines: Boolean) = SubscriptionRules.migrate(topics, lines)
    fun topics(s: PushSubscription) = SubscriptionRules.topics(s)
    fun tighten(s: PushSubscription, c: PushCapabilities) = SubscriptionRules.tighten(s, c)
    fun json(s: PushSubscription) = JSONObject(SubscriptionJson.encode(s).toString())
    fun read(j: JSONObject) = SubscriptionJson.read(app.resound.platform.SharedJson.parseToJsonElement(j.toString()) as kotlinx.serialization.json.JsonObject)
    fun nextSchedule(t: RecordTrigger.Schedule, after: ZonedDateTime): Long =
        SubscriptionRules.nextSchedule(t, after.toInstant().toEpochMilli(), after.zone.id)
    fun changeAt(t: RecordTrigger, last: Long?, now: Long) = SubscriptionRules.changeAt(t, last, now)
}
internal fun JSONArray.strings() = (0 until length()).map { get(it).toString() }
internal fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
