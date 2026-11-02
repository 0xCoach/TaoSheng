package app.resound.digest

import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class LlmCancellationAudit087Test {
    /** 模拟响应头已到、正文仍在生成时用户取消；全程只访问本地假服务。 */
    @Test fun chatJsonCancellationStopsBodyRead() = runBlocking {
        val server = MockWebServer()
        val reading = CountDownLatch(1)
        val activeCall = AtomicReference<Call>()
        val http = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun callStart(call: Call) { activeCall.set(call) }
        }).addNetworkInterceptor { chain ->
            val response = chain.proceed(chain.request())
            val body = response.body!!
            // 在阻塞读取之前发信号；OkHttp 的 responseBodyStart 在首批字节到达后才触发。
            val source = object : ForwardingSource(body.source()) {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    reading.countDown()
                    return super.read(sink, byteCount)
                }
            }.buffer()
            response.newBuilder().body(object : ResponseBody() {
                override fun contentType() = body.contentType()
                override fun contentLength() = body.contentLength()
                override fun source() = source
            }).build()
        }.build()
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"{}"}}]}""")
            .setBodyDelay(2, TimeUnit.SECONDS))
        server.start()
        var job: Job? = null
        try {
            val preset = LlmPreset("custom", "audit", server.url("/v1").toString(), "synthetic")
            job = launch(Dispatchers.IO) { LlmClient(http).chatJson(preset, "", "synthetic", "synthetic") }
            assertTrue("必须先进入正文读取阶段", reading.await(5, TimeUnit.SECONDS))
            job.cancel()
            val stopped = withTimeoutOrNull(500) { job.join(); true } ?: false
            assertTrue("取消应及时完成", stopped)
            assertTrue("应同时取消 HTTP call", activeCall.get().isCanceled())
        } finally {
            job?.cancelAndJoin()
            server.shutdown()
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }
}
