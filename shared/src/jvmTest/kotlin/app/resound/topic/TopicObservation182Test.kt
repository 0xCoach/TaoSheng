package app.resound.topic

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Test
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class TopicObservation182Test {
    @Test fun emptyStopsSubscriptionAndNewTaskStartsWithoutDelay() = runTest {
        val ids=MutableStateFlow(emptyList<Long>())
        var starts=0;var stops=0;val output=mutableListOf<List<Long>>()
        val job=backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            ids.whileTopicsPending { tasks -> flow {
                starts++
                try { emit(tasks);awaitCancellation() } finally { stops++ }
            } }.collect { output += it }
        }
        runCurrent();assertEquals(0,starts)
        ids.value=listOf(1);runCurrent();assertEquals(listOf(listOf(1L)),output)
        ids.value=emptyList();runCurrent();assertEquals(1,stops)
        ids.value=listOf(2);runCurrent();assertEquals(2,starts)
        assertEquals(listOf(listOf(1L),listOf(2L)),output)
        job.cancel();runCurrent();assertEquals(2,stops)
    }
}
