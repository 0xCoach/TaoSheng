package app.resound.digest

// 同名类型与本文件声明或已有 import 冲突，以下引用保留全限定名：
// app.resound.digest.core.SituationPrompt
import app.resound.data.*
import app.resound.context.SituationLookup
import java.time.ZoneId
internal class SituationPrompt(enabled: Boolean, lookup: SituationLookup?) {
    private val core = app.resound.digest.core.SituationPrompt(enabled, lookup)
    fun system(original: String) = core.system(original)
    suspend fun transcript(lines: List<TranscriptLineEntity>, unseparated: Set<Long> = emptySet(), zone: ZoneId = ZoneId.systemDefault()) =
        core.transcript(lines.map { it.snapshot() }, unseparated, zone.sharedZone()).first to lines
    suspend fun merge(events: List<MemoryEventEntity>, zone: ZoneId = ZoneId.systemDefault()) = core.merge(events.map { it.snapshot() }, zone.sharedZone())
    companion object { const val RULE = app.resound.digest.core.SituationPrompt.RULE }
}
