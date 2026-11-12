package app.resound.push

import kotlinx.serialization.json.*

object SubscriptionJson {
    fun encode(s: PushSubscription): JsonObject = buildJsonObject {
        put("summaries", s.summaries); put("reminders", s.reminders)
        put("records", JsonArray(s.records.sortedBy { it.ordinal }.map { JsonPrimitive(it.name) })); put("lines", s.lines.name)
        put("trigger", buildJsonObject {
            when (val t = s.trigger) {
                is RecordTrigger.OnChange -> { put("type", "change"); put("gap", t.minGapMinutes) }
                is RecordTrigger.Schedule -> {
                    put("type", "schedule"); put("times", JsonArray(t.times.distinct().sorted().map(::JsonPrimitive)))
                    put("weekdays", JsonArray(t.weekdays.sorted().map(::JsonPrimitive)))
                }
                RecordTrigger.Manual -> put("type", "manual")
            }
        })
        put("incremental", s.incremental); put("fullCheckAt", s.fullCheckAt?.let(::JsonPrimitive) ?: JsonNull)
        put("layout", s.layout.name); put("rangeDays", s.rangeDays?.let(::JsonPrimitive) ?: JsonNull); put("excludeMedia", s.excludeMedia)
        put("backfillDays", s.backfillDays?.let(::JsonPrimitive) ?: JsonNull); put("lineContext", s.lineContext)
        put("audio", s.audio.name); put("audioWithText", s.audioWithText); put("audioWifiOnly", s.audioWifiOnly)
        put("footprint", s.footprint); put("footprintAt", s.footprintAt)
    }
    fun read(j: JsonObject): PushSubscription {
        fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
        fun JsonObject.flag(key: String, default: Boolean? = null): Boolean = when (val value = get(key)?.jsonPrimitive?.content?.lowercase()) {
            "true" -> true; "false" -> false; else -> default ?: error("Invalid boolean: $key ($value)")
        }
        fun JsonObject.number(key: String): Int? = get(key)?.takeUnless { it == JsonNull }?.jsonPrimitive?.content?.let { it.toIntOrNull() ?: it.toDouble().toInt() }
        val t = j.getValue("trigger").jsonObject
        return PushSubscription(j.flag("summaries"), j.flag("reminders"),
            j.getValue("records").jsonArray.map { RecordKind.valueOf(it.jsonPrimitive.content) }.toSet(), LinesMode.valueOf(j.text("lines")),
            when (t.text("type")) {
                "change" -> RecordTrigger.OnChange(t.number("gap") ?: error("Missing gap"))
                "schedule" -> RecordTrigger.Schedule(t.getValue("times").jsonArray.map { it.jsonPrimitive.content.toInt() },
                    t.getValue("weekdays").jsonArray.map { it.jsonPrimitive.content.toInt() }.toSet())
                else -> RecordTrigger.Manual
            }, j.flag("incremental"), j.number("fullCheckAt"), FolderLayout.valueOf(j.text("layout")), j.number("rangeDays"),
            j.flag("excludeMedia"), j.number("backfillDays"), lineContext = j.flag("lineContext", true),
            audio = AudioPush.entries.find { it.name == j["audio"]?.jsonPrimitive?.content } ?: AudioPush.Off,
            audioWithText = j.flag("audioWithText", true), audioWifiOnly = j.flag("audioWifiOnly", true),
            footprint = j.flag("footprint", false), footprintAt = (runCatching { j.number("footprintAt") }.getOrNull() ?: 22 * 60).coerceIn(0, 1439))
    }
}
