package app.resound.digest

// 同名类型与本文件声明或已有 import 冲突，以下引用保留全限定名：
// app.resound.digest.core.SituationPrompt
import app.resound.data.*
import app.resound.context.SituationLookup
import java.time.ZoneId
internal class SituationPrompt(enabled: Boolean, lookup: SituationLookup?, playback: Map<Long, Long> = emptyMap(),
    private val marks: suspend (Long, Long) -> List<app.resound.marks.MarkRow> = { _, _ -> emptyList() }) {
    private val core = app.resound.digest.core.SituationPrompt(enabled, lookup, playback)
    fun system(original: String) = core.system(original)
    suspend fun transcript(lines: List<TranscriptLineEntity>, unseparated: Set<Long> = emptySet(), zone: ZoneId = ZoneId.systemDefault()) =
        (core.transcript(lines.map { it.snapshot() }, unseparated, zone.sharedZone()).first +
            if (lines.isEmpty()) "" else app.resound.marks.MarkPrompt.context(
                marks(lines.minOf { it.startAt }, lines.maxOf { it.endAt }), lines.map { it.snapshot() })) to lines
    suspend fun merge(events: List<MemoryEventEntity>, zone: ZoneId = ZoneId.systemDefault()) = core.merge(events.map { it.snapshot() }, zone.sharedZone())
    companion object {
        const val RULE = app.resound.digest.core.SituationPrompt.RULE
        const val PLAYBACK_RULE = app.resound.digest.core.SituationPrompt.PLAYBACK_RULE
    }
}
