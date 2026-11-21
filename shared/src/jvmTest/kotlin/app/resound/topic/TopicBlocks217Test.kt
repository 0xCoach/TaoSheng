package app.resound.topic

import app.resound.topic.core.*
import app.resound.topic.core.TopicGroup
import app.resound.topic.core.TopicRow
import app.resound.topic.core.TopicSpanRow
import app.resound.digest.core.TranscriptLineData
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import org.junit.Assert.*
import org.junit.Test

class TopicBlocks217Test {
    private val minute = 60_000L
    private fun group(status: String = "Ended", end: Long = 30 * minute) = TopicGroup(
        TopicRow(id = 1, kind = "Normal", name = "周会", status = status, createdAt = 0),
        listOf(TopicSpanRow(topicId = 1, startAt = 0, endAt = if (status == "Running") null else end)), emptyList())
    private fun speech() = (0..2).flatMap { block -> (0..4).map { i ->
        TranscriptLineData(id = block * 5L + i + 1, segmentId = block + 1L,
            startAt = block * 10 * minute + i * minute, endAt = block * 10 * minute + i * minute + 1000,
            text = "块$block 句$i", speakerTag = "0")
    } }
    private class Fixture {
        var time = 30 * 60_000L
        var todo = "待办原始上下文"
        val store = MemoryTopicCheckpoints()
        val calls = mutableListOf<Pair<String, String>>()
        var failure: (String, String) -> Boolean = { _, _ -> false }
        var replySummary = "决定提交报告"
        var beforeReply: suspend () -> Unit = {}
        val generator = TopicBlockGenerator({ "提取 events items" }, { error("普通专题无卡模板") }, { s, u ->
            calls += s to u; beforeReply()
            if (failure(s, u)) throw IllegalStateException("模拟短暂失败")
            if (s == TopicBlockGenerator.NORMAL_SUMMARY) "{\"summary\":\"全文纪要\"}"
            else """{"events":[{"from":1,"to":5,"title":"讨论","summary":"$replySummary","points":["提交报告"]}],"items":[]}"""
        }, { emptyList() }, { time }, { TimeZone.UTC }, "涛声")
        fun processor() = TopicBlockProcessor(1, store, generator, { it },
            { TopicFailure(TopicFailureKind.Temporary, "模拟失败") }, {}, { time }, { (it / 60_000).toString() })
        suspend fun run(g: TopicGroup, lines: List<TranscriptLineData>, segments: List<TopicBlockSegment> = emptyList(), network: Boolean = true) =
            processor().run(g, lines, segments, { false }, network, { todo })
    }
    @Test fun pausedSpansUseMeetingTimeAndSpeechNeverMovesBoundaries() {
        val g = group().copy(spans = listOf(TopicSpanRow(topicId = 1, startAt = 0, endAt = 7 * minute),
            TopicSpanRow(topicId = 1, startAt = 20 * minute, endAt = 35 * minute)))
        val blocks = TopicBlocks.timeline(g, 35 * minute)
        assertEquals(listOf(listOf(TopicBlockRange(0, 7 * minute), TopicBlockRange(20 * minute, 23 * minute)),
            listOf(TopicBlockRange(23 * minute, 33 * minute)), listOf(TopicBlockRange(33 * minute, 35 * minute))), blocks.map { it.ranges })
        assertTrue(blocks.all { it.closed }); assertFalse(blocks[0].contains(8 * minute))
        assertFalse(blocks[0].contains(23 * minute)); assertTrue(blocks[1].contains(23 * minute))
        assertEquals(blocks, TopicBlocks.timeline(g, 99 * minute))
    }
    @Test fun cacheFreezesTodosMiddleEditOnlyRebuildsOneBlockTailEditRebuildsNext() = runBlocking {
        val f = Fixture(); val g = group(); val lines = speech()
        f.run(g, lines); assertEquals(4, f.calls.size)
        f.todo = "在别处已勾掉"; f.run(g, lines); assertEquals(4, f.calls.size)
        val middle = lines.map { if (it.id == 2L) it.copy(speakerName = "我") else it }
        f.run(g, middle); assertEquals(6, f.calls.size)
        assertEquals("在别处已勾掉", f.store.read(1)!!.blocks[0]!!.todoContext)
        val tail = middle.map { if (it.id == 5L) it.copy(speakerName = "同事") else it }
        f.run(g, tail); assertEquals(9, f.calls.size)
        assertEquals(3, f.store.read(1)!!.blocks.size)
        assertTrue(f.calls.last { it.first != TopicBlockGenerator.NORMAL_SUMMARY }.second.contains("前情（仅供理解，不要提取"))
    }
    @Test fun readinessRequiresClosedBlockAndTranscriptionButVoiceWaitIsBounded() {
        val b = TopicBlocks.timeline(group(), 30 * minute).first()
        val pending = TopicBlockSegment(1, 0, minute, "Pending")
        assertFalse(TopicBlocks.readiness(b, listOf(pending), false, 0, 30 * minute).ready)
        assertFalse(TopicBlocks.readiness(b.copy(closed = false), emptyList(), false, null, 30 * minute).ready)
        val voice = pending.copy(status = "Done", voiceprintPending = 1)
        assertFalse(TopicBlocks.readiness(b, listOf(voice), false, minute, 5 * minute).ready)
        assertTrue(TopicBlocks.readiness(b, listOf(voice), false, minute, 6 * minute).ready)
    }
    @Test fun activeWorkCachesOnlyFinishedBlocksEndingOnlyAddsTailAndSummary() = runBlocking {
        val f = Fixture(); f.time = 25 * minute
        val lines = speech()
        val live = f.run(group("Running"), lines)
        assertNull(live.summary); assertEquals(2, f.calls.size)
        assertEquals(2, f.store.read(1)!!.progress.blocksDone)
        f.run(group(end = 25 * minute), lines)
        assertEquals(4, f.calls.size)
        f.run(group(end = 25 * minute), lines); assertEquals(4, f.calls.size)
    }
    @Test fun failedBlockDoesNotBlockSummaryAndRetryReusesSuccessfulRequests() = runBlocking {
        val f = Fixture(); f.failure = { s, u -> s != TopicBlockGenerator.NORMAL_SUMMARY && u.contains("[1] 00:10") }
        val result = f.run(group(), speech())
        assertEquals(1, f.store.read(1)!!.progress.blocksFailed)
        assertTrue(result.summary!!.text.contains("10–20 这段没整理"))
        val before = f.calls.size
        val old = f.store.read(1)!!
        f.store.write(1, old.copy(blocks = old.blocks.mapValues { (_, b) -> if (b.state == TopicBlockState.Failed) b.copy(state = TopicBlockState.Waiting) else b }))
        f.failure = { _, _ -> false }; f.run(group(), speech())
        assertEquals(before + 2, f.calls.size)
    }
    @Test fun noProgressShowsReasonAndSummarizeNowSkipsOnlyUnreadyBlock() = runBlocking {
        val f = Fixture(); val pending = listOf(TopicBlockSegment(3, 20 * minute, 30 * minute, "Failed"))
        assertTrue(f.run(group(), speech(), pending).waiting)
        f.time += TopicBlocks.STALL
        f.run(group(), speech(), pending)
        val stalled = f.store.read(1)!!
        assertTrue(stalled.progress.stalledReason!!.contains("1 段"))
        f.store.write(1, stalled.copy(skipWaiting = true))
        val result = f.run(group(), speech(), pending)
        assertTrue(result.summary!!.text.contains("20–30 这段没整理"))
        assertEquals(2, result.results.size); assertEquals(3, f.calls.size)
        f.run(group(), speech().map { if (it.id == 15L) it.copy(text = "迟到的新原话") else it }) // 主动跳过的块不会偷偷补上。
        assertEquals(3, f.calls.size)
    }
    @Test fun offlineProgressDoesNotCallModelAndRecoveryContinues() = runBlocking {
        val f = Fixture()
        f.run(group(), speech(), network = false)
        assertTrue(f.store.read(1)!!.progress.waitingNetwork); assertTrue(f.calls.isEmpty())
        f.run(group(), speech()); assertEquals(4, f.calls.size)
    }
    @Test fun summaryUsesOneRequestOrSixWayFanInWithoutRawSpeech() = runBlocking {
        val f = Fixture()
        fun result(size: Int) = TopicBlockResult(events = listOf(app.resound.digest.core.DigestPrompt.ParsedEvent(
            emptyList(), 0, 1, "其他", "主题", "摘".repeat(size), emptyList(), emptyList(), null, null, null, false)))
        suspend fun summarize(results: List<TopicBlockResult>) {
            val session = TopicProcessingSession(99, "source", MemoryTopicCheckpoints(), { it }, { TopicFailure(TopicFailureKind.Permanent, "失败") }, {})
            session.begin(); f.generator.summarize(group(), results, "", session)
        }
        summarize(List(12) { result(100) }); assertEquals(1, f.calls.size)
        f.calls.clear(); summarize(List(12) { i -> result(1100 + i) }); assertEquals(3, f.calls.size)
        assertTrue(f.calls.all { it.first == TopicBlockGenerator.NORMAL_SUMMARY && !it.second.contains("块0 句") })
    }
    @Test fun cancellationKeepsValidatedRequestsAndOldCheckpointIsIgnored() = runBlocking {
        val f = Fixture()
        f.store.write(1, TopicCheckpoint("legacy", mapOf("old" to "obsolete")))
        f.beforeReply = { if (f.calls.size == 2) throw CancellationException("停止") }
        try { f.run(group(), speech()); fail() } catch (_: CancellationException) { }
        assertEquals(1, f.store.read(1)!!.progress.blocksDone)
        f.beforeReply = {}; f.run(group(), speech())
        assertEquals(5, f.calls.size); assertEquals(3, f.store.read(1)!!.format)
    }
    @Test fun generationTimeoutIsNotTreatedAsCancellationOrInfiniteRetry() = runTest {
        val f = Fixture(); f.beforeReply = { delay(TopicBlocks.STALL + 1) }
        try { f.run(group(), speech()); fail() } catch (e: TopicStalledException) {
            assertTrue(e.message!!.contains("30 分钟"))
        }
        assertEquals(1, f.calls.size)
    }
}
