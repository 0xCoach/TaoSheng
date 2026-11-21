package app.resound.digest.core

import app.resound.data.SegmentPlayback
import app.resound.screentime.DevicePlayback
import app.resound.transcribe.*
import kotlinx.serialization.json.Json

fun playbackLabels(segments: List<SegmentPlayback>, lines: List<TranscriptLineData>, rows: List<DevicePlayback>): Map<Long, SentencePlayback> {
    val segmentsById = segments.associateBy { it.id }
    val intervals = segments.associate { it.id to it.speakerIntervals?.let { text -> Json.decodeFromString<List<SpeakerInterval>>(text) } }
    return lines.associate { line ->
        val segment = segmentsById[line.segmentId]
        line.id to sentencePlayback(line.id, line.startAt, line.endAt, intervals[line.segmentId],
            segment?.playbackMillis ?: 0, segment?.duration ?: 0, rows)
    }
}
