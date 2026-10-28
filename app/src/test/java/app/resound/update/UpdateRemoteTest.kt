package app.resound.update

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

class UpdateRemoteTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun resumesInterruptedBodyFromTheSavedRange() {
        MockWebServer().use { server ->
            val bytes = fixture("new.bin").readBytes()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val range = request.getHeader("Range")
                    if (range == null) return MockResponse().setBody(Buffer().write(bytes))
                        .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
                    val start = range.removePrefix("bytes=").removeSuffix("-").toInt()
                    return MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes $start-${bytes.lastIndex}/${bytes.size}")
                        .setBody(Buffer().write(bytes, start, bytes.size - start))
                }
            }
            server.start()
            val file = File(temporary.root, "resume.apk")
            remote(server).download(fixtureManifest().apk, "0.2.11", file, false) {}
            assertArrayEquals(bytes, file.readBytes())
            assertEquals(2, server.requestCount)
            assertNull(server.takeRequest().getHeader("Range"))
            val offset = server.takeRequest().getHeader("Range")!!.removePrefix("bytes=").removeSuffix("-").toInt()
            assertTrue(offset in 1 until bytes.size)
        }
    }

    @Test fun deletesBadHashRetriesOnceAndReportsIntegrityFailure() {
        MockWebServer().use { server ->
            server.start()
            val bad = fixture("new.bin").readBytes().apply { this[0] = (this[0] + 1).toByte() }
            repeat(2) { server.enqueue(MockResponse().setBody(Buffer().write(bad))) }
            val file = File(temporary.root, "bad.apk")
            val error = assertThrows(UpdateFailure::class.java) {
                remote(server).download(fixtureManifest().apk, "0.2.11", file, false) {}
            }
            assertEquals(UPDATE_INTEGRITY_ERROR, error.human)
            assertEquals(2, server.requestCount)
            assertFalse(file.exists())
            repeat(2) { assertNull(server.takeRequest().getHeader("Range")) }
        }
    }

    @Test fun modelscopeFailureFallsBackToGithubForManifestAndDownload() {
        MockWebServer().use { first -> MockWebServer().use { second ->
            first.start(); second.start()
            first.enqueue(MockResponse().setBody("{\"versionCode\":26}"))
            second.enqueue(MockResponse().setBody(fixtureManifest().json()))
            val logs = mutableListOf<String>()
            val remote = UpdateRemote(listOf(source(first, "modelscope"), source(second, "github")), logs::add)
            val manifest = remote.check()
            assertEquals(26, manifest.versionCode)
            assertTrue(logs[0].contains("source=modelscope"))
            assertTrue(logs[1].contains("source=github"))
            repeat(2) { first.enqueue(MockResponse().setResponseCode(503)) }
            second.enqueue(MockResponse().setBody(Buffer().write(fixture("new.bin").readBytes())))
            val file = File(temporary.root, "fallback.apk")
            remote.download(manifest.apk, manifest.versionName, file, false) {}
            assertEquals(manifest.apk.sha256, file.updateSha256())
            assertEquals(3, first.requestCount)
            assertEquals(2, second.requestCount)
        } }
    }
}

internal fun source(server: MockWebServer, name: String = "test") = UpdateSource(name, server.url("/update.json").toString()) {
    version, file -> server.url("/v$version/$file").toString()
}
internal fun remote(server: MockWebServer) = UpdateRemote(listOf(source(server)), log = { println(it) }, client = OkHttpClient.Builder()
    .readTimeout(3, TimeUnit.SECONDS).build())
