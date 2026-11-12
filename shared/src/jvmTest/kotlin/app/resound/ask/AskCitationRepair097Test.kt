package app.resound.ask

import app.resound.ask.core.*
import app.resound.net.*

import kotlinx.coroutines.runBlocking
import app.resound.net.Arr as JSONArray
import app.resound.net.Obj as JSONObject
import org.junit.Assert.*
import org.junit.Test

class AskCitationRepair097Test {
    private val source = AskSource("line", 42, 1000, "原话", "最终决定先试运行两周，再复盘。")
    private val context = AskContext("本轮原话 ref=1：${source.quote}", listOf(source))

    @Test fun wrongDatabaseIdAndMissingReferenceAreRepairedOnceAndUsageIsCounted() = runBlocking {
        for (first in listOf("先试运行两周[^42]", "先试运行两周")) {
            var calls = 0; var usages = 0; var total = 0L
            val updates = mutableListOf<AskEngineCore.Update>()
            val engine = engine(TestStream { messages, tools, _, usage ->
                calls++; usage(20, 4)
                if (calls == 1) reply(first, emptyList()) else {
                    assertEquals(0, tools.length())
                    assertTrue(messages.toString().contains("数据库 id 不是 ref"))
                    reply("先试运行两周，再复盘。[^1]", emptyList())
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
            var calls = 0; var result: AskEngineCore.Update? = null
            val engine = engine(TestStream { _, _, _, _ ->
                calls++
                if (unexpectedTool && calls == 2) reply("", listOf(tool("x", "propose_change", "{}")))
                else reply("结论[^999]", emptyList())
            }, JSONArray(), { _, _, _ -> error("不能执行工具") }, { _, _ -> }, { _, _ -> error("不能修改数据") })
            engine.answer("", emptyList(), "最后结论是啥", context) { result = it }
            assertEquals(2, calls); assertNotNull(result!!.error)
            assertTrue(result!!.sources.isEmpty())
        }
    }

    @Test fun repairStillFitsAfterSixToolRoundsAndCannotExpandToolBudget() = runBlocking {
        var calls = 0; var searches = 0; var result: AskEngineCore.Update? = null
        val engine = engine(TestStream { _, tools, _, _ ->
            calls++
            when {
                calls <= 6 -> reply("", listOf(tool("$calls", "search_lines", "{}")))
                calls == 7 -> { assertEquals(0, tools.length()); reply("结论[^42]", emptyList()) }
                else -> { assertEquals(0, tools.length()); reply("先试运行两周[^1]", emptyList()) }
            }
        }, JSONArray().put(JSONObject()), { _, _, sources -> searches++; if (sources.isEmpty()) sources += source; JSONObject().put("ref", 1) }, { _, _ -> })
        engine.answer("", emptyList(), "最后结论是啥") { result = it }
        assertEquals(8, calls); assertEquals(6, searches); assertNull(result!!.error)
    }
}
