package app.resound.digest

// 同名类型与本文件声明或已有 import 冲突，以下引用保留全限定名：
// app.resound.digest.core.ReviewInput

import app.resound.data.MemoryEventEntity
import app.resound.data.MemoryItemEntity
import app.resound.data.ReviewInput
import app.resound.data.TranscriptLineEntity
import app.resound.digest.core.MemoryEventData
import app.resound.digest.core.MemoryItemData
import app.resound.digest.core.TranscriptLineData

import app.resound.data.*
import app.resound.digest.core.*

fun TranscriptLineEntity.snapshot() = TranscriptLineData(id = id, segmentId = segmentId, startAt = startAt, endAt = endAt, text = text, speakerTag = speakerTag,
    speakerName = speakerName, eventId = eventId, digestedAt = digestedAt, speakerScore = speakerScore, speakerPersonId = speakerPersonId, speakerManual = speakerManual,
    voiceInputJson = voiceInputJson, voiceInputPendingUntil = voiceInputPendingUntil, originalText = originalText)
fun MemoryEventEntity.snapshot() = MemoryEventData(id = id, startAt = startAt, endAt = endAt, scene = scene, title = title, summary = summary, pointsJson = pointsJson,
    peopleJson = peopleJson, place = place, emotion = emotion, intensity = intensity, important = important, deleted = deleted, createdAt = createdAt, model = model,
    starred = starred, starredAt = starredAt, editedAt = editedAt, originalTitle = originalTitle, originalSummary = originalSummary, originalPointsJson = originalPointsJson)
fun MemoryItemEntity.snapshot() = MemoryItemData(id = id, eventId = eventId, category = category, kind = kind, content = content, dueText = dueText, dueAt = dueAt,
    who = who, lineId = lineId, status = status, createdAt = createdAt, doneAt = doneAt, statusChangedAt = statusChangedAt, sortOrder = sortOrder,
    mergedInto = mergedInto, noMerge = noMerge, deleted = deleted, editedAt = editedAt, doneHintLineId = doneHintLineId, doneHintAt = doneHintAt,
    dismissedDoneLinesJson = dismissedDoneLinesJson, reminderVersion = reminderVersion, origin = origin, sourceText = sourceText, strongReminder = strongReminder)
fun ReviewInput.snapshot() = app.resound.digest.core.ReviewInput(events = events.map { it.snapshot() }, items = items.map { it.snapshot() }, starredStale = starredStale.map { it.snapshot() })
