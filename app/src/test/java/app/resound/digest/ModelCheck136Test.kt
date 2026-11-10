package app.resound.digest

import app.resound.ask.AskFailure
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ModelCheck136Test {
    private val json: suspend (String, String) -> LlmReply = { _, _ -> LlmReply("{}", 0, 0) }

    @Test fun secondRoundRejectionIsUsableDegradationAndPrefetchCitationIsTested() = runBlocking {
        val transitions = mutableListOf<AskFailure?>(); var calls = 0
        val items = ModelCheck(json, transitions::add) { messages, tools ->
            when (++calls) {
                1 -> LlmStreamReply("好", emptyList())
                2 -> LlmStreamReply("", listOf(LlmToolCall("a", "search_notes", "{}")))
                3 -> throw LlmException(LlmError.Server, 400)
                else -> {
                    assertEquals(0, tools.length()); assertFalse(messages.toString().contains("tool_call_id"))
                    assertTrue(messages.toString().contains("蓝鲸")); LlmStreamReply("暗号是蓝鲸[^1]", emptyList())
                }
            }
        }.run()
        assertEquals(listOf(null, AskFailure.ToolRejected), transitions)
        assertEquals(ModelCheckStatus.Degraded, items.first { it.feature == ModelCheck.TOOLS }.status)
        assertTrue(items.first { it.feature == ModelCheck.TOOLS }.ok!!)
        assertEquals(ModelCheckStatus.Passed, items.first { it.feature == ModelCheck.CITE }.status)
        assertEquals(4, calls)
    }

    @Test fun truncationAndAuthAreNotMisreportedAsToolDegradation() = runBlocking {
        for (failure in listOf(LlmException(LlmError.Format, detail = LlmException.TRUNCATED, truncated = true), LlmException(LlmError.Auth, 401))) {
            val items = ModelCheck(json, { error("不应标记降级") }) { _, tools ->
                if (tools.length() == 0) LlmStreamReply("好", emptyList()) else throw failure
            }.run()
            val result = items.first { it.feature == ModelCheck.TOOLS }
            assertEquals(ModelCheckStatus.Failed, result.status)
            assertEquals(if (failure.truncated) LlmException.TRUNCATED else "密钥无效或没开通", result.detail)
        }
    }
}
