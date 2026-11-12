package app.resound.capture

import android.app.Application
import androidx.room.Room
import app.resound.data.CaptureDatabase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 卡 172：音量不再经过状态流，只关心状态的后台订阅者不会被音量叫醒。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class LevelFlow172Test {
    private fun controller(): AndroidCaptureController {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java).build()
        return AndroidCaptureController(context, CaptureStore(context, db.capture()))
    }

    @Test fun levelChangesDoNotWakeStatusSubscribers() = runTest(UnconfinedTestDispatcher()) {
        val c = controller()
        c.update(CaptureState.Listening)
        var statusEmissions = 0
        val levels = mutableListOf<Float>()
        val a = backgroundScope.launch { c.status.collect { statusEmissions++ } }
        val b = backgroundScope.launch { c.level.collect { levels += it } }
        repeat(20) { c.level(0.1f + it * 0.01f) }
        assertEquals(1, statusEmissions)
        assertEquals(21, levels.size)
        a.cancel(); b.cancel()
    }

    @Test fun levelResetsWhenNotListening() = runTest(UnconfinedTestDispatcher()) {
        val c = controller()
        c.update(CaptureState.Recording); c.level(0.6f)
        c.update(CaptureState.Listening)
        assertEquals(0.6f, c.level.first())
        c.update(CaptureState.Paused, pausedUntil = 1L)
        assertEquals(0f, c.level.first())
    }
}
