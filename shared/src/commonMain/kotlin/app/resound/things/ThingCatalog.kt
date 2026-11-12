package app.resound.things

import app.resound.platform.SharedJson
import app.resound.platform.legacyValue
import kotlinx.serialization.json.*

fun thingAliases(json: String): List<String> = runCatching {
    SharedJson.parseToJsonElement(json).jsonArray.map { it.legacyValue() }
}.getOrDefault(emptyList())
fun ThingRecordData.model(names: Map<Long, String>) = ThingRecord(id, thingId, name,
    ThingAction.valueOf(action), place, who, ThingOwner.valueOf(owner), at, sourceLineId, sourceText,
    RecordStatus.valueOf(status), ThingOrigin.valueOf(origin), explicit,
    sameAsId?.let { id -> names[id]?.let { SameAsQuestion(id, it) } })

fun thingCatalog(rows: List<ThingData>, records: List<ThingRecordData>): List<Thing> {
    val names = rows.associate { it.id to it.name }
    return rows.mapNotNull { t ->
        val history = records.filter { it.thingId == t.id && it.status == "Accepted" }.sortedWith(compareByDescending<ThingRecordData> { it.at }.thenByDescending { it.id })
        val latest = history.firstOrNull() ?: return@mapNotNull null
        val lastPlace = history.firstOrNull { it.action == "Placed" && it.place != null }
        Thing(t.id, t.name, thingAliases(t.aliasesJson), ThingOwner.valueOf(t.owner), latest.model(names), lastPlace?.place,
            lastPlace?.at, history.size, records.any { it.status == "Pending" && (it.thingId == t.id || it.sameAsId == t.id) })
    }.sortedWith(compareByDescending<Thing> { it.latest.at }.thenByDescending { it.latest.id })
}
