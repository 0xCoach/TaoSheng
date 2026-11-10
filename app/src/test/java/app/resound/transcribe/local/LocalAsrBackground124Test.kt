package app.resound.transcribe.local

import android.app.Application
import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import androidx.work.*
import app.resound.transcribe.BackgroundTranscribeDiagnostics
import app.resound.transcribe.PipelineTimes
import app.resound.transcribe.TranscribeScheduler
import app.resound.transcribe.TranscribeWork
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class LocalAsrBackground124Test {
    private val context get() = RuntimeEnvironment.getApplication() as Context
    private fun lock() = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "test:local-asr")

    @Test fun bindingInheritsClientImportanceWithoutSensorCapabilities() {
        assertTrue(LocalAsrConnection.BIND_FLAGS and Context.BIND_IMPORTANT != 0)
        assertTrue(LocalAsrConnection.BIND_FLAGS and Context.BIND_AUTO_CREATE != 0)
        assertEquals(0, LocalAsrConnection.BIND_FLAGS and Context.BIND_INCLUDE_CAPABILITIES)
    }

    @Test fun successfulInferenceReleasesLock() = runBlocking {
        val lock = lock()
        assertFalse(lock.isHeld)
        InferenceWakeLock(lock).run(300_000) { assertTrue(lock.isHeld) }
        assertFalse(lock.isHeld)
    }

    @Test fun failedInferenceReleasesLock() = runBlocking {
        val lock = lock()
        try {
            InferenceWakeLock(lock).run(300_000) { assertTrue(lock.isHeld); throw IllegalStateException() }
            fail("应保留原错误")
        } catch (_: IllegalStateException) { }
        assertFalse(lock.isHeld)
    }

    @Test fun timeoutAndRemoteCancellationReleaseLockWithoutReacquiring() = runBlocking {
        val lock = lock()
        val lease = InferenceWakeLock(lock)
        assertNull(withTimeoutOrNull(50) {
            lease.run(300_000) { assertTrue(lock.isHeld); awaitCancellation() }
        })
        assertFalse(lock.isHeld)
        // IPC 取消可在 JNI 未返回时释放；finally 再释放也安全，已取消请求不得重新拿锁。
        val remote = InferenceWakeLock(lock)
        remote.run(InferenceWakeLock.MAX_MILLIS) {
            assertTrue(lock.isHeld)
            remote.release()
            assertFalse(lock.isHeld)
        }
        remote.run(300_000) { fail("取消后不得再次执行") }
        assertFalse(lock.isHeld)
        assertEquals(660_000L, InferenceWakeLock.MAX_MILLIS)
    }

    @Test fun earlyWorkIsExpeditedAndFallsBackWithoutChangingSchedule() {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.NOT_REQUIRED).build()
        val request = TranscribeScheduler.earlyRequest(constraints)
        val spec = request.workSpec
        assertTrue(spec.expedited)
        assertEquals(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST, spec.outOfQuotaPolicy)
        assertEquals(TranscribeWork.MODE_REALTIME, spec.input.getString(TranscribeWork.MODE))
        assertEquals(constraints, spec.constraints)
        assertEquals(BackoffPolicy.LINEAR, spec.backoffPolicy)
        assertEquals(30_000L, spec.backoffDelayDuration)
        assertEquals(0L, spec.initialDelay)
    }

    @Test fun delayedBackgroundWorkReportsMetadataAndMarksRebuiltProcess() {
        context.getSharedPreferences("pipeline-times", 0).edit().clear().commit()
        val input = Data.Builder().putAll(BackgroundTranscribeDiagnostics.input())
            .putLong(BackgroundTranscribeDiagnostics.ENQUEUED_AT, SystemClock.elapsedRealtime() - 45_000)
            .putBoolean(BackgroundTranscribeDiagnostics.FOREGROUND, false).build()
        BackgroundTranscribeDiagnostics.delayed(context, input)
        val rebuilt = Data.Builder().putAll(input).putString(BackgroundTranscribeDiagnostics.PROCESS, "previous").build()
        BackgroundTranscribeDiagnostics.delayed(context, rebuilt)
        val rows = PipelineTimes.dump(context).lines().filter { it.isNotBlank() }
        assertEquals(2, rows.size)
        assertTrue(rows[0].contains("stage=realtime-delayed foreground=false processRecreated=false waitMillis=45000"))
        assertTrue(rows[1].contains("processRecreated=true"))
        assertFalse(rows[1].contains("waitMillis=")) // 不跨进程/重启比较单调时钟。
        assertFalse(rows.any { it.contains("previous") })
    }
}
