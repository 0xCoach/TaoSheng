package app.resound.transcribe

import android.app.Application
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class Training176Test {
    private suspend fun settings(block: suspend (AndroidAsrSpeedSettings) -> Unit) {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("training176", 0)
        prefs.edit().clear().commit()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try { block(AndroidAsrSpeedSettings(prefs, scope) { 100L }) }
        finally { scope.cancel(); prefs.edit().clear().commit() }
    }

    @Test fun waitingForTrainingDoesNotLockSettingsAndKeepsTheEarlierSnapshot() = runBlocking {
        settings { speed ->
            val entered = CompletableDeferred<Long>()
            val release = CompletableDeferred<Unit>()
            speed.topicTraining = { at -> entered.complete(at); release.await(); false }
            val decision = async(Dispatchers.Default) { speed.effectiveSeparateSpeakers(101) }
            assertEquals(101L, withTimeout(3000) { entered.await() })
            withTimeout(3000) { withContext(Dispatchers.Default) { speed.setSeparateSpeakers(false) } }
            release.complete(Unit)
            assertTrue(withTimeout(3000) { decision.await() })
            assertFalse(speed.effectiveSeparateSpeakers(101))
        }
    }

    @Test fun trainingIsQueriedEvenWhenSeparationIsOffAndErrorsAreNotHidden() = runBlocking {
        settings { speed ->
            speed.setSeparateSpeakers(false)
            var calls = 0
            val failure = IllegalStateException("synthetic query failure")
            speed.topicTraining = { calls++; throw failure }
            assertSame(failure, runCatching { speed.effectiveSeparateSpeakers(101) }.exceptionOrNull())
            assertEquals(1, calls)
            speed.topicTraining = { calls++; true }
            assertFalse(speed.effectiveSeparateSpeakers(101))
            assertEquals(2, calls)
        }
    }

    @Test fun cancellingTheCallerCancelsTheTrainingQuery() = runBlocking {
        settings { speed ->
            val entered = CompletableDeferred<Unit>()
            val released = CompletableDeferred<Unit>()
            speed.topicTraining = {
                entered.complete(Unit)
                try { awaitCancellation() } finally { released.complete(Unit) }
            }
            val call = launch { speed.effectiveSeparateSpeakers(101) }
            withTimeout(3000) { entered.await() }
            call.cancelAndJoin()
            assertTrue(released.isCompleted)
            assertTrue(call.isCancelled)
        }
    }
}
