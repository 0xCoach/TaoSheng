package app.resound.transcribe

import kotlinx.coroutines.runBlocking
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** #52：豆包选 Agent Plan 时连套餐专属地址（路径多 plan/），请求头与资源编号不变；普通 API 不受影响 */
class DoubaoPlan052Test {
    @get:Rule val temp = TemporaryFolder()
    @Test fun planAddsPlanSegmentAndKeepsDefaultsElsewhere() {
        assertEquals("wss://openspeech.bytedance.com/api/v3/plan/sauc/bigmodel_nostream", DoubaoConfig().plan().url)
        assertEquals(DoubaoConfig().resourceId, DoubaoConfig().plan().resourceId)
        assertEquals("wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_nostream", DoubaoConfig().url)
    }
    private fun recognize(plan: Boolean): okhttp3.mockwebserver.RecordedRequest = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    if (bytes[1].toInt() == 0x22) webSocket.send(DoubaoProtocol.frame(9, 2,
                        """{"audio_info":{"duration":200},"result":{"utterances":[{"text":"套餐","end_time":200}]}}""".toByteArray(), true).toByteString())
                }
            }))
            val config = DoubaoConfig(server.url("/api/v3/sauc/bigmodel_nostream").toString().replace("http://", "ws://"), "volc.seedasr.sauc.duration", 2000)
            val audio = AsrAudio(temp.newFile().apply { writeBytes(ByteArray(6400)) }, "pcm", 200)
            val result = DoubaoAsrProvider(AsrAudioReader(temp.root), config, plan = { plan }).recognize(audio, "plan-key") {}
            assertEquals("套餐", result.sentences.single().text)
            server.takeRequest()
        }
    }
    @Test fun requestGoesToPlanPathOnlyWhenPlanChosen() {
        val planned = recognize(true)
        assertEquals("/api/v3/plan/sauc/bigmodel_nostream", planned.path)
        assertEquals("plan-key", planned.getHeader("X-Api-Key"))
        assertEquals("volc.seedasr.sauc.duration", planned.getHeader("X-Api-Resource-Id"))
        assertEquals("/api/v3/sauc/bigmodel_nostream", recognize(false).path)
    }
}
