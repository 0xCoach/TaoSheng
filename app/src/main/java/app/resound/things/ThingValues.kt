package app.resound.things

import app.resound.data.TranscriptLineEntity
import java.time.Instant
import java.time.ZoneId

internal fun TranscriptLineEntity.shared() = ThingLine(id, startAt, endAt, segmentId, speakerName, speakerPersonId, text,
    Instant.ofEpochMilli(startAt).atZone(ZoneId.systemDefault()).toString())
