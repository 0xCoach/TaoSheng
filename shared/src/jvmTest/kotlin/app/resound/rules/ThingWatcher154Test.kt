package app.resound.rules

import app.resound.things.*
import app.resound.platform.NowMillis
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ThingWatcher154Test {
    private class Store : ThingWatchStore {
        var current = ThingWatchData()
        var lines = listOf(ThingLine(1, 100, 200, 1, null, null, "钥匙在柜子"))
        val saved = mutableListOf<ThingRecordData>()
        var skipped = 0
        override suspend fun <T> transaction(block: suspend () -> T): T = block()
        override suspend fun state() = current
        override suspend fun state(value: ThingWatchData) { current = value }
        override suspend fun maxLineIdBefore(before: Long) = lines.filter { it.startAt < before }.maxOfOrNull { it.id } ?: 0
        override suspend fun maxLineId() = lines.maxOfOrNull { it.id } ?: 0
        override suspend fun batch(after: Long, until: Long) = lines.filter { it.id > after && it.id <= until }.take(80)
        override suspend fun eligible(ids: List<Long>) = lines.filter { it.id in ids }
        override suspend fun context(before: Long) = lines.filter { it.id <= before }
        override suspend fun allThings() = emptyList<ThingData>()
        override suspend fun allRecords() = saved.toList()
        override suspend fun propose(value: ThingProposalData): ThingRecordData = value.row.copy(id = saved.size + 1L).also { saved += it }
        override suspend fun skipped(at: Long, detail: String) { skipped++ }
    }
    @Test fun failurePinsBatchThenSkipsOnlyAfterThirdFailure() = runBlocking {
        val store = Store()
        val engine = ThingWatcherRules(store, { _, _, _ -> error("offline") }, {}, NowMillis { 100 })
        assertTrue(engine.runOnce()); assertEquals(0L, store.current.watermark); assertEquals(1L, store.current.failedEnd)
        store.lines = store.lines + store.lines.single().copy(id = 2)
        assertTrue(engine.runOnce()); assertEquals(2, store.current.failures)
        assertTrue(engine.runOnce()); assertEquals(1L, store.current.watermark); assertEquals(0, store.current.failures); assertEquals(1, store.skipped)
    }
    @Test fun commitNotifiesOnceAndNeverRevivesChangedSource() = runBlocking {
        val store = Store(); var notices = 0; var stale = false
        val engine = ThingWatcherRules(store, { lines, _, _ ->
            val line = lines.single()
            if (stale) store.lines = listOf(line.copy(text = "已重转写"))
            listOf(ThingProposalData(ThingRecordData(name = "钥匙", action = "Placed", place = "柜子", who = null, owner = "Mine",
                at = line.startAt, sourceLineId = line.id, sourceText = line.text, explicit = true, createdAt = 1), true))
        }, { notices++ }, NowMillis { 100 })
        assertFalse(engine.runOnce()); assertEquals(1, store.saved.size); assertEquals(1, notices)
        stale = true; store.lines = store.lines.map { it.copy(id = 2) }
        assertFalse(engine.runOnce()); assertEquals(1, store.saved.size); assertEquals(2L, store.current.watermark)
    }
}
