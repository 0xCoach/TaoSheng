package app.resound.topic

import app.resound.topic.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TopicProcessingTest {
    private class Bad : Exception()
    private fun session(store: TopicCheckpointStore, source: String = "speech-v1") = TopicProcessingSession(1, source, store, { it },
        { if (it is Bad) TopicFailure(TopicFailureKind.Format, "格式错误") else TopicFailure(TopicFailureKind.Temporary, "网络中断") }, {})
    @Test fun restartReusesOnlyValidatedResultsAndSourceChangesInvalidate() = runBlocking {
        val store = MemoryTopicCheckpoints(); var calls = 0
        val first = session(store); first.begin(); first.phase("整理纪要", 2)
        first.request("system", "part1", { _, _ -> calls++; "{\"ok\":1}" }, {}, { "第一段决定" }); first.advance()
        first.fail(IllegalStateException())
        val next = session(store); next.begin(); next.phase("整理纪要", 2)
        next.request("system", "part1", { _, _ -> error("不应重复调用") }, {})
        assertEquals(1, calls); assertTrue(store.read(1)!!.progress.notes.isNotEmpty())
        val changed = session(store, "speech-v2"); changed.begin()
        changed.request("system", "part1", { _, _ -> calls++; "{}" }, {})
        assertEquals(2, calls)
    }
    @Test fun fencedOutputAndRepairValidateBeforeSaving() = runBlocking {
        val store = MemoryTopicCheckpoints(); val s = session(store); s.begin(); var calls = 0
        val result = s.request("system", "part", { _, _ -> if (++calls == 1) "not-json" else "```JSON\n{\"ok\":1}\n```" }, { if (!it.startsWith("{")) throw Bad() })
        assertEquals("{\"ok\":1}", result); assertEquals(2, calls)
        assertEquals(listOf(result), store.read(1)!!.entries.values.toList())
    }
    @Test fun splitKeepsEveryLineAndDoesNotDiscardSuccessfulHalf() = runBlocking {
        val s = session(MemoryTopicCheckpoints()); s.begin()
        val parts = s.splitRead((1..9).toList()) { part -> if (part.size > 2) throw Bad() else part }
        assertEquals((1..9).toList(), parts.flatten())
        val chunks = TopicProcessingRules.chunks((1..25).toList()) { "字".repeat(500) }
        assertEquals((1..25).toList(), chunks.flatten())
        assertTrue(chunks.all { it.size <= 4 })
        val longLine = ("超长原话😀。".repeat(1000))
        val fragments = TopicProcessingRules.chunks(listOf(longLine), { _, text -> text }) { it }.flatten()
        assertEquals(longLine, fragments.joinToString(""))
        assertTrue(fragments.all { it.length <= TopicProcessingRules.CHARS - 80 && !it.last().isHighSurrogate() })
    }
    @Test fun permanentFailureStopsWithoutRepeatedCharges() = runBlocking {
        var calls = 0
        val s = TopicProcessingSession(1, "v1", MemoryTopicCheckpoints(), { it }, { TopicFailure(TopicFailureKind.Permanent, "密钥无效") }, {})
        s.begin()
        try { s.request("s", "u", { _, _ -> calls++; throw Bad() }, {}); fail() } catch (_: Bad) { }
        assertEquals(1, calls)
    }
}
