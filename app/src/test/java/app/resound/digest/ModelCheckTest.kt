package app.resound.digest

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelCheckTest {
    private val okJson: suspend (String, String) -> LlmReply = { _, _ -> LlmReply("{\"ok\":true}", 1, 1) }
    private fun result(items: List<ModelCheckItem>) = items.associate { it.feature to (it.ok to it.detail) }

    @Test fun fullySupportedModelPassesEveryFeature() = runBlocking {
        val items = ModelCheck(okJson) { messages, tools ->
            when {
                tools.length() == 0 -> LlmStreamReply("好", emptyList())
                messages.toString().contains("\"tool\"") -> LlmStreamReply("暗号是蓝鲸[^1]", emptyList())
                else -> LlmStreamReply("", listOf(LlmToolCall("a", "search_notes", "{\"keywords\":[\"暗号\"]}")))
            }
        }.run()
        assertEquals(5, items.size)
        assertEquals(listOf(true, true, true, true, true), items.map { it.ok })
    }

    @Test fun modelIgnoringToolsIsReportedAsNoToolSupport() = runBlocking {
        val r = result(ModelCheck(okJson) { _, _ -> LlmStreamReply("暗号是芝麻开门", emptyList()) }.run())
        assertEquals(false, r[ModelCheck.TOOLS]!!.first)
        assertEquals(true, r[ModelCheck.TOOLS]!!.second.startsWith("模型不支持工具调用"))
        assertEquals(null, r[ModelCheck.CITE]!!.first)
    }

    @Test fun serverRejectingToolsIsReportedAsNoToolSupport() = runBlocking {
        val r = result(ModelCheck(okJson) { _, tools ->
            if (tools.length() > 0) throw LlmException(LlmError.Server, 400) else LlmStreamReply("好", emptyList())
        }.run())
        assertEquals(false to "模型不支持工具调用", r[ModelCheck.TOOLS])
    }

    @Test fun answerWithoutMarkerFailsCitation() = runBlocking {
        val r = result(ModelCheck(okJson) { messages, tools ->
            if (tools.length() > 0 && !messages.toString().contains("\"tool\"")) LlmStreamReply("", listOf(LlmToolCall("a", "search_notes", "{}")))
            else LlmStreamReply("暗号是蓝鲸", emptyList())
        }.run())
        assertEquals(true, r[ModelCheck.TOOLS]!!.first)
        assertEquals(false to "模型不会按要求标注出处，问问会提示缺少出处", r[ModelCheck.CITE])
    }

    @Test fun badKeyStopsAtConnection() = runBlocking {
        val items = ModelCheck(okJson) { _, _ -> throw LlmException(LlmError.Auth, 401) }.run()
        assertEquals(false to "密钥无效或没开通", items.first().ok to items.first().detail)
        assertEquals(listOf(null, null, null, null), items.drop(1).map { it.ok })
    }

    @Test fun noStreamingButJsonWorks() = runBlocking {
        val r = result(ModelCheck(okJson) { _, _ -> throw LlmException(LlmError.Format) }.run())
        assertEquals(true, r[ModelCheck.CONNECT]!!.first)
        assertEquals(true, r[ModelCheck.JSON]!!.first)
        assertEquals(false to "模型不支持流式输出，问问没法用", r[ModelCheck.STREAM])
        assertEquals(null, r[ModelCheck.TOOLS]!!.first)
    }

    @Test fun jsonModeRejected() = runBlocking {
        val r = result(ModelCheck({ _, _ -> throw LlmException(LlmError.Server, 400) }) { _, _ -> LlmStreamReply("好", emptyList()) }.run())
        assertEquals(false to "模型不支持按格式输出（JSON），整理、回顾、技能会失败", r[ModelCheck.JSON])
    }
}
