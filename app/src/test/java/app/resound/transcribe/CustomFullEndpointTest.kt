package app.resound.transcribe

import android.content.Context
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 自定义转写：完整地址模式（MiniMax 这类不是 OpenAI 兼容的服务）。
 * 只测最关键的几条：地址原样使用、热词默认不发、响应能读懂、错误能说清楚。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = android.app.Application::class)
class CustomFullEndpointTest {
    private fun context(): Context = RuntimeEnvironment.getApplication() as Context

    private fun settings(mode: CustomEndpointMode, endpoint: String, model: String = "asr-1.0"): AndroidTranscribeSettings {
        val s = AndroidTranscribeSettings(context())
        s.setSource(TranscribeSource.Custom)
        s.saveCustomAll(endpoint, model, "", mode)
        return s
    }

    private fun audio(context: Context) = java.io.File.createTempFile("audio", ".ogg", context.cacheDir).apply { writeBytes(byteArrayOf(1, 2)) }

    @Test fun fullEndpointIsRequestedAsIs() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"text":"你好"}"""))
            val context = context()
            val s = settings(CustomEndpointMode.Full, server.url("/v1/speech_to_text").toString())
            val file = audio(context)
            try {
                val reply = CustomAsrProvider(s).recognize(AsrAudio(file, "ogg", 1000), "") {}
                assertEquals("你好", reply.sentences.single().text)
                // 原样请求：不补 /v1，也不补 /audio/transcriptions
                assertEquals("/v1/speech_to_text", server.takeRequest().path)
            } finally { file.delete() }
        }
    }

    @Test fun autoModeStillAppendsOpenAiPath() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"text":"你好"}"""))
            val context = context()
            val s = settings(CustomEndpointMode.Auto, server.url("/").toString(), "whisper")
            val file = audio(context)
            try {
                CustomAsrProvider(s).recognize(AsrAudio(file, "ogg", 1000), "") {}
                assertEquals("/v1/audio/transcriptions", server.takeRequest().path)
            } finally { file.delete() }
        }
    }

    @Test fun fullModeSkipsHotwordPromptUnlessEnabled() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"text":"你好"}"""))
            val context = context()
            val s = settings(CustomEndpointMode.Full, server.url("/v1/speech_to_text").toString())
            val file = audio(context)
            try {
                val provider = CustomAsrProvider(s) { listOf("涛声") }
                provider.recognize(AsrAudio(file, "ogg", 1000), "") {}
                assertFalse(server.takeRequest().body.readUtf8().contains("name=\"prompt\""))
                server.enqueue(MockResponse().setBody("""{"text":"你好"}"""))
                s.setCustomPrompt(true)
                CustomAsrProvider(s) { listOf("涛声") }.recognize(AsrAudio(file, "ogg", 1000), "") {}
                assertTrue(server.takeRequest().body.readUtf8().contains("name=\"prompt\""))
            } finally { file.delete() }
        }
    }

    @Test fun minimaxStyleResponseIsRead() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"text":"今天天气不错","duration":26.325,"trace_id":"0217852"}"""))
            val context = context()
            val s = settings(CustomEndpointMode.Full, server.url("/v1/speech_to_text").toString())
            val file = audio(context)
            try {
                val reply = CustomAsrProvider(s).recognize(AsrAudio(file, "ogg", 1000), "") {}
                assertEquals("今天天气不错", reply.sentences.single().text)
            } finally { file.delete() }
        }
    }

    @Test fun serviceErrorCodeBecomesFixedHint() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"base_resp":{"status_code":1004,"status_msg":"invalid api key"},"trace_id":"x"}"""))
            val context = context()
            val s = settings(CustomEndpointMode.Full, server.url("/v1/speech_to_text").toString())
            val file = audio(context)
            try {
                val e = runCatching { CustomAsrProvider(s).recognize(AsrAudio(file, "ogg", 1000), "") {} }.exceptionOrNull()
                assertTrue(e is AsrException)
                assertEquals(AsrError.Auth, (e as AsrException).type)
                // 不回显服务端原文
                assertFalse(e.detail.contains("invalid api key"))
            } finally { file.delete() }
        }
    }

    @Test fun fullEndpointRejectsUnsafeUrl() {
        assertTrue(runCatching { fullEndpoint("ftp://example.com/x") }.isFailure)
        assertTrue(runCatching { fullEndpoint("https://user@example.com/x") }.isFailure)
        assertTrue(runCatching { fullEndpoint("https://example.com/x#片段") }.isFailure)
        assertEquals("https://example.com/v1/speech_to_text", fullEndpoint("https://example.com/v1/speech_to_text"))
        assertEquals("https://example.com/v1/speech_to_text", fullEndpoint("https://example.com/v1/speech_to_text/"))
    }

    @Test fun modeSurvivesRestartAndMarksReady() {
        val context = context()
        settings(CustomEndpointMode.Full, "https://api.minimax.cn/v1/speech_to_text")
        val again = AndroidTranscribeSettings(context)
        assertEquals(CustomEndpointMode.Full, again.customEndpointMode)
        assertTrue(again.ready.value)
        assertTrue(again.identity().contains("Full"))
    }
}
