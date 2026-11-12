package app.resound.things

import app.resound.data.TranscriptLineEntity
import java.time.Instant
import java.time.ZoneId

internal fun TranscriptLineEntity.shared() = ThingLine(id, startAt, endAt, segmentId, speakerName, speakerPersonId, text,
    Instant.ofEpochMilli(startAt).atZone(ZoneId.systemDefault()).toString())
internal fun ThingRow.shared() = ThingData(id, name, aliasesJson, owner, createdAt, updatedAt, deleted)
internal fun ThingRecordRow.shared() = ThingRecordData(id, thingId, name, action, place, who, owner, at, sourceLineId,
    sourceText, status, origin, explicit, sameAsId, createdAt, acceptedAt, aliasesJson)
internal fun ThingRecordData.room() = ThingRecordRow(id, thingId, name, action, place, who, owner, at, sourceLineId,
    sourceText, status, origin, explicit, sameAsId, createdAt, acceptedAt, aliasesJson)
internal fun ThingWatchState.shared() = ThingWatchData(slot, watermark, enabled, revision, failedEnd, failures)
internal fun ThingWatchData.room() = ThingWatchState(slot, watermark, enabled, revision, failedEnd, failures)
