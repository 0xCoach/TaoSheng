package app.resound.ask

import app.resound.digest.LlmStreamReply
import app.resound.digest.LlmToolCall
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AskCitationRepair097Test {
    private val source = AskSource("line", 42, 1000, "原话", "最终决定先试运行两周，再复盘。")
    private val context = AskContext("本轮原话 ref=1：${source.quote}", listOf(source))

    @Test fun wrongDatabaseIdAndMissingReferenceAreRepairedOnceAndUsageIsCounted() = runBlocking {
        for (first in listOf("先试运行两周[^42]", "先试运行两周")) {
            var calls = 0; var usages = 0; var total = 0L
            val updates = mutableListOf<AskEngine.Update>()
            val engine = AskEngine(AskStream { messages, tools, _, usage ->
                calls++; usage(20, 4)
                if (calls == 1) LlmStreamReply(first, emptyList()) else {
                    assertEquals(0, tools.length())
                    assertTrue(messages.toString().contains("数据库 id 不是 ref"))
                    LlmStreamReply("先试运行两周，再复盘。[^1]", emptyList())
                }
            }, JSONArray().put(JSONObject()), { _, _, _ -> error("不能调用工具") }, { p, c -> usages++; total += p + c })
            engine.answer("", emptyList(), "最后结论是啥", context, updates::add)
            assertEquals(2, calls); assertEquals(2, usages); assertEquals(48L, total)
            assertTrue(updates.any { it.progress == "正在核对回答出处…" })
            assertNull(updates.last().error); assertEquals(listOf(source), updates.last().sources)
        }
    }

    @Test fun repeatedBadCitationsAndUnexpectedToolCallsStopWithoutLoopingOrChangingData() = runBlocking {
        for (unexpectedTool in listOf(false, true)) {
            var calls = 0; var result: AskEngine.Update? = null
            val engine = AskEngine(AskStream { _, _, _, _ ->
                calls++
                if (unexpectedTool && calls == 2) LlmStreamReply("", listOf(LlmToolCall("x", "propose_change", "{}")))
                else LlmStreamReply("结论[^999]", emptyList())
            }, JSONArray(), { _, _, _ -> error("不能执行工具") }, { _, _ -> }, { _, _ -> error("不能修改数据") })
            engine.answer("", emptyList(), "最后结论是啥", context) { result = it }
            assertEquals(2, calls); assertNotNull(result!!.error)
            assertTrue(result!!.sources.isEmpty())
        }
    }

    @Test fun repairStillFitsAfterSixToolRoundsAndCannotExpandToolBudget() = runBlocking {
        var calls = 0; var searches = 0; var result: AskEngine.Update? = null
        val engine = AskEngine(AskStream { _, tools, _, _ ->
            calls++
            when {
                calls <= 6 -> LlmStreamReply("", listOf(LlmToolCall("$calls", "search_lines", "{}")))
                calls == 7 -> { assertEquals(0, tools.length()); LlmStreamReply("结论[^42]", emptyList()) }
                else -> { assertEquals(0, tools.length()); LlmStreamReply("先试运行两周[^1]", emptyList()) }
            }
        }, JSONArray().put(JSONObject()), { _, _, sources -> searches++; if (sources.isEmpty()) sources += source; JSONObject().put("ref", 1) }, { _, _ -> })
        engine.answer("", emptyList(), "最后结论是啥") { result = it }
        assertEquals(8, calls); assertEquals(6, searches); assertNull(result!!.error)
    }
}
