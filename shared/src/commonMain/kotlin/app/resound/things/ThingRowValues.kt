package app.resound.things

fun ThingRow.shared() = ThingData(id, name, aliasesJson, owner, createdAt, updatedAt, deleted)
fun ThingRecordRow.shared() = ThingRecordData(id, thingId, name, action, place, who, owner, at, sourceLineId,
    sourceText, status, origin, explicit, sameAsId, createdAt, acceptedAt, aliasesJson)
fun ThingRecordData.room() = ThingRecordRow(id, thingId, name, action, place, who, owner, at, sourceLineId,
    sourceText, status, origin, explicit, sameAsId, createdAt, acceptedAt, aliasesJson)
fun ThingWatchState.shared() = ThingWatchData(slot, watermark, enabled, revision, failedEnd, failures)
fun ThingWatchData.room() = ThingWatchState(slot, watermark, enabled, revision, failedEnd, failures)
