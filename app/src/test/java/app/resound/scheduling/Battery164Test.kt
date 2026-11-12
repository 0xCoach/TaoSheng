package app.resound.scheduling

import android.app.Application
import android.content.Context
import app.resound.onboarding.LocalOnboarding
import app.resound.recordings.AndroidRecordingPacker
import app.resound.redo.*
import app.resound.data.CaptureDatabase
import androidx.room.Room
import app.resound.transcribe.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class Battery164Test {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    @Test fun asrChooserRefreshesOnSubscriptionAndStopsAfterLeaving() = runTest {
        val prefs = context.getSharedPreferences("redo-choices", 0)
        prefs.edit().clear().commit()
        val chooser = AndroidAsrChooser(context, AndroidTranscribeSettings(context), backgroundScope) { null }
        runCurrent()
        val old = chooser.lastChoice.value
        val changed = TranscribeSource.entries.first { it != old }
        prefs.edit().putString("asr", changed.name).commit()
        advanceTimeBy(10_000); runCurrent(); assertEquals(old, chooser.lastChoice.value)
        val shown = backgroundScope.launch { chooser.options.collect() }; runCurrent()
        assertEquals(changed, chooser.lastChoice.value)
        shown.cancel(); runCurrent(); prefs.edit().putString("asr", old.name).commit()
        advanceTimeBy(10_000); runCurrent(); assertEquals(changed, chooser.lastChoice.value)
    }
    @Test fun recordingDaysDoNotScanUntilSubscribedAndResumeImmediately() = runTest {
        var scans = 0
        val packer = AndroidRecordingPacker(context, { emptyList() }, backgroundScope,
            enqueue = {}, metadata = { scans++; emptyList() }, refreshDispatcher = StandardTestDispatcher(testScheduler))
        runCurrent(); advanceTimeBy(3_600_000); assertEquals(0, scans)
        val shown = backgroundScope.launch { packer.availableDays.collect() }; runCurrent(); assertEquals(1, scans)
        advanceTimeBy(30_000); runCurrent(); assertEquals(2, scans)
        shown.cancel(); runCurrent(); advanceTimeBy(3_600_000); assertEquals(2, scans)
        backgroundScope.launch { packer.availableDays.collect() }; runCurrent(); assertEquals(3, scans)
    }
    @Test fun exportPrunesOnlyWhileThereAreJobsAndStopsWhenEmpty() = runTest {
        var clockReads = 0
        val prefs = context.getSharedPreferences("export-jobs", 0); prefs.edit().clear().commit()
        val exporter = object : AudioExporter {
            override suspend fun estimate(request: ExportRequest): ExportEstimate = error("unused")
            override fun export(request: ExportRequest): Flow<ExportProgress> = emptyFlow()
            override fun cleanup() = Unit
        }
        val jobs = AndroidExportJobs(context, exporter, backgroundScope, enqueue = {}, stop = {}, now = { clockReads++; testScheduler.currentTime })
        runCurrent(); val idle = clockReads
        advanceTimeBy(3_600_000); runCurrent(); assertEquals(idle, clockReads)
        val id = jobs.start(ExportRequest(listOf(1), "test")); runCurrent()
        jobs.update(id, ExportJobState.Failed("test")); runCurrent()
        val active = clockReads
        advanceTimeBy(60_000); runCurrent(); assertTrue(clockReads > active)
        advanceTimeBy(86_400_001); runCurrent(); assertTrue(jobs.jobs.value.isEmpty())
        val empty = clockReads
        advanceTimeBy(3_600_000); runCurrent(); assertEquals(empty, clockReads)
    }
    @Test fun onboardingDoesNotKeepTranscriptClockAliveInBackground() = runTest {
        var ticks = 0
        val heartbeat = flow { while (true) { ticks++; emit(testScheduler.currentTime); delay(60_000) } }
        val subject = LocalOnboarding(context.getSharedPreferences("onboarding164", 0), context.getSharedPreferences("backup164", 0),
            MutableStateFlow<Long?>(1), MutableStateFlow<Long?>(null), MutableStateFlow(false), heartbeat, backgroundScope,
            now = { testScheduler.currentTime })
        runCurrent(); advanceTimeBy(3_600_000); assertEquals(0, ticks)
        val shown = backgroundScope.launch { subject.tips.collect() }; runCurrent(); assertEquals(1, ticks)
        advanceTimeBy(60_000); runCurrent(); assertEquals(2, ticks)
        shown.cancel(); runCurrent(); advanceTimeBy(3_600_000); assertEquals(2, ticks)
    }
    @Test fun redoOptionsStopWhenHiddenAndHistoryExpiresAtDeadline() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java)
            .allowMainThreadQueries().setQueryCoroutineContext(StandardTestDispatcher(testScheduler)).build()
        val owner = CoroutineScope(SupervisorJob(coroutineContext[Job]) + StandardTestDispatcher(testScheduler))
        try {
            db.redoData().snapshotMeta() // 先等待 Room 完成初始化，再推进虚拟时钟。
            val calendar = async { calendarChanges(context).first() }
            runCurrent(); assertTrue("calendar must emit at subscription", calendar.isCompleted)
            calendar.await()
            context.getSharedPreferences("redo-jobs", 0).edit().putString("jobs",
                """[{"id":"one","title":"test","at":0,"events":[],"segments":[],"scope":"DigestOnly","state":"done","message":"ok","undo":false}]""").commit()
            val chooser = AndroidAsrChooser(context, AndroidTranscribeSettings(context), owner) { null }
            val engine = RedoEngine(db, chooser, recognize = { _, _ -> error("unused") }, digest = { _, _ -> error("unused") })
            var options = 0
            val reads = mutableListOf<Long>()
            val redo = AndroidRedoService(context, db, chooser, engine, owner,
                options = { options++; emptyList() }, currentLlm = { "test" }, enqueue = {}, now = { testScheduler.currentTime.also { reads.add(it) } })
            runCurrent(); assertEquals(1, redo.jobs.value.size)
            val initial = options
            advanceTimeBy(3_600_000); runCurrent(); assertEquals(initial, options)
            val shown = owner.launch { redo.llmOptions.collect() }; runCurrent(); assertEquals(initial + 1, options)
            advanceTimeBy(1000); runCurrent(); assertEquals(initial + 2, options)
            shown.cancel(); runCurrent()
            advanceTimeBy(86_400_001 - testScheduler.currentTime); runCurrent()
            assertTrue("clock=${testScheduler.currentTime} reads=$reads jobs=${redo.jobs.value}", redo.jobs.value.isEmpty()); assertEquals(initial + 2, options)
        } finally { owner.cancel(); runCurrent(); db.close() }
    }

    @Test fun voiceCountClockIsInactiveUntilObservedAndStopsOnExit() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java)
            .allowMainThreadQueries().setQueryCoroutineContext(StandardTestDispatcher(testScheduler)).build()
        val owner = CoroutineScope(SupervisorJob(coroutineContext[Job]) + StandardTestDispatcher(testScheduler))
        try {
            var ticks = 0
            val input = app.resound.capture.AndroidVoiceInputCapture(context, db, owner) {
                ticks++; java.time.LocalDate.of(2026, 11, 12)
            }
            runCurrent(); advanceTimeBy(3_600_000); assertEquals(0, ticks)
            val shown = owner.launch { input.todayCount.collect() }; runCurrent(); assertEquals(1, ticks)
            advanceTimeBy(60_000); runCurrent(); assertEquals(2, ticks)
            shown.cancel(); runCurrent(); advanceTimeBy(3_600_000); assertEquals(2, ticks)
        } finally { owner.cancel(); runCurrent(); db.close() }
    }

}
