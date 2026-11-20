package app.resound.topic

import app.resound.digest.*
import app.resound.topic.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TopicAdaptive218Test {
    @Test fun adaptiveFailuresSplitImmediatelyAndReuseValidatedHalves() = runBlocking {
        for (type in listOf(LlmError.TooSlow, LlmError.Truncated)) {
            val store = MemoryTopicCheckpoints()
            val s = TopicProcessingSession(1, "synthetic", store, { it }, ::topicFailure, { error("不应原样重试") })
            s.begin(); s.phase("提取要点", 1)
            val calls = mutableListOf<List<Int>>()
            suspend fun read(part: List<Int>) = s.request("system", part.toString(), { _, _ ->
                calls += part
                if (part.size > 2) throw LlmException(type)
                "{}"
            }, {})
            assertEquals(listOf("{}", "{}"), s.splitRead((1..4).toList(), ::read))
            assertEquals(listOf(4, 2, 2), calls.map { it.size })
            val before = calls.size
            read(listOf(1, 2)); assertEquals(before, calls.size)
            try { s.splitRead(listOf(1)) { throw LlmException(type) }; fail() }
            catch (e: LlmException) { assertEquals(type, e.type) }
        }
    }
}
