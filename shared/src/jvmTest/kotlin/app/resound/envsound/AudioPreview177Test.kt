package app.resound.envsound

import app.resound.platform.FileRef
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AudioPreview177Test {
    private class Players {
        val active = mutableSetOf<Long>()
        val started = mutableListOf<Long>()
        val released = mutableListOf<Long>()
        val completed = mutableMapOf<Long, () -> Unit>()
        fun requests(scope: CoroutineScope, load: suspend (Long) -> FileRef?) = AudioPreviewRequests(
            scope, load,
            onReady = { id, _, done ->
                assertTrue("不能同时保留两个播放器", active.isEmpty())
                active += id; started += id; completed[id] = done
            },
            onStop = { released += active; active.clear() },
        )
    }

    @Test fun switchingWhileLoadingDiscardsTheOldResultEvenIfItIgnoresCancellation() = runTest {
        val a = CompletableDeferred<FileRef>()
        val b = CompletableDeferred<FileRef>()
        val players = Players()
        val requests = players.requests(this) { id ->
            withContext(NonCancellable) { if (id == 1L) a.await() else b.await() }
        }
        requests.play(1); runCurrent()
        requests.play(2); runCurrent()
        b.complete(FileRef("second")); runCurrent()
        a.complete(FileRef("first")); runCurrent()
        assertEquals(listOf(2L), players.started)
        assertEquals(setOf(2L), players.active)
        requests.stop()
        assertEquals(listOf(2L), players.released)
    }

    @Test fun stoppingWhileLoadingPreventsPlaybackWhenTheReadReturnsLater() = runTest {
        val file = CompletableDeferred<FileRef>()
        val players = Players()
        val requests = players.requests(this) { withContext(NonCancellable) { file.await() } }
        requests.play(1); runCurrent()
        requests.stop()
        file.complete(FileRef("late")); runCurrent()
        assertTrue(players.started.isEmpty())
        assertTrue(players.active.isEmpty())
    }

    @Test fun anOldCompletionCannotStopTheNewPlayerAndStopReleasesTheCurrentOne() = runTest {
        val players = Players()
        val requests = players.requests(this) { FileRef("audio-$it") }
        requests.play(1); runCurrent()
        val oldCompletion = players.completed.getValue(1)
        requests.play(2); runCurrent()
        assertEquals(listOf(1L), players.released)
        oldCompletion()
        assertEquals(setOf(2L), players.active)
        requests.stop()
        assertEquals(listOf(1L, 2L), players.released)
        assertTrue(players.active.isEmpty())
    }
}
