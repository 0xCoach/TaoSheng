package app.resound.transcribe.local

import androidx.work.WorkInfo
import app.resound.ui.transcript.downloadLine
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 龟速不断开的下载要能被识别成卡住，并保留已下部分；界面不能被过时的进度文字骗成“一直在下” */
class ModelDownloadStallTest {
    @Test fun tricklingDownloadIsTreatedAsStalledAndKeepsPartialFile() = runBlocking {
        val server = MockWebServer()
        // 每 200 毫秒只给 1 KB，永远不超时
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(64 * 1024))).throttleBody(1024, 200, TimeUnit.MILLISECONDS))
        server.start()
        val target = File.createTempFile("stall-", ".part")
        try {
            val spec = ModelSpec("t", server.url("/m").toString(), 64 * 1024, "x", "m", 1, "x", false)
            try {
                downloadModelFile(spec, target, stallWindowNanos = 1_000_000_000L, stallMinBytes = 32 * 1024L)
                fail("龟速下载应当被判为卡住")
            } catch (_: IOException) {}
            assertTrue(target.length() in 1 until 64 * 1024L)
        } finally { target.delete(); server.shutdown() }
    }

    @Test fun staleProgressWithoutRunningTaskBecomesRetryable() {
        assertEquals("下载 45% · 73/162 MB", downloadLine("下载 45% · 73/162 MB", WorkInfo.State.RUNNING))
        assertEquals("下载中断（45%），点重试接着下", downloadLine("下载 45% · 73/162 MB", null))
        assertTrue(downloadLine("下载 45% · 73/162 MB", WorkInfo.State.ENQUEUED).startsWith("排队中"))
    }
}
