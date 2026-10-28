package app.resound.diagnostics

import android.app.Application
import android.content.Context
import androidx.room.Room
import app.resound.capture.RoomCaptureStatsRepository
import app.resound.data.CaptureDatabase
import app.resound.update.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class ProblemReportsTest {
    private val context get() = RuntimeEnvironment.getApplication() as Context
    private val updater = object : AppUpdater {
        override val currentVersion = "0.2.12-preview.1"
        override val state = MutableStateFlow<UpdateState>(UpdateState.Idle)
        override val autoCheck = MutableStateFlow(true)
        override val previewChannel = MutableStateFlow(true)
        override val isPreviewBuild = true
        override fun setAutoCheck(enabled: Boolean) {}
        override fun check(manual: Boolean) {}
        override fun download() {}
        override fun install() {}
        override fun later() {}
        override fun skip() {}
        override fun canInstall() = true
    }

    @Test fun crashesOmitMessagesRetainTenAndDelegateToOriginalHandler() {
        File(context.filesDir, "crash").deleteRecursively()
        val crashes = CrashRecords(context)
        val error = IllegalStateException("私人原话 sk-secret", IllegalArgumentException("私人地点"))
        repeat(12) { crashes.record(Thread.currentThread(), error, 1000L + it) }
        assertEquals(10, crashes.files().size)
        assertEquals(1011L, crashes.latestAt())
        assertTrue(crashes.files().first().name.startsWith("0000000000000001002"))
        var delegated = false
        crashes.handler(Thread.UncaughtExceptionHandler { _, caught ->
            assertSame(error, caught); delegated = true
        }).uncaughtException(Thread.currentThread(), error)
        assertTrue(delegated)
        assertEquals(10, crashes.files().size)
        val text = crashes.dump()
        assertTrue(text.contains("java.lang.IllegalStateException"))
        assertFalse(text.contains("私人")); assertFalse(text.contains("sk-secret"))
    }

    @Test fun exportIncludesDiagnosticsScrubsKeysAndCrashNoticeStaysDismissedAfterRestart() = runBlocking {
        File(context.filesDir, "crash").deleteRecursively()
        context.getSharedPreferences("problem-reports", 0).edit().clear().commit()
        val crashes = CrashRecords(context)
        crashes.record(Thread.currentThread(), RuntimeException("不要导出"), 2000)
        val database = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java).allowMainThreadQueries().build()
        try {
            val key = "sk-" + "secret".repeat(10)
            val hex = "abcdef12".repeat(4)
            val stats = RoomCaptureStatsRepository(context, database.capture()) { "步骤=12 $key $hex" }
            val reports = LocalProblemReports(context, crashes, stats, updater)
            assertEquals(2000L, reports.lastCrashAt.value)
            repeat(55) { UpdateDiagnostics.record(context, "step=$it") }
            val file = reports.export()
            assertTrue(file.canonicalPath.startsWith(context.cacheDir.canonicalPath + "/"))
            val text = file.readText()
            assertTrue(text.contains("预览通道=true")); assertTrue(text.contains("步骤=12"))
            assertTrue(text.contains("step=54")); assertFalse(text.contains("step=0\n"))
            assertFalse(text.contains(key)); assertFalse(text.contains(hex)); assertFalse(text.contains("不要导出"))
            reports.dismissCrashNotice()
            assertNull(reports.lastCrashAt.value)
            assertNull(LocalProblemReports(context, crashes, stats, updater).lastCrashAt.value)
        } finally { database.close() }
    }
}
