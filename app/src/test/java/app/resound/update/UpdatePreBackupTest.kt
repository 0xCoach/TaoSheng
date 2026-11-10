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
import java.util.concurrent.CopyOnWriteArrayList

class UpdatePreBackupTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun fullDownloadRecordsCompletedBackupAndRestartReusesIt() = runBlocking {
        scenario {
            platform.duringBackup = {
                assertTrue(engine.state.value is UpdateState.Ready)
                clock += 2_000
            }
            download()
            finishBackup()
            assertEquals(1, platform.backups)
            assertEquals(release.apk.sha256, store.string("preBackupFor"))
            assertEquals(clock, store.long("preBackupAt"))
            assertTrue(logs.any { it.startsWith("pre_backup ms=") && it.endsWith("result=ok") })
            clock += SIX_HOURS - 1
            val restarted = newEngine()
            restarted.install()
            awaitInstall()
            assertEquals(1, platform.backups)
            assertTrue(logs.contains("install_backup reused=true backup_ms=0"))
            assertFalse((restarted.state.value as UpdateState.Installing).backingUp)
        }
    }

    @Test fun cancelledInstallAtSixHourBoundaryBacksUpAgain() = runBlocking {
        scenario {
            download(); finishBackup()
            engine.install(); awaitInstall()
            engine.installResult(51, cancelled = true)
            clock += SIX_HOURS
            engine.install()
            // 同一 gate 作完成屏障；submitted 在第一次安装时已经完成。
            engine.checkNow()
            withTimeout(5000) {
                engine.awaitState { it is UpdateState.Installing }
                engine.checkNow()
            }
            assertEquals(2, platform.backups)
            assertEquals(2, platform.installs)
            assertEquals(clock, store.long("preBackupAt"))
            assertTrue(logs.any { it.startsWith("install_backup reused=false backup_ms=") })
        }
    }

    @Test fun freshBackupForDifferentShaIsNotReused() = runBlocking {
        scenario {
            download(); finishBackup()
            store.save("preBackupFor" to "0".repeat(64))
            engine.install(); awaitInstall()
            assertEquals(2, platform.backups)
            assertEquals(release.apk.sha256, store.string("preBackupFor"))
        }
    }

    @Test fun failedPreBackupKeepsReadyAndInstallationRetries() = runBlocking {
        scenario {
            platform.backupFails = true
            download(); finishBackup()
            assertTrue(engine.state.value is UpdateState.Ready)
            assertEquals(1, platform.backups)
            assertNull(store.string("preBackupFor"))
            assertEquals(0L, store.long("preBackupAt"))
            assertTrue(logs.any { it.startsWith("pre_backup ms=") && "failure=" in it })
            platform.backupFails = false
            engine.install(); awaitInstall()
            assertEquals(2, platform.backups)
            assertEquals(1, platform.installs)
        }
    }

    @Test fun installationWaitsForRunningPreBackupWithoutStartingAnother() = runBlocking {
        scenario {
            val entered = CompletableDeferred<Unit>()
            val releaseBackup = CompletableDeferred<Unit>()
            platform.duringBackup = { entered.complete(Unit); releaseBackup.await() }
            download()
            withTimeout(5000) { entered.await() }
            engine.install()
            assertNull(withTimeoutOrNull(150) { platform.submitted.await() })
            assertEquals(1, platform.backups)
            // 点了安装但后台备份还没完：界面显示“正在保存”，不像没点上
            assertTrue((engine.state.value as? UpdateState.Installing)?.backingUp == true)
            releaseBackup.complete(Unit)
            awaitInstall()
            assertEquals(1, platform.backups)
            assertEquals(1, platform.installs)
        }
    }

    @Test fun cancelledPreBackupKeepsReadyWithoutRecordingSuccess() = runBlocking {
        scenario {
            platform.duringBackup = { throw CancellationException("cancel_backup") }
            download(); finishBackup()
            assertTrue(engine.state.value is UpdateState.Ready)
            assertNull(store.string("preBackupFor"))
            assertEquals(0L, store.long("preBackupAt"))
            platform.duringBackup = {}
            engine.install(); awaitInstall()
            assertEquals(2, platform.backups)
        }
    }

    private suspend fun scenario(block: suspend Scenario.() -> Unit) {
        MockWebServer().use { server ->
            server.start()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try { Scenario(server, scope).block() }
            finally { scope.coroutineContext.job.cancelAndJoin() }
        }
    }

    private inner class Scenario(val server: MockWebServer, val scope: CoroutineScope) {
        val release = fixtureManifest(null)
        val store = MemoryUpdateStore().apply { save("autoOff" to 1L) }
        val platform = FakePlatform()
        val logs = CopyOnWriteArrayList<String>()
        var clock = 1_800_000_000_000L
        val engine = newEngine()

        fun newEngine() = UpdateEngine(25, "0.2.10", fixture("old.bin"),
            File(temporary.root, "update"), scope, store, remote(server), platform,
            { logs.add(it) }, { Long.MAX_VALUE / 2 }, { clock })

        suspend fun download() {
            server.enqueue(MockResponse().setBody(release.json()))
            server.enqueue(MockResponse().setBody(Buffer().write(fixture("new.bin").readBytes())))
            engine.checkNow(manual = true)
            engine.download()
            engine.awaitState { it is UpdateState.Ready }
        }

        // 自动检查已关闭：只等待 gate，不请求网络、不改变 Ready。
        suspend fun finishBackup() { withTimeout(5000) { engine.checkNow() } }
        suspend fun awaitInstall() { withTimeout(5000) { platform.submitted.await() } }
    }

    companion object { private const val SIX_HOURS = 6 * 60 * 60 * 1000L }
}
