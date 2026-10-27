package app.resound.transcribe.local

import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class ModelDownloadSourcesTest {
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun spec(server: MockWebServer, data: ByteArray = "abcdef".toByteArray()) =
        ModelSpec("t", server.url("/primary").toString(), data.size.toLong(), sha(data), "model", data.size.toLong(), sha(data), false,
            listOf(server.url("/backup").toString()))
    private fun runCase(block: suspend (MockWebServer, File) -> Unit) = runBlocking {
        val server = MockWebServer().apply { start() }
        val target = File.createTempFile("model-sources-", ".part")
        try { block(server, target) } finally { target.delete(); server.shutdown() }
    }
    @Test fun primarySuccessDoesNotContactBackup() = runCase { server, target ->
        server.enqueue(MockResponse().setBody("abcdef"))
        downloadModelFile(spec(server), target)
        assertEquals("abcdef", target.readText())
        assertEquals(1, server.requestCount)
        assertEquals("/primary", server.takeRequest().path)
    }
    @Test fun unavailablePrimaryFallsBackAndResumesExistingPartial() = runCase { server, target ->
        target.writeText("abc")
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 3-5/6").setBody("def"))
        downloadModelFile(spec(server), target)
        assertEquals("abcdef", target.readText())
        assertEquals("bytes=3-", server.takeRequest().getHeader("Range"))
        assertEquals("/backup", server.takeRequest().let { assertEquals("bytes=3-", it.getHeader("Range")); it.path })
    }
    @Test fun truncatedPrimaryContinuesFromBackup() = runCase { server, target ->
        server.enqueue(MockResponse().setBody("abc"))
        server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 3-5/6").setBody("def"))
        downloadModelFile(spec(server), target)
        server.takeRequest()
        assertEquals("bytes=3-", server.takeRequest().getHeader("Range"))
        assertEquals("abcdef", target.readText())
    }
    @Test fun corruptPrimaryIsDiscardedBeforeBackup() = runCase { server, target ->
        server.enqueue(MockResponse().setBody("broken"))
        server.enqueue(MockResponse().setBody("abcdef"))
        downloadModelFile(spec(server), target)
        server.takeRequest()
        assertNull(server.takeRequest().getHeader("Range"))
        assertEquals("abcdef", target.readText())
    }
    @Test fun corruptPartialRestartsSameSourceFromZero() = runCase { server, target ->
        target.writeText("bad")
        server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 3-5/6").setBody("def"))
        server.enqueue(MockResponse().setBody("abcdef"))
        downloadModelFile(spec(server), target)
        server.takeRequest()
        val retry = server.takeRequest()
        assertEquals("/primary", retry.path)
        assertNull(retry.getHeader("Range"))
        assertEquals("abcdef", target.readText())
    }
    @Test fun ignoredRangeRestartsInsteadOfAppending() = runCase { server, target ->
        target.writeText("abc")
        server.enqueue(MockResponse().setBody("abcdef"))
        downloadModelFile(spec(server), target)
        assertEquals("abcdef", target.readText())
        assertEquals(1, server.requestCount)
    }
    @Test fun incorrectRangeIsRejectedBeforeWritingPartial() = runCase { server, target ->
        target.writeText("abc")
        server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 2-5/6").setBody("cdef"))
        server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 3-5/6").setBody("def"))
        downloadModelFile(spec(server), target)
        server.takeRequest()
        assertEquals("bytes=3-", server.takeRequest().getHeader("Range"))
        assertEquals("abcdef", target.readText())
    }
    @Test fun allSourcesFailKeepPartialAndRemainRetryable() = runCase { server, target ->
        target.writeText("abc")
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(503))
        try { downloadModelFile(spec(server), target); fail("must fail") } catch (_: IOException) {}
        assertEquals("abc", target.readText())
        assertEquals(2, server.requestCount)
    }
    @Test fun completeCacheIsVerifiedWithoutNetworkAndCorruptCacheIsReplaced() = runCase { server, target ->
        target.writeText("abcdef")
        downloadModelFile(spec(server), target)
        assertEquals(0, server.requestCount)
        target.writeText("broken")
        server.enqueue(MockResponse().setBody("abcdef"))
        downloadModelFile(spec(server), target)
        assertNull(server.takeRequest().getHeader("Range"))
        assertEquals("abcdef", target.readText())
    }
    @Test fun slowPrimarySwitchesToBackupWithoutLosingPartial() = runCase { server, target ->
        val data = ByteArray(64 * 1024) { (it % 251).toByte() }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/primary") return MockResponse().setBody(Buffer().write(data)).throttleBody(1024, 200, TimeUnit.MILLISECONDS)
                val start = request.getHeader("Range")!!.removePrefix("bytes=").removeSuffix("-").toInt()
                return MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes $start-${data.lastIndex}/${data.size}")
                    .setBody(Buffer().write(data, start, data.size - start))
            }
        }
        downloadModelFile(spec(server, data), target, stallWindowNanos = 1_000_000_000, stallMinBytes = 32 * 1024)
        assertArrayEquals(data, target.readBytes())
        assertEquals(2, server.requestCount)
    }
    @Test fun cancellationDoesNotStartBackup() = runCase { server, target ->
        server.enqueue(MockResponse().setBody("abcdef").setHeadersDelay(5, TimeUnit.SECONDS))
        coroutineScope {
            val job = launch(Dispatchers.IO) { downloadModelFile(spec(server), target) }
            assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(2, TimeUnit.SECONDS) })
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
        }
        assertEquals(1, server.requestCount)
    }
}
