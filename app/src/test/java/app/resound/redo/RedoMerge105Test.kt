package app.resound.redo

import android.app.Application
import android.content.Context
import androidx.room.Room
import app.resound.data.*
import app.resound.transcribe.*
import app.resound.digest.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** 合并（卡 105）：把选中的几件事合成一条，原话不重转写，可撤销。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class RedoMerge105Test {
    private lateinit var db: CaptureDatabase
    private lateinit var context: Context
    private lateinit var root: File
    private val chooser = object : AsrChooser {
        override val options = MutableStateFlow(TranscribeSource.entries.map { AsrOption(it, it.title, true) })
        override val lastChoice = MutableStateFlow(TranscribeSource.Local)
        override fun remember(source: TranscribeSource) { lastChoice.value = source }
        override fun estimate(durationMs: Long, source: TranscribeSource) = AsrEstimate(durationMs / 2, 0.0)
    }
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java).allowMainThreadQueries().build()
        root = File(context.cacheDir, "redo105-${System.nanoTime()}").apply { mkdirs() }
    }
    @After fun finish() { db.close(); root.deleteRecursively() }
    private fun event(id: Long = 1, start: Long = 1000, end: Long = 3000, starred: Boolean = false) = MemoryEventEntity(id,
        start, end, "工作", "旧事件", "旧摘要", "[]", "[]", null, null, null, false, createdAt = 1, model = "old", starred = starred)

    /** 造一件事：事件 + 录音段 + 一句原话（原话挂在事件上）。 */
    private suspend fun seed(id: Long = 1, start: Long = 1000, end: Long = 3000, expired: Boolean = false, starred: Boolean = false) {
        db.memory().insertEvent(event(id, start, end, starred))
        val file = File(root, "$id.wav"); if (!expired) file.writeText("audio")
        db.capture().segment(SegmentEntity(id, start, end, "mic", file.path, 5, "Done", completedAt = 1))
        db.transcribe().insertLines(listOf(TranscriptLineEntity(id, id, start, end, "原话$id", null, eventId = id, digestedAt = 1)))
    }
    /** 只重做会重新转写、只整理会走普通提示词；合并两个都不该碰。 */
    private fun engine(mergedEvents: Int = 1) = RedoEngine(db, chooser,
        recognize = { _, _ -> error("合并不该重新转写") },
        digest = { _, _ -> error("合并不该走普通整理") },
        merge = { _, lines, _ ->
            val e = event(0, lines.minOf { it.startAt }, lines.maxOf { it.endAt }).copy(title = "合并事件", model = "m")
            RedoDigest(List(mergedEvents) { e to lines.map { it.id } }) { ids -> listOf(MemoryItemEntity(eventId = ids[0], category = "待办", kind = null,
                content = "交报告", dueText = null, dueAt = null, who = null, lineId = lines.first().id, status = "Pending", createdAt = 10)) }
        }, now = { 100 })
    private suspend fun run(e: RedoEngine, id: String = "one", request: RedoRequest = RedoRequest(listOf(1, 2), scope = RedoScope.Merge)) =
        e.run(id, request, {}, { _, _ -> })
    private suspend fun lineEvents() = db.transcribe().exportSegments(listOf(1L, 2L)).flatMap { it.lines }.mapNotNull { it.eventId }.toSet()

    @Test fun mergeReplacesTwoEventsWithOneAndPointsEveryLineAtIt() = runBlocking {
        seed(1, 1000, 3000); seed(2, 4000, 6000)
        run(engine())
        val events = db.redoData().events(0, 10_000_000)
        assertEquals(listOf("合并事件"), events.map { it.title })
        val lines = db.transcribe().exportSegments(listOf(1L, 2L)).flatMap { it.lines }
        assertTrue(lines.all { it.eventId == events.single().id })
        assertEquals(listOf("原话1", "原话2"), lines.map { it.text })
        assertTrue(db.memory().undigestedLines().isEmpty())
    }

    @Test fun mergeRefusesEventsFarApartAndKeepsEverything() = runBlocking {
        seed(1, 1000, 3000); seed(2, 3_600_000, 3_602_000)
        try { run(engine()); fail() } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("相隔太久")) }
        assertEquals(2, db.redoData().events(0, 10_000_000).size)
        assertEquals(setOf(1L, 2L), lineEvents())
    }

    @Test fun mergeNeedsTwoEventsAndKeepsThemWhenRejected() = runBlocking {
        seed(1, 1000, 3000)
        try { run(engine(), request = RedoRequest(listOf(1), scope = RedoScope.Merge)); fail() }
        catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("至少选两件")) }
        assertEquals(1, db.redoData().events(0, 10_000_000).size)
        assertEquals(setOf(1L), lineEvents())
    }

    @Test fun mergeDoesNotRetranscribeSoExpiredAudioStillMerges() = runBlocking {
        seed(1, 1000, 3000, expired = true); seed(2, 4000, 6000, expired = true)
        run(engine())
        assertEquals(1, db.redoData().events(0, 10_000_000).size)
        assertEquals(listOf("原话1", "原话2"), db.transcribe().exportSegments(listOf(1L, 2L)).flatMap { it.lines }.map { it.text })
    }

    @Test fun mergeInheritsStarAndKeepsDoneTodo() = runBlocking {
        seed(1, 1000, 3000, starred = true); seed(2, 4000, 6000)
        db.memory().insertItems(listOf(MemoryItemEntity(id = 7, eventId = 1, category = "待办", kind = null, content = "交报告",
            dueText = null, dueAt = null, who = null, lineId = 1, status = "Done", createdAt = 1, doneAt = 9)))
        run(engine())
        val events = db.redoData().events(0, 10_000_000)
        assertTrue(events.single().starred)
        val items = db.redoData().items(events.map { it.id })
        assertEquals(1, items.size)
        assertEquals(7, items.single().id); assertEquals("Done", items.single().status)
        assertEquals(events.single().id, items.single().eventId)
    }

    @Test fun mergeFailsWhenModelReturnsSeveralEventsAndKeepsOldData() = runBlocking {
        seed(1, 1000, 3000); seed(2, 4000, 6000)
        try { run(engine(mergedEvents = 2)); fail() } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("合成一条")) }
        assertEquals(2, db.redoData().events(0, 10_000_000).size)
        assertEquals(setOf(1L, 2L), lineEvents())
    }

    @Test fun undoRestoresBothEventsItemsAndLineOwnership() = runBlocking {
        seed(1, 1000, 3000); seed(2, 4000, 6000)
        db.memory().insertItems(listOf(MemoryItemEntity(id = 7, eventId = 2, category = "待办", kind = null, content = "交报告",
            dueText = null, dueAt = null, who = null, lineId = 2, status = "Accepted", createdAt = 1)))
        val e = engine(); run(e)
        assertEquals(1, db.redoData().events(0, 10_000_000).size)
        assertTrue(e.undo("one").isSuccess)
        val events = db.redoData().events(0, 10_000_000)
        assertEquals(2, events.size)
        assertEquals(setOf("旧事件"), events.map { it.title }.toSet())
        assertEquals(setOf(1L, 2L), lineEvents())
        val items = db.redoData().items(events.map { it.id })
        assertEquals(1, items.size); assertEquals(7, items.single().id); assertEquals(2L, items.single().eventId)
    }

    /** 合并按已整理好的结果写，不重读原话：原话再多也能合，旧事件的提取项（含未确认的）全部原样改挂过去 */
    @Test fun mergeHandlesManyLinesAndKeepsAllOldItems() = runBlocking {
        seed(1, 1000, 3000); seed(2, 4000, 6000)
        db.transcribe().insertLines((10..2000).map { TranscriptLineEntity(it.toLong(), 1L, 3000L + it, 3001L + it, "话$it", null, eventId = 1, digestedAt = 1) })
        db.memory().insertItems(listOf(
            MemoryItemEntity(id = 7, eventId = 1, category = "待办", kind = null, content = "交报告", dueText = null, dueAt = null, who = null, lineId = 1, status = "Pending", createdAt = 1),
            MemoryItemEntity(id = 8, eventId = 2, category = "关键信息", kind = null, content = "电话 123", dueText = null, dueAt = null, who = null, lineId = 2, status = "Pending", createdAt = 1)))
        var seenEvents = 0
        val e = RedoEngine(db, chooser, recognize = { _, _ -> error("不该转写") }, digest = { _, _ -> error("不该普通整理") },
            merge = { events, lines, _ ->
                seenEvents = events.size
                RedoDigest(listOf(event(0, lines.minOf { it.startAt }, lines.maxOf { it.endAt }).copy(title = "合并事件") to lines.map { it.id })) { emptyList() }
            }, now = { 100 })
        run(e)
        assertEquals(2, seenEvents)
        val events = db.redoData().events(0, 10_000_000)
        assertEquals(listOf("合并事件"), events.map { it.title })
        assertEquals(setOf(7L, 8L), db.redoData().items(events.map { it.id }).map { it.id }.toSet())
    }

    @Test fun mergeEstimateReportsLinesGapAndNoAudioCost() = runBlocking {
        seed(1, 1000, 3000); seed(2, 4000, 6000)
        val e = engine()
        val estimate = e.estimate(RedoRequest(listOf(1, 2), scope = RedoScope.Merge))
        assertEquals(2, estimate.events); assertEquals(0, estimate.segments); assertEquals(0L, estimate.audioMs)
        assertNull(estimate.costYuan); assertEquals(2, estimate.mergeLines)
        assertEquals(1000L, estimate.mergeGapMillis); assertFalse(estimate.mergeTooLong)
    }

    @Test fun mergeOnlyTakesTheSelectedEventsOwnLines() = runBlocking {
        seed(1, 1000, 3000); seed(2, 4000, 6000)
        // 另一件事的原话不该被卷进来
        db.memory().insertEvent(event(30, 7000, 8000))
        db.capture().segment(SegmentEntity(30, 7000, 8000, "mic", File(root, "30.wav").path, 5, "Done", completedAt = 1))
        db.transcribe().insertLines(listOf(TranscriptLineEntity(90, 30, 7000, 8000, "别人的原话", null, eventId = 30, digestedAt = 1)))
        var seen = 0
        val e = RedoEngine(db, chooser, recognize = { _, _ -> error("不该转写") }, digest = { _, _ -> error("不该普通整理") },
            merge = { _, lines, _ ->
                seen = lines.size
                val e2 = event(0, lines.minOf { it.startAt }, lines.maxOf { it.endAt }).copy(title = "合并事件")
                RedoDigest(listOf(e2 to lines.map { it.id })) { emptyList() }
            }, now = { 100 })
        run(e)
        assertEquals(2, seen)
        // 合并后是一条新事件 + 没被选中的那一条
        assertEquals(2, db.redoData().events(0, 10_000_000).size)
        assertEquals(30L, db.transcribe().exportSegments(listOf(30L)).flatMap { it.lines }.single().eventId)
    }
}
