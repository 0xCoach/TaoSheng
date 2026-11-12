package app.resound.things

import androidx.room.withTransaction
import app.resound.data.CaptureDatabase
import app.resound.data.TranscriptLineEntity
import app.resound.data.EventEntity
import app.resound.platform.NowMillis
import kotlinx.coroutines.CoroutineScope

internal class ThingWatcher(private val db: CaptureDatabase, repository: RoomThingRepository,
    detect: suspend (List<TranscriptLineEntity>, List<TranscriptLineEntity>, List<Thing>) -> List<ThingProposal>,
    notify: (ThingRecordRow) -> Unit = {}, now: () -> Long = System::currentTimeMillis,
) {
    private val originals = mutableMapOf<Long, TranscriptLineEntity>()
    private fun remember(rows: List<TranscriptLineEntity>) = rows.map { originals[it.id] = it; it.shared() }
    private val engine = ThingWatcherRules(object : ThingWatchStore {
        private val dao = db.things()
        override suspend fun <T> transaction(block: suspend () -> T): T = db.withTransaction { block() }
        override suspend fun state() = dao.state()?.shared()
        override suspend fun state(value: ThingWatchData) = dao.state(value.room())
        override suspend fun maxLineIdBefore(before: Long) = dao.maxLineIdBefore(before)
        override suspend fun maxLineId() = dao.maxLineId()
        override suspend fun batch(after: Long, until: Long): List<ThingLine> {
            originals.clear()
            return dao.batch(after, until).map { it.shared() }
        }
        override suspend fun eligible(ids: List<Long>) = remember(dao.eligible(ids))
        override suspend fun context(before: Long) = remember(dao.context(before))
        override suspend fun allThings() = dao.allThings().map { it.shared() }
        override suspend fun allRecords() = dao.allRecords().map { it.shared() }
        override suspend fun propose(value: ThingProposalData) = repository.propose(ThingProposal(value.row.room(), value.confident))?.shared()
        override suspend fun skipped(at: Long, detail: String) {
            db.capture().event(EventEntity(at = at, type = "ThingsSkipped", detail = detail, batteryPercent = null, charging = null))
        }
    }, { lines, context, known ->
        try { detect(lines.map { originals.getValue(it.id) }, context.map { originals.getValue(it.id) }, known)
            .map { ThingProposalData(it.row.shared(), it.confident) } }
        finally { originals.clear() }
    }, { notify(it.room()) }, NowMillis(now))
    fun request() = engine.request()
    fun start(scope: CoroutineScope, debounceMillis: Long = 30_000, skipBefore: Long? = null, onSkipped: () -> Unit = {}) =
        engine.start(scope, debounceMillis, skipBefore, onSkipped)
    suspend fun skipHistory(before: Long) = engine.skipHistory(before)
    suspend fun runOnce(): Boolean = engine.runOnce()
}
