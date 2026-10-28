package app.resound.update

import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PreviewUpdateTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun release(code: Int, preview: Boolean) = fixtureManifest(null).copy(
        versionCode = code, versionName = if (preview) "0.2.12-preview.1" else "0.2.12")
    private fun source(server: MockWebServer, name: String) = UpdateSource(name, server.url("/$name.json").toString()) {
        _, file -> server.url("/$name/$file").toString()
    }
    private fun remote(official: MockWebServer, preview: MockWebServer) = UpdateRemote(
        listOf(source(official, "official")), previewSources = listOf(source(preview, "preview")))

    @Test fun parsesPreviewAndRejectsPreviewFromOfficialSource() {
        val parsed = UpdateManifest.parse(release(28, true).json())
        assertTrue(parsed.info(null).preview)
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setBody(parsed.json()))
            assertThrows(UpdateFailure::class.java) { UpdateRemote(listOf(source(server, "official"))).check() }
        }
    }

    @Test fun selectsHighestCodeAndKeepsWorkingWhenEitherChannelFails() {
        MockWebServer().use { official -> MockWebServer().use { preview ->
            official.start(); preview.start()
            val remote = remote(official, preview)
            fun check(code: Int?, previewCode: Int?): UpdateManifest {
                official.enqueue(if (code == null) MockResponse().setResponseCode(503) else MockResponse().setBody(release(code, false).json()))
                preview.enqueue(if (previewCode == null) MockResponse().setResponseCode(503) else MockResponse().setBody(release(previewCode, true).json()))
                return remote.check(true)
            }
            assertEquals(28, check(27, 28).versionCode)
            assertEquals(30, check(30, 28).versionCode)
            assertEquals(27, check(27, null).versionCode)
            assertEquals(28, check(null, 28).versionCode)
            official.enqueue(MockResponse().setBody(release(27, false).json()))
            assertEquals(27, remote.check(false).versionCode)
            assertEquals(4, preview.requestCount)
        } }
    }

    @Test fun channelSwitchReschedulesChecksAndPreviewStartupIgnoresThrottleButHonorsAutoOff() = runBlocking {
        MockWebServer().use { official -> MockWebServer().use { preview ->
            official.start(); preview.start()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val store = MemoryUpdateStore()
            val schedules = java.util.concurrent.CopyOnWriteArrayList<Pair<Boolean, Boolean>>()
            val engine = UpdateEngine(26, "0.2.11-preview.1", fixture("old.bin"), temporary.newFolder(), scope,
                store, remote(official, preview), FakePlatform(), {}, reschedule = { a, p -> schedules.add(a to p) })
            try {
                assertFalse(engine.previewChannel.value); assertTrue(engine.isPreviewBuild)
                official.enqueue(MockResponse().setBody(release(27, false).json()))
                engine.checkNow(startup = true); engine.checkNow(startup = true)
                assertEquals(1, official.requestCount)
                official.enqueue(MockResponse().setBody(release(27, false).json()))
                preview.enqueue(MockResponse().setBody(release(28, true).json()))
                engine.setPreviewChannel(true)
                engine.awaitState { it is UpdateState.Available && it.info.versionCode == 28 }
                assertEquals(true to true, schedules.last())
                assertEquals(1L, store.long("previewChannel"))
                official.enqueue(MockResponse().setBody(release(30, false).json()))
                preview.enqueue(MockResponse().setBody(release(28, true).json()))
                engine.checkNow(startup = true)
                assertEquals(30, (engine.state.value as UpdateState.Available).info.versionCode)
                engine.setAutoCheck(false); engine.checkNow(startup = true)
                assertEquals(3, official.requestCount)
                engine.setPreviewChannel(false)
                assertEquals(false to false, schedules.last())
                official.enqueue(MockResponse().setBody(release(30, false).json()))
                engine.checkNow(manual = true)
                assertEquals(2, preview.requestCount)
            } finally { scope.cancel() }
        } }
    }

    @Test fun previewDownloadUsesPreviewDirectoryAndDisablingClearsRestoredReadyPackage() = runBlocking {
        MockWebServer().use { official -> MockWebServer().use { preview ->
            official.start(); preview.start()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val store = MemoryUpdateStore().apply { save("previewChannel" to 1L) }
            val directory = temporary.newFolder()
            fun engine() = UpdateEngine(26, "0.2.11", fixture("old.bin"), directory, scope, store,
                remote(official, preview), FakePlatform(), {})
            try {
                val engine = engine()
                official.enqueue(MockResponse().setBody(release(27, false).json()))
                preview.enqueue(MockResponse().setBody(release(28, true).json()))
                preview.enqueue(MockResponse().setBody(Buffer().write(fixture("new.bin").readBytes())))
                engine.checkNow(); engine.download(); engine.awaitState { it is UpdateState.Ready }
                preview.takeRequest()
                assertTrue(preview.takeRequest().path!!.startsWith("/preview/"))
                val restarted = engine()
                restarted.setAutoCheck(false)
                restarted.setPreviewChannel(false)
                restarted.checkNow()
                assertTrue(restarted.state.value is UpdateState.Idle)
                assertNull(store.string("manifest"))
                assertEquals(1, official.requestCount)
            } finally { scope.cancel() }
        } }
    }
}
