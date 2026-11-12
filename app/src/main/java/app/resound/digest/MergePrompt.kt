package app.resound.digest
import app.resound.data.*
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.datetime.toKotlinLocalDate
import app.resound.digest.core.MergePrompt as Core
object MergePrompt {
    fun system(template: String, today: LocalDate, appName: String) = Core.system(template, today.toKotlinLocalDate(), appName)
    fun input(events: List<MemoryEventEntity>, zone: ZoneId = ZoneId.systemDefault(), situationHeadings: Map<Long, String> = emptyMap()) = Core.input(events.map { it.snapshot() }, zone.sharedZone(), situationHeadings)
    fun parse(content: String, lines: List<TranscriptLineEntity>, zone: ZoneId = ZoneId.systemDefault()) = promptResult {
        Core.parse(content, lines.map { it.snapshot() }, zone.sharedZone()).let { (event, items) -> event.android() to items.map { it.android() } }
    }
}
