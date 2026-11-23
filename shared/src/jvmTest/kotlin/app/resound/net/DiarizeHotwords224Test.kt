package app.resound.net

import app.resound.transcribe.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** 卡 224（#48）：分说话人模式用 definition 字段带热词，服务不认时去掉重发并记住 */
class DiarizeHotwords224Test {
    private val upload = AudioUpload("a.wav", "wav", 2) { it(byteArrayOf(1, 2)) }
    private val ok = """{"segments":[{"start":0,"end":1,"text":"巴塘","speaker":"A"}]}"""
    @Before fun reset() = CustomPhraseList.resetForTest()
    private suspend fun run(engine: MockEngine, hotwords: List<String>, format: CustomSpeakerFormat = CustomSpeakerFormat.Diarized,
        url: String = "https://synthetic.invalid/asr"): AsrResult {
        val client = networkClient(engine, NetworkTimeouts(1000, 1000, 1000))
        return try { customRecognize(client, listOf(url), "", emptyList(), upload, 1000, "diarize", "巴塘，如美", "boundary", {},
            speakerFormat = format, hotwords = hotwords) } finally { client.close() }
    }
    private fun definitionOf(body: String) = Regex("name=\"definition\"\r\nContent-Length: \\d+\r\n\r\n(.*?)\r\n").find(body)?.groupValues?.get(1)

    @Test fun diarizedSendsPhraseListWithoutPrompt() = runBlocking {
        var body = ""
        run(MockEngine { body = it.body.toByteArray().decodeToString(); respond(ok) }, listOf(" 巴塘 ", "如美", "巴塘", "", "塌方"))
        assertEquals("""{"phraseList":{"phrases":["巴塘","如美","塌方"]}}""", definitionOf(body))
        assertFalse(body.contains("name=\"prompt\""))
    }
    @Test fun noHotwordsNoDefinition() = runBlocking {
        var body = ""
        run(MockEngine { body = it.body.toByteArray().decodeToString(); respond(ok) }, emptyList())
        assertFalse(body.contains("definition"))
    }
    @Test fun autoModeKeepsPromptAndNoDefinition() = runBlocking {
        var body = ""
        run(MockEngine { body = it.body.toByteArray().decodeToString(); respond("""{"text":"好"}""") }, listOf("巴塘"), CustomSpeakerFormat.Auto)
        assertTrue(body.contains("name=\"prompt\"")); assertFalse(body.contains("definition"))
    }
    @Test fun capsAt500() = runBlocking {
        var body = ""
        run(MockEngine { body = it.body.toByteArray().decodeToString(); respond(ok) }, (1..600).map { "词$it" })
        val def = definitionOf(body)!!
        assertTrue(def.contains("\"词500\"")); assertFalse(def.contains("\"词501\""))
    }
    @Test fun rejectedDefinitionRetriesPlainOnceAndRemembers() = runBlocking {
        val bodies = mutableListOf<String>()
        // 每次新建引擎：客户端关闭时会连带关掉引擎，不能复用
        fun engine() = MockEngine { r ->
            val b = r.body.toByteArray().decodeToString(); bodies += b
            if (b.contains("definition")) respond("Unrecognized request argument: definition", HttpStatusCode.BadRequest) else respond(ok)
        }
        assertEquals(CustomCapability.Speakers, run(engine(), listOf("巴塘")).customCapability)
        assertEquals(2, bodies.size); assertFalse(bodies[1].contains("definition"))
        bodies.clear()
        run(engine(), listOf("巴塘"))
        assertEquals(1, bodies.size); assertFalse(bodies[0].contains("definition"))
        bodies.clear()
        run(engine(), listOf("巴塘"), url = "https://other.invalid/asr")
        assertEquals(2, bodies.size)
    }
    @Test fun diarizeUnsupportedStillReportsAfterPlainRetry() = runBlocking {
        var calls = 0
        val error = runCatching { run(MockEngine { calls++; respond("unsupported", HttpStatusCode.BadRequest) }, listOf("巴塘")) }.exceptionOrNull() as AsrException
        assertEquals(2, calls); assertEquals("这个模型不支持分说话人，请把说话人改成自动", error.detail)
        assertFalse(CustomPhraseList.rejected("https://synthetic.invalid/asr", "diarize"))
    }
    @Test fun authAndRateLimitDoNotRetry() = runBlocking {
        for (status in listOf(401, 429)) {
            var calls = 0
            runCatching { run(MockEngine { calls++; respond("x", HttpStatusCode.fromValue(status)) }, listOf("巴塘")) }
            assertEquals("status $status", 1, calls)
        }
    }
}
