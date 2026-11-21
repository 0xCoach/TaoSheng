package app.resound.net

import app.resound.transcribe.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class Qwen212Test {
    private fun fixture(name: String) = File("../docs/任务卡/212-证据/$name.json").readText()
    private val upload = AudioUpload("a.ogg", "ogg", 4) { it(byteArrayOf(1)); it(byteArrayOf(2, 3, 4)) }
    private suspend fun recognize(engine: MockEngine, duration: Long = 9000): AsrResult {
        val client = networkClient(engine, NetworkTimeouts(1000, 1000, 1000))
        return try { qwenRecognize(client, "https://synthetic.invalid/asr", "test-key", emptyList(), upload,
            duration, "qwen", emptyList(), {}) } finally { client.close() }
    }
    @Test fun diarizedFixtureUsesOneLayerMillisecondsAndFirstSeenSpeakers() {
        val body = fixture("qwen-diarized")
        val result = qwenTranscription(body, 9000, true)
        assertEquals(4, result.sentences.size)
        assertEquals(listOf("0", "0", "1", "1"), result.sentences.map { it.speakerTag })
        assertEquals(1680L, result.sentences[1].startMillis)
        assertEquals(8240L, result.sentences.last().endMillis)
        assertEquals(105L, result.inputTokens); assertEquals(83L, result.outputTokens)
        assertTrue(qwenTranscription(body, 9000, false).sentences.all { it.speakerTag == null })
        val root = Json.parseToJsonElement(body).jsonObject
        val nested = JsonObject(root + ("output" to JsonObject(root["output"]!!.jsonObject - "sentences")))
        assertEquals(result, qwenTranscription(nested.toString(), 9000, true))
    }
    @Test fun plainFixtureUsesSentencePunctuationAndTextFallback() {
        val result = qwenTranscription(fixture("qwen-plain"), 9000, true)
        // 实测夹具只有两个句号；短逗号不是断句条件。
        assertEquals(2, result.sentences.size)
        assertEquals(120L, result.sentences.first().startMillis)
        assertEquals(8160L, result.sentences.last().endMillis)
        assertTrue(result.sentences.all { it.speakerTag == null && it.text.endsWith("。") })
        assertEquals(listOf(AsrSentence(0, 3000, "跟，下周", null)),
            qwenTranscription("""{"output":{"text":"跟， 下周"}}""", 3000, true).sentences)
    }
    @Test fun badRowsAreDroppedTimesClippedSortedAndSpacingPreserved() {
        val body = """{"output":{"sentences":[
            {"begin_time":500,"end_time":1200,"text":"你 好， 下周","speaker_id":8},
            {"begin_time":0,"end_time":500,"text":"用 Kotlin 写 Hello world","speaker_id":4},
            {"begin_time":8,"end_time":8,"text":"坏句","speaker_id":9},
            {"begin_time":8,"end_time":30,"text":" "}]}}"""
        val rows = qwenTranscription(body, 1000, true).sentences
        assertEquals(listOf("1", "0"), rows.map { it.speakerTag })
        assertEquals(1000L, rows.last().endMillis)
        assertEquals(listOf("用 Kotlin 写 Hello world", "你好，下周"), rows.map { it.text })
    }
    @Test fun jsonRequestHasDataUriFormatAndFiveHundredPrioritizedWords() = runBlocking {
        val words = listOf(" 涛声 ", "", "涛声") + (1..501).map { "词$it" }
        val request = Json.parseToJsonElement(qwenRequest(upload, "model", false, words)).jsonObject
        val parameters = request["parameters"]!!.jsonObject
        assertEquals("ogg", parameters["format"]!!.jsonPrimitive.content)
        assertFalse(parameters["speaker_diarization_enabled"]!!.jsonPrimitive.boolean)
        val vocabulary = parameters["vocabulary"]!!.jsonObject
        assertEquals(500, vocabulary.size); assertEquals("涛声", vocabulary.keys.first())
        assertTrue(vocabulary.values.all { it.jsonPrimitive.int == 4 }); assertFalse(vocabulary.containsKey("词500"))
        assertFalse(request.toString().contains("language_hints"))
        assertTrue(request.toString().contains("data:audio/ogg;base64,AQIDBA=="))
        val m4a = qwenRequest(AudioUpload("a.m4a", "m4a", 0) {}, "m", true, emptyList())
        assertTrue(m4a.contains("data:audio/mp4;base64,")); assertFalse(m4a.contains("vocabulary"))
    }
    @Test fun overFiveMinutesFailsBeforeReadingReservingOrSending() = runBlocking {
        var calls = 0
        val error = runCatching { recognize(MockEngine { calls++; error("不可发送") }, 300_501) }.exceptionOrNull() as AsrException
        assertEquals(0, calls); assertFalse(error.type.retryable); assertEquals("这段超过 5 分钟，千问接口转不了", error.detail)
    }
    @Test fun httpAndServiceCodesAreClassifiedWithoutEchoingMessage() = runBlocking {
        for ((status, code, expected) in listOf(Triple(401,"InvalidApiKey",AsrError.Auth), Triple(400,"InvalidApiKey",AsrError.Auth),
            Triple(429,"",AsrError.Server), Triple(400,"Throttling.RateQuota",AsrError.Server), Triple(503,"",AsrError.Server),
            Triple(400,"DataInspectionFailed",AsrError.Protocol), Triple(400,"InvalidParameter",AsrError.Protocol))) {
            var calls = 0
            val error = runCatching { recognize(MockEngine { request ->
                calls++; assertEquals("Bearer test-key", request.headers[HttpHeaders.Authorization])
                assertEquals(ContentType.Application.Json, request.body.contentType)
                respond("""{"code":"$code","message":"test-key private audio"}""", HttpStatusCode.fromValue(status))
            }) }.exceptionOrNull() as AsrException
            assertEquals(expected, error.type); assertEquals(1, calls)
            assertFalse(error.detail.contains("test-key")); assertFalse(error.detail.contains("private audio"))
            if (code == "DataInspectionFailed") {
                assertEquals("内容没通过千问审核，这段跳过", error.detail)
                assertNull(TranscribePolicy.retryAt(error.type, 1, 0))
            }
            if (code == "InvalidParameter") assertTrue(error.detail.contains(code))
        }
    }
    private suspend fun openAi(engine: MockEngine): AsrResult {
        val client = networkClient(engine, NetworkTimeouts(1000,1000,1000))
        return try { customRecognize(client, listOf("https://synthetic.invalid/asr"), "", emptyList(), upload,
            9000, "diarize", "不应发送", "boundary", {}, speakerFormat = CustomSpeakerFormat.Diarized) } finally { client.close() }
    }
    @Test fun diarizedOpenAiSendsChunkingWithoutPromptAndParsesSpeakers() = runBlocking {
        val result = openAi(MockEngine { request ->
            val body = request.body.toByteArray().decodeToString()
            assertTrue(body.contains("diarized_json")); assertTrue(body.contains("chunking_strategy")); assertTrue(body.contains("\r\nauto\r\n"))
            assertFalse(body.contains("prompt")); assertFalse(body.contains("不应发送"))
            respond("""{"segments":[{"start":0.1,"end":2,"text":"跟， 下周","speaker":"A"},{"start":2,"end":3,"text":"好的","speaker":"B"}]}""")
        })
        assertEquals(listOf("0", "1"), result.sentences.map { it.speakerTag })
        assertEquals("跟，下周", result.sentences.first().text); assertEquals(CustomCapability.Speakers, result.customCapability)
    }
    @Test fun diarizedRejectionDoesNotFallbackOrPolluteAutoCache() = runBlocking {
        var calls = 0
        val error = runCatching { openAi(MockEngine { calls++; respond("unsupported", HttpStatusCode.BadRequest) }) }.exceptionOrNull() as AsrException
        assertEquals(1, calls); assertFalse(error.type.retryable)
        assertEquals("这个模型不支持分说话人，请把说话人改成自动", error.detail)
    }
}
