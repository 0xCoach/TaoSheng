package app.resound.update

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class UpdateEngineTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun incrementalLifecycleWaitsForUserAndRestoresReadyAfterCancellationAndRestart() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val platform = FakePlatform()
            val store = MemoryUpdateStore()
            val engine = engine(server, scope, platform, store)
            val states = CopyOnWriteArrayList<UpdateState>()
            val collector = scope.launch(Dispatchers.Unconfined) { engine.state.collect { states.add(it) } }
            try {
                server.enqueue(MockResponse().setBody(fixtureManifest().json()))
                engine.checkNow()
                assertTrue(engine.state.value.toString(), engine.state.value is UpdateState.Available)
                assertEquals(1, server.requestCount) // 检查不会下载。
                engine.checkNow(startup = true)
                assertEquals(1, server.requestCount) // 一小时内进程启动跳过。
                server.enqueue(MockResponse().setBody(fixtureManifest().json()))
                engine.checkNow()
                assertEquals(1, platform.notifications) // 同版本只通知一次。
                server.enqueue(MockResponse().setBody(Buffer().write(fixture("update.patch").readBytes())))
                engine.download()
                engine.awaitState { it is UpdateState.Ready }
                assertEquals(0, platform.installs)
                engine.install()
                withTimeout(5000) { platform.submitted.await() }
                val kinds = states.map { it.javaClass.simpleName }
                var at = -1
                for (kind in listOf("Available", "Downloading", "Assembling", "Ready", "Installing")) {
                    val next = kinds.indices.firstOrNull { it > at && kinds[it] == kind }
                    assertNotNull("$kind missing from $kinds", next); at = next!!
                }
                assertTrue(states.any { it is UpdateState.Installing && it.backingUp })
                assertTrue(states.any { it is UpdateState.Installing && !it.backingUp })
                engine.installResult(51, cancelled = true)
                assertTrue(engine.state.value is UpdateState.Ready)
                val restarted = engine(server, scope, platform, store)
                restarted.checkNow(startup = true)
                assertTrue(restarted.state.value is UpdateState.Ready)
                assertEquals(1, platform.installs)
            } finally { collector.cancel(); scope.cancel() }
        }
    }

    @Test fun assembledHashMismatchAutomaticallyDownloadsFullApkAndChangesDisplayedSize() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val full = temporary.newFile("newer.bin")
            // 保留同长度，让补丁能完整拼出旧的目标，再由新整包摘要发现不一致。
            full.writeBytes(fixture("new.bin").readBytes().apply { this[0] = (this[0] + 1).toByte() })
            val manifest = fixtureManifest(newFile = full)
            val engine = engine(server, scope, FakePlatform(), MemoryUpdateStore())
            try {
                server.enqueue(MockResponse().setBody(manifest.json()))
                server.enqueue(MockResponse().setBody(Buffer().write(fixture("update.patch").readBytes())))
                server.enqueue(MockResponse().setBody(Buffer().write(full.readBytes())))
                engine.checkNow(); engine.download()
                val ready = engine.awaitState { it is UpdateState.Ready } as UpdateState.Ready
                assertFalse(ready.info.incremental)
                assertEquals(full.length(), ready.info.downloadBytes)
                assertEquals(manifest.apk.sha256, File(temporary.root, "update/${manifest.apk.sha256}.apk").updateSha256())
                assertFalse(File(temporary.root, "update/assembled.part").exists())
                assertEquals(3, server.requestCount)
            } finally { scope.cancel() }
        }
    }

    @Test fun insufficientSpaceAndFailedMandatoryBackupPreventDownloadOrInstallationButAllowRetry() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val platform = FakePlatform().apply { backupFails = true }
            var space = 0L
            val engine = engine(server, scope, platform, MemoryUpdateStore()) { space }
            try {
                server.enqueue(MockResponse().setBody(fixtureManifest(null).json()))
                engine.checkNow(); engine.download()
                val failure = engine.awaitState { it is UpdateState.Failed } as UpdateState.Failed
                assertTrue(failure.message, failure.message.startsWith("手机空间不够，还差 "))
                assertEquals(1, server.requestCount)
                space = Long.MAX_VALUE / 2
                server.enqueue(MockResponse().setBody(Buffer().write(fixture("new.bin").readBytes())))
                engine.download(); engine.awaitState { it is UpdateState.Ready }
                engine.install()
                val backupFailure = engine.awaitState { it is UpdateState.Failed } as UpdateState.Failed
                assertEquals("更新前备份没成功，没有安装", backupFailure.message)
                assertEquals(0, platform.installs)
                platform.backupFails = false
                engine.install()
                withTimeout(5000) { platform.submitted.await() }
                assertEquals(1, platform.installs)
                assertEquals(2, platform.backups)
            } finally { scope.cancel() }
        }
    }

    private fun engine(server: MockWebServer, scope: CoroutineScope, platform: FakePlatform, store: MemoryUpdateStore,
        space: () -> Long = { Long.MAX_VALUE / 2 }) = UpdateEngine(25, "0.2.10", fixture("old.bin"),
        File(temporary.root, "update"), scope, store, remote(server), platform, { println(it) }, space)
}

internal suspend fun UpdateEngine.awaitState(predicate: (UpdateState) -> Boolean): UpdateState =
    withTimeout(5000) { state.first(predicate) }
internal class MemoryUpdateStore : UpdateStore {
    private val entries = ConcurrentHashMap<String, Any>()
    override fun string(key: String) = entries[key] as? String
    override fun long(key: String) = entries[key] as? Long ?: 0L
    override fun save(vararg values: Pair<String, Any?>) { values.forEach { (key, value) -> if (value == null) entries.remove(key) else entries[key] = value } }
}
internal class FakePlatform : UpdatePlatform {
    var notifications = 0
    var installs = 0
    var backups = 0
    var backupFails = false
    val submitted = CompletableDeferred<Unit>()
    override fun verify(apk: File, manifest: UpdateManifest) {}
    override suspend fun backup() { backups++; if (backupFails) error("snapshot_failed") }
    override fun install(apk: File, rememberSession: (Int) -> Unit) { rememberSession(51); installs++; submitted.complete(Unit) }
    override fun canInstall() = true
    override fun sessionExists(id: Int) = false
    override fun notifyAvailable(info: UpdateInfo): Boolean { notifications++; return true }
    override fun dismissNotification() {}
    override fun afterReplacement() {}
}
