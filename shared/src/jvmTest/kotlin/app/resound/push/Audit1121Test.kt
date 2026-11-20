package app.resound.push

import app.resound.net.rejectsStream
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

class Audit1121Test {
    @Test fun `不认识的逐字稿选项不让整条推送订阅读不出来`() {
        val j = SubscriptionJson.encode(PushSubscription(audio = AudioPush.All, audioText = AudioText.Daily))
        val future = JsonObject(j + ("audioText" to JsonPrimitive("Weekly")))
        val read = SubscriptionJson.read(future)
        assertEquals(AudioPush.All, read.audio)
        assertEquals(AudioText.PerSegment, read.audioText)
    }

    @Test fun `只有明确拒绝 stream 参数才记成不支持流式`() {
        assertFalse(rejectsStream("""{"error":{"type":"upstream_error","message":"upstream error"}}"""))
        assertFalse(rejectsStream("""{"error":{"message":"Upstream service unavailable, invalid response"}}"""))
        assertTrue(rejectsStream("""{"error":{"message":"Unsupported parameter: 'stream'"}}"""))
        assertTrue(rejectsStream("""{"error":{"message":"stream_options is not supported"}}"""))
    }
}
