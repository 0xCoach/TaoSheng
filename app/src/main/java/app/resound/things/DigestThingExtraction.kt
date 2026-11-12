package app.resound.things

import app.resound.data.writeTransaction
import app.resound.data.CaptureDatabase
import app.resound.data.EventEntity
import app.resound.data.TranscriptLineEntity
import app.resound.digest.DigestPrompt

/** 自动寻物复用整理请求；本类不持有 LLM 客户端，不发起任何网络请求。 */
class DigestThingExtraction internal constructor(
    private val db: CaptureDatabase,
    private val repository: RoomThingRepository,
    private val template: () -> String,
    private val initialize: suspend () -> Unit = {},
    private val notify: (ThingRecordRow) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
) {
    internal data class Batch(val lines: List<TranscriptLineEntity>, val eligible: Set<Long>,
        val known: Set<Long>, val revision: Long, val system: String, val input: String)

    internal suspend fun prepare(lines: List<TranscriptLineEntity>): Batch? {
        initialize()
        val state = db.things().state() ?: ThingWatchState()
        if (!state.enabled) return null
        val eligible = db.things().eligible(lines.filter { it.id > state.watermark }.map { it.id }).map { it.id }.toSet()
        if (eligible.isEmpty()) return null
        val rows = db.things().allThings()
        val catalog = things(rows, db.things().allRecords()).sortedByDescending { t -> rows.first { it.id == t.id }.updatedAt }
        val previous = ThingPrompt.context(lines, db.things().context(lines.minOf { it.id } - 1).reversed())
        val selected = ThingPrompt.known(lines, previous, catalog)
        val rules = DigestThingRules.system(template(), java.time.LocalDate.now().toString())
        val input = DigestThingRules.input(lines.map { it.shared() }, previous.map { it.shared() }, selected, eligible)
        return Batch(lines, eligible, selected.map { it.id }.toSet(), state.revision, rules, input)
    }

    /** 整理与寻物同事务入库；寻物格式问题不触发另一轮付费请求。 */
    internal suspend fun commit(batch: Batch?, content: String, events: List<DigestPrompt.ParsedEvent>, save: suspend () -> Unit) {
        if (batch == null) { save(); return }
        val parsed = DigestThingRules.parse(normalizeThingJson(content), batch.lines.map { it.shared() }, batch.eligible, batch.known,
            events.filter { it.scene == "媒体" }.flatMap { it.lineIds }.toSet(), app.resound.platform.NowMillis(now))
        val rejected = parsed.rejected
        val notices = db.writeTransaction {
            // 在改写事件归属前核对原始资格及原话，防止删除/重转写后复活旧请求。
            if (db.memory().refreshLines(batch.lines.map { it.id }).size != batch.lines.size) return@writeTransaction emptyList<ThingRecordRow>()
            val current = db.things().state() ?: ThingWatchState()
            val live = db.things().eligible(batch.eligible.toList()).map { it.shared() }
            val valid = DigestThingRules.valid(parsed.proposals, current.shared(), batch.revision, live)
            save()
            val existing = db.things().allRecords()
            val saved = valid.filterNot { p -> existing.any { DigestThingRules.duplicate(p.row, it.shared()) } }
                .mapNotNull { repository.propose(ThingProposal(it.row.room(), it.confident)) }
            db.capture().event(EventEntity(at = now(), type = "ThingsShared", detail =
                "lines=${batch.eligible.size} results=${saved.size} rejected=$rejected sharedRequest=true", batteryPercent = null, charging = null))
            saved.filter { it.explicit }
        }
        notices.distinctBy { it.id }.forEach { runCatching { notify(it) } }
    }
}
