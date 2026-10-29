package app.resound.transcribe

import android.content.Context
import app.resound.digest.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.ConnectException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = android.app.Application::class)
class CustomHttp055Test {
    @Test fun httpsKind() { assertEquals(EndpointKind.Https, OpenAiEndpoint.kind("https://example.com")) }
    @Test fun lanKind() { assertEquals(EndpointKind.LocalHttp, OpenAiEndpoint.kind("http://192.168.1.5:11434")) }
    @Test fun publicHttpKind() { assertEquals(EndpointKind.PublicHttp, OpenAiEndpoint.kind("http://example.com")) }
    @Test fun localBaseAddsVersion() { assertEquals(listOf("http://192.168.1.5:11434/v1", "http://192.168.1.5:11434"), OpenAiEndpoint.bases("http://192.168.1.5:11434")) }
    @Test fun customSettingsWithoutKeysAreReadyAfterReload() {
        val context = RuntimeEnvironment.getApplication() as Context
        val asr = AndroidTranscribeSettings(context)
        asr.setSource(TranscribeSource.Custom)
        asr.saveCustomAll("http://192.168.1.5:11434", "whisper", "")
        assertTrue(asr.ready.value); assertTrue(asr.hasKey.value)
        assertTrue(AndroidTranscribeSettings(context).ready.value)
        val digest = AndroidDigestSettings(context, LlmClient())
        digest.setPreset("custom")
        digest.saveCustomAll("http://192.168.1.5:11434", "qwen", "")
        assertTrue(digest.hasKey.value)
        assertTrue(AndroidDigestSettings(context, LlmClient()).hasKey.value)
        digest.setPreset("deepseek"); asr.setSource(TranscribeSource.Doubao)
        assertFalse(digest.hasKey.value); assertFalse(asr.ready.value)
    }
    @Test fun digestOmitsAuthorization() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"{}"}}]}"""))
            val reply = LlmClient().chatJson(LlmPreset("custom", "自定义", server.url("/").toString(), "qwen"), "", "JSON", "hello")
            assertEquals("{}", reply.content)
            val request = server.takeRequest()
            assertNull(request.getHeader("Authorization")); assertEquals("/v1/chat/completions", request.path)
        }
    }
    @Test fun transcriptionOmitsAuthorization() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"text":"你好"}"""))
            val settings = AndroidTranscribeSettings(context)
            settings.setSource(TranscribeSource.Custom)
            settings.saveCustomAll(server.url("/").toString(), "whisper", "")
            val file = java.io.File.createTempFile("audio", ".wav", context.cacheDir).apply { writeBytes(byteArrayOf(1,2)) }
            try {
                val reply = CustomAsrProvider(settings).recognize(AsrAudio(file,"wav",1000), "") {}
                assertEquals("你好", reply.sentences.single().text)
                val request = server.takeRequest()
                assertNull(request.getHeader("Authorization")); assertEquals("/v1/audio/transcriptions", request.path)
            } finally { file.delete() }
        }
    }
    @Test fun refusedConnectionHasSafeActionableMessage() {
        assertEquals("连不上 192.168.1.5:11434：确认服务已启动，手机和它在同一网络。Ollama 需在电脑上设置 OLLAMA_HOST=0.0.0.0 才能被局域网访问",
            modelServiceError("http://192.168.1.5:11434/v1", ConnectException("secret"), "失败"))
    }
}
