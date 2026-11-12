package app.resound.digest
import app.resound.data.TranscriptLineEntity
fun splitForRetry(lines: List<TranscriptLineEntity>, minLines: Int = MIN_SPLIT_LINES) =
    splitDigestForRetry(lines, minLines, { it.startAt }, { it.endAt })
