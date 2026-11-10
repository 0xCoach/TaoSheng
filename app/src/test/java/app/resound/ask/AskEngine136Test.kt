package app.resound.ask

import app.resound.digest.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class AskEngine136Test {
    private val source = AskSource("line", 42, 0, "原话", "给老王发方案")
    private val tools = JSONArray().put(JSONObject())
    private fun found(sources: MutableList<AskSource>): JSONObject {
        if (sources.isEmpty()) sources += source
        return JSONObject().put("results", JSONArray().put(JSONObject().put("ref", 1).put("quote", source.quote)))
    }

    @Test fun malformedToolRequestRetriesOnceWithPrefetchedReferencesAndCountsBothCalls() = runBlocking {
        var calls = 0; var usages = 0; var reason: AskFailure? = null
        val updates = mutableListOf<AskEngine.Update>()
        val engine = AskEngine(AskStream { messages, definitions, _, usage ->
            usage(5, 2)
            if (++calls == 1) { assertEquals(1, definitions.length()); throw LlmException(LlmError.Format) }
            assertEquals(0, definitions.length()); assertFalse(messages.toString().contains("tool_call_id"))
            assertTrue(messages.toString().contains("\\\"ref\\\":1"))
            LlmStreamReply("给老王发方案[^1]", emptyList())
        }, tools, { _, _, sources -> found(sources) }, { _, _ -> usages++ }, markFallback = { value, _ -> reason = value })
        engine.answer("", emptyList(), "昨天老王的方案", updates::add)
        assertEquals(2, calls); assertEquals(2, usages); assertEquals(AskFailure.ToolFormat, reason)
        assertEquals(AskMode.Prefetch, updates.last().mode); assertNull(updates.last().error)
        assertEquals(listOf(source), updates.last().sources)
    }

    @Test fun secondRound400RetriesWithoutToolHistoryAndDoesNotRepeatProposal() = runBlocking {
        var calls = 0; var reason: AskFailure? = null; var result: AskEngine.Update? = null
        AskEngine(AskStream { messages, definitions, _, _ ->
            when (++calls) {
                1 -> LlmStreamReply("", listOf(LlmToolCall("a", "search_lines", "{}")))
                2 -> { assertTrue(messages.toString().contains("tool_call_id")); throw LlmException(LlmError.Server, 400) }
                else -> {
                    assertEquals(0, definitions.length()); assertFalse(messages.toString().contains("tool_call_id"))
                    LlmStreamReply("给老王发方案[^1]", emptyList())
                }
            }
        }, tools, { _, _, sources -> found(sources) }, { _, _ -> }, markFallback = { value, _ -> reason = value })
            .answer("", emptyList(), "方案") { result = it }
        assertEquals(3, calls); assertEquals(AskFailure.ToolRejected, reason)
        assertEquals(AskMode.Prefetch, result!!.mode); assertNull(result!!.error)
    }

    @Test fun casualMarkerIsHiddenInBothModesButUncitedPersonalAnswerStillFails() = runBlocking {
        for (mode in AskMode.entries) {
            val updates = mutableListOf<AskEngine.Update>()
            AskEngine(AskStream { _, _, delta, _ ->
                delta("[无需"); delta("记录]你好！"); LlmStreamReply("[无需记录]你好！", emptyList())
            }, tools, { _, _, _ -> JSONObject() }, { _, _ -> }, mode = { mode })
                .answer("", emptyList(), "你好", updates::add)
            assertEquals("你好！", updates.last().text); assertNull(updates.last().error)
            assertTrue(updates.none { it.text.contains("[无") })
        }
        var count = 0; var result: AskEngine.Update? = null
        AskEngine(AskStream { _, _, _, _ -> count++; LlmStreamReply("你明天要开会", emptyList()) }, tools,
            { _, _, _ -> JSONObject() }, { _, _ -> }).answer("", emptyList(), "明天干嘛") { result = it }
        assertEquals(2, count); assertNotNull(result!!.error); assertEquals(AskMode.Prefetch, result!!.mode)
    }

    @Test fun localDateRulesCoverRequestedWordsAndLengthNeverTriggersFallback() = runBlocking {
        val today = LocalDate.of(2026, 11, 10)
        val ranges = listOf(
            "今天上午" to ("2026-11-10 00:00" to "2026-11-10 11:59"),
            "昨天下午" to ("2026-11-09 12:00" to "2026-11-09 17:59"),
            "前天晚上" to ("2026-11-08 18:00" to "2026-11-08 23:59"),
            "本周" to ("2026-11-09 00:00" to "2026-11-15 23:59"),
            "上周" to ("2026-11-02 00:00" to "2026-11-08 23:59"),
            "这个月" to ("2026-11-01 00:00" to "2026-11-30 23:59"))
        ranges.forEach { (question, expected) ->
            val actual = AskPrefetch.timeRange(question, today)
            assertEquals(expected.first, actual.getString("from")); assertEquals(expected.second, actual.getString("to"))
        }
        assertEquals(0, AskPrefetch.timeRange("方案", today).length())
        var reason: AskFailure? = null
        val failure = runCatching {
            AskEngine(AskStream { _, _, _, _ -> throw LlmException(LlmError.Format, detail = LlmException.TRUNCATED, truncated = true) },
                tools, { _, _, _ -> error("不应查询") }, { _, _ -> }, markFallback = { _, _ -> error("不应降级") }, recordFailure = { reason = it })
                .answer("", emptyList(), "问题") {}
        }.exceptionOrNull() as LlmException
        assertEquals(LlmException.TRUNCATED, failure.detail); assertEquals(AskFailure.Truncated, reason)
    }
}
