package app.resound.transcribe

import android.app.Application
import android.content.Context
import androidx.work.OutOfQuotaPolicy
import app.resound.data.SegmentEntity
import app.resound.data.SegmentWithLines
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class AudioExport069Test {
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var scope: CoroutineScope
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("export-jobs", 0).edit().clear().commit()
        context.getSharedPreferences("export-speed-test", 0).edit().clear().commit()
        root = File(context.cacheDir, "export069-${System.nanoTime()}").apply { mkdirs() }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
    @After fun cleanup() { scope.cancel(); root.deleteRecursively() }
    private fun source(id: Long): ExportSource {
        val file = File(root, "$id.ogg").apply { writeText("source") }
        return ExportSource(SegmentWithLines(SegmentEntity(id, id * 10000, id * 10000 + 2000,
            "mic", file.path, file.length()), emptyList()), emptyList())
    }
    private open class Sink(private val target: File) : ExportPcmSink {
        var bytes = 0L
        override suspend fun append(pcm: ByteArray) {
            assertTrue(pcm.size <= 8192)
            bytes += pcm.size
            target.writeText("partial")
        }
        override suspend fun silence() { bytes += 16000 }
        override suspend fun finish() { target.writeText(bytes.toString()) }
        override fun close() {}
    }
    private fun exporter(rows: List<ExportSource> = listOf(source(1)),
        decode: suspend (File, suspend (ByteArray) -> Unit) -> Unit = { _, send -> send(ByteArray(64000)) },
        sink: (File) -> ExportPcmSink = ::Sink) =
        LocalAudioExporter(File(root, "exports"), { rows }, decode, sink)

    @Test fun twentySecondDecodeDoesNotConsumeCodecWatchdog() {
        val guard = ExportCodecWatchdog()
        guard.waited(20)
        // 解码时墙钟前进 20 秒，dequeue 没有被调用；看门狗不依赖墙钟。
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(20))
        guard.checkAlive()
        guard.output()
        guard.waited(29_999)
        guard.checkAlive()
    }

    @Test fun thirtySecondsWaitingWithoutOutputFailsEvenIfInputsContinue() {
        val guard = ExportCodecWatchdog()
        repeat(2999) { guard.waited(10); guard.checkAlive() }
        guard.waited(10)
        assertThrows(IllegalStateException::class.java) { guard.checkAlive() }
        guard.output()
        guard.checkAlive()
    }

    @Test fun boundedParallelPipelineSkipsMidstreamDamageAndKeepsSerialDuration() = runBlocking {
        val secondStarted = CompletableDeferred<Unit>()
        val rows = listOf(source(1), source(2), source(3))
        val exporter = exporter(rows, decode = { from, send ->
            if (from.name == "1.ogg") {
                send(ByteArray(2000))
                secondStarted.complete(Unit) // 编码第一块时，生产者能够继续解码。
                send(ByteArray(62000))
            } else {
                send(ByteArray(64000))
                if (from.name == "2.ogg") error("中途损坏")
            }
        }, sink = { target -> object : Sink(target) {
            override suspend fun append(pcm: ByteArray) {
                withTimeout(5000) { secondStarted.await() }
                super.append(pcm)
            }
        } })
        val result = withTimeout(5000) {
            exporter.export(ExportRequest(listOf(1, 2, 3), "并行")).toList().last()
        } as ExportProgress.Done
        assertEquals("144000", result.files.first().readText()) // 2 秒 + 0.5 秒 + 2 秒，与串行一致。
        assertTrue(result.files.first { it.extension == "txt" }.readText().contains("有 1 段录音读不出来"))
        assertTrue(root.walkTopDown().none { it.extension == "pcm" })
    }

    @Test fun startDedupeRunningDoneAndPersistenceDismissExpiry() = runBlocking {
        var now = 100L
        val queued = mutableListOf<String>()
        val exporter = exporter()
        val jobs = AndroidExportJobs(context, exporter, scope, queued::add, {}, { now })
        val request = ExportRequest(listOf(2, 1, 1), "后台")
        val id = jobs.start(request)
        assertTrue(jobs.jobs.value.single().state is ExportJobState.Running)
        assertEquals(id, jobs.start(request.copy(segmentIds = listOf(1, 2))))
        assertEquals(listOf(id), queued)
        val work = AndroidExportJobs.workRequest(id)
        assertTrue(work.workSpec.expedited)
        assertEquals(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST, work.workSpec.outOfQuotaPolicy)
        assertEquals(id, work.workSpec.input.getString("export-id"))
        assertTrue(jobs.run(id) {})
        assertTrue(jobs.jobs.value.single().state is ExportJobState.Done)
        val restored = AndroidExportJobs(context, exporter, scope, queued::add, {}, { now })
        assertTrue(restored.jobs.value.single().state is ExportJobState.Done)
        val files = (restored.jobs.value.single().state as ExportJobState.Done).files
        restored.dismiss(id)
        assertTrue(restored.jobs.value.isEmpty()); assertTrue(files.all { it.isFile })
        val another = restored.start(request)
        assertTrue(restored.run(another) {})
        now += 86_400_001
        assertTrue(AndroidExportJobs(context, exporter, scope, {}, {}, { now }).jobs.value.isEmpty())
    }

    @Test fun cancelStopsPipelineAndRemovesPartialDirectory() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val exporter = exporter(sink = { target -> object : Sink(target) {
            override suspend fun append(pcm: ByteArray) {
                super.append(pcm); entered.complete(Unit); awaitCancellation()
            }
        } })
        var runner: Job? = null
        val jobs = AndroidExportJobs(context, exporter, scope, {}, { runner?.cancel(); Unit })
        val id = jobs.start(ExportRequest(listOf(1), "取消"))
        runner = launch { jobs.run(id) {} }
        withTimeout(5000) { entered.await() }
        jobs.cancel(id)
        runner.join()
        assertEquals(ExportJobState.Cancelled, jobs.jobs.value.single().state)
        assertFalse(File(root, "exports/$id").exists())
        val restored = AndroidExportJobs(context, exporter, scope, { fail("取消不能恢复执行") }, {})
        assertEquals(ExportJobState.Cancelled, restored.jobs.value.single().state)
    }

    @Test fun interruptedRequestRestartsAndAllBadBecomesFailed() = runBlocking {
        val rows = listOf(source(1), source(2))
        val exporter = exporter(rows, decode = { _, send -> send(ByteArray(2000)); error("bad") })
        val jobs = AndroidExportJobs(context, exporter, scope, {}, {})
        val id = jobs.start(ExportRequest(listOf(1, 2), "恢复"))
        val stale = File(root, "exports/$id/old.m4a").apply { parentFile!!.mkdirs(); writeText("旧半成品") }
        val queued = mutableListOf<String>()
        val restored = AndroidExportJobs(context, exporter, scope, queued::add, {})
        assertEquals(listOf(id), queued)
        assertFalse(restored.run(id) {})
        val state = restored.jobs.value.single().state as ExportJobState.Failed
        assertTrue(state.message.contains("2 段录音均无法读取"))
        assertFalse(stale.parentFile!!.exists())
    }

    @Test fun measuredMedianPersistsAndNotificationCarriesExactJobId() {
        val prefs = context.getSharedPreferences("export-speed-test", 0)
        val speeds = ExportSpeeds(prefs)
        assertEquals(120_000L, speeds.estimate(60 * 60000L))
        (1..12).forEach { speeds.record(60000, it * 1000L) }
        assertEquals(10, prefs.getString("speeds", "")!!.split(',').size)
        assertEquals(20_000L, ExportSpeeds(prefs).estimate(120000)) // 最近 5 次为 8..12 秒，中位 10 秒。
        val job = ExportJob("test-id", "录音标题", 0, ExportJobState.Done(emptyList()))
        val notification = ExportNotifications.result(context, job)
        assertEquals("test-id", shadowOf(notification.contentIntent).savedIntent.getStringExtra(EXTRA_EXPORT_JOB))
        assertEquals("app.resound.MainActivity", shadowOf(notification.contentIntent).savedIntent.component!!.className)
        assertEquals("录音已导出 · 录音标题", notification.extras.getString("android.title"))
        val foreground = ExportNotifications.foreground(context, job.copy(state = ExportJobState.Running(.35f)))
        assertEquals(android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, foreground.foregroundServiceType)
        assertEquals("test-id", shadowOf(foreground.notification.actions.single().actionIntent).savedIntent.getStringExtra(EXTRA_EXPORT_JOB))
    }
}
