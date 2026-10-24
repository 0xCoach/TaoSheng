package app.resound.transcribe

import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.util.concurrent.atomic.AtomicInteger

class DoubaoAsrProviderTest {
    @get:Rule val temp = TemporaryFolder()
    private fun audio() = AsrAudio(temp.newFile().apply { writeBytes(ByteArray(6400)) }, "pcm", 200)
    private fun provider(server: MockWebServer, timeout: Long = 2000) = DoubaoAsrProvider(AsrAudioReader(temp.root),
        DoubaoConfig(server.url("/asr").toString().replace("http://", "ws://"), "test-resource", timeout))
    @Test fun timeoutGrowsWithDurationAndKeepsShortAudioMargin() {
        val config = DoubaoConfig()
        assertEquals(90_000L, config.timeoutFor(3_000))
        assertEquals(135_000L, config.timeoutFor(70_000))
        assertEquals(255_000L, config.timeoutFor(150_000))
        assertEquals(465_000L, config.timeoutFor(290_000))
        assertEquals(480_000L, config.timeoutFor(300_000))
        assertEquals(300L, DoubaoConfig(timeoutMillis = 300).timeoutFor(300_000))
    }
    @Test fun fiveMinuteAudioUploadsCompletelyAndKeepsFinalSentenceTime() = runBlocking {
        // 本地假服务验证真实 Provider 的长上传/队列背压与尾句时间，不冒充云端准确率实测。
        val bytesExpected = 300 * 16000 * 2
        val source = AsrAudio(temp.newFile().apply { writeBytes(ByteArray(bytesExpected)) }, "pcm", 300_000)
        val received = AtomicInteger()
        MockWebServer().use { server ->
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    if (bytes[1].toInt() ushr 4 != 2) return
                    received.addAndGet(bytes.size - 8)
                    if (bytes[1].toInt() == 0x22) webSocket.send(DoubaoProtocol.frame(9, 2,
                        """{"audio_info":{"duration":300000},"result":{"utterances":[{"text":"最后一句","start_time":299000,"end_time":300000}]}}""".toByteArray(), true).toByteString())
                }
            }))
            val config = DoubaoConfig(url = server.url("/asr").toString().replace("http://", "ws://"))
            var reservations = 0
            val result = withTimeout(10_000) {
                DoubaoAsrProvider(AsrAudioReader(temp.root), config).recognize(source, "synthetic-key") { reservations++ }
            }
            assertEquals(bytesExpected, received.get())
            assertEquals(1, reservations)
            assertEquals(299_000L, result.sentences.single().startMillis)
            assertEquals(300_000L, result.sentences.single().endMillis)
        }
    }
    @Test fun onlyFinalCompletesAndRequestUsesExpectedHeaders() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    if (bytes[1].toInt() == 0x22) webSocket.send(DoubaoProtocol.frame(9, 2,
                        """{"audio_info":{"duration":200},"result":{"utterances":[{"text":"测试","end_time":200}]}}""".toByteArray(), true).toByteString())
                }
            }).addHeader("X-Tt-Logid", "safe-log"))
            var reserved = 0
            val result = provider(server).recognize(audio(), "synthetic-key") { reserved++ }
            assertEquals(1, reserved)
            assertEquals("测试", result.sentences.single().text)
            val request = server.takeRequest()
            assertEquals("synthetic-key", request.getHeader("X-Api-Key"))
            assertEquals("test-resource", request.getHeader("X-Api-Resource-Id"))
            assertNotNull(request.getHeader("X-Api-Connect-Id"))
        }
    }
    @Test fun partialThenCloseFailsWithoutSavingPartial() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    if (bytes[1].toInt() == 0x22) {
                        webSocket.send(DoubaoProtocol.frame(9, 0, """{"result":{"text":"未完成"}}""".toByteArray(), true).toByteString())
                        webSocket.close(1000, "")
                    }
                }
            }))
            val failure = runCatching { provider(server).recognize(audio(), "synthetic-key") {} }.exceptionOrNull() as AsrException
            assertEquals(AsrError.Network, failure.type)
        }
    }
    @Test fun authHandshakeDoesNotReserveAndDoesNotEchoCredentials() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403).setBody("synthetic-key").addHeader("X-Tt-Logid", "synthetic-key"))
            val count = AtomicInteger()
            val error = runCatching { provider(server).recognize(audio(), "synthetic-key") { count.incrementAndGet() } }.exceptionOrNull() as AsrException
            assertEquals(AsrError.Auth, error.type)
            assertEquals(0, count.get())
            assertEquals("密钥无效或未开通", error.message)
            assertNull(error.logId); assertNull(error.cause)
        }
    }
    @Test fun noFinalTimesOut() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {}))
            val failure = runCatching { provider(server, 300).recognize(audio(), "synthetic-key") {} }.exceptionOrNull() as AsrException
            assertEquals(AsrError.Timeout, failure.type)
        }
    }
    @Test fun cancellationClosesRequest() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {}))
            val job = launch { provider(server).recognize(audio(), "synthetic-key") {} }
            delay(100)
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
        }
    }
    @Test fun oggPagesPreserveEveryOriginalByteAndOnlyLastFlag() = runBlocking {
        val original = java.io.File("src/main/assets/asr-test.ogg").readBytes()
        val file = temp.newFile().apply { writeBytes(original) }
        val received = java.io.ByteArrayOutputStream()
        val flags = mutableListOf<Boolean>()
        AsrAudioReader.chunks(AsrAudioReader.Prepared(file, "ogg", false)) { bytes, last ->
            assertEquals("OggS", bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII))
            received.write(bytes); flags.add(last)
        }
        assertArrayEquals(original, received.toByteArray())
        assertTrue(flags.last()); assertTrue(flags.dropLast(1).none { it })
    }
}
