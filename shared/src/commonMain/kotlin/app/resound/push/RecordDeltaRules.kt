package app.resound.push

import kotlinx.serialization.json.*

object RecordDeltaRules {
    fun changes(current: Map<String, JsonObject>, previous: Map<String, String>, skipped: Set<String>,
        fingerprint: (String) -> String): JsonObject {
        fun entry(op: String, key: String, data: JsonObject) = buildJsonObject {
            put("op", op); put("kind", key.substringBefore(':')); put("id", key.substringAfter(':').toLong()); put("data", data)
        }
        val changes = buildList {
            current.keys.sorted().forEach { key ->
                val data = current.getValue(key)
                if (previous[key].orEmpty() != fingerprint(key)) add(entry("upsert", key, data))
            }
            previous.keys.sorted().filter { it !in current && it !in skipped }.forEach { add(entry("delete", it, JsonObject(emptyMap()))) }
        }
        return buildJsonObject { put("type", "records_delta"); put("version", 1); put("changes", JsonArray(changes)) }
    }
}
