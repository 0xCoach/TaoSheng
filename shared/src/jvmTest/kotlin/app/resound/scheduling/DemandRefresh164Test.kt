package app.resound.scheduling

import app.resound.topic.TopicStatus
import app.resound.push.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DemandRefresh164Test {
    @Test fun healthSubscribersStartImmediatelyAndLastSubscriberStopsMinuteTicks() = runTest {
        val report = MutableStateFlow(0)
        var calls = 0
        backgroundScope.launch { report.subscriptionCount.map { it > 0 }.refreshWhileNeeded(60_000) { calls++ } }
        runCurrent(); advanceTimeBy(3_600_000); assertEquals(0, calls)
        val a = backgroundScope.launch { report.collect() }; runCurrent(); assertEquals(1, calls)
        val b = backgroundScope.launch { report.collect() }; runCurrent()
        advanceTimeBy(60_000); runCurrent(); assertEquals(2, calls)
        a.cancel(); runCurrent(); advanceTimeBy(60_000); runCurrent(); assertEquals(3, calls)
        b.cancel(); runCurrent(); advanceTimeBy(3_600_000); assertEquals(3, calls)
    }
    @Test fun topicDemandStopsWithoutActiveTopicAndResumesForRunningOrPaused() = runTest {
        val status = MutableStateFlow<TopicStatus?>(null)
        val demand = status.map(BackgroundRefreshPolicy::topicNeedsTick)
        var calls = 0
        backgroundScope.launch { demand.refreshWhileNeeded(60_000) { calls++ } }
        runCurrent(); advanceTimeBy(3_600_000); assertEquals(0, calls)
        status.value = TopicStatus.Running; runCurrent(); assertEquals(1, calls)
        advanceTimeBy(60_000); runCurrent(); assertEquals(2, calls)
        status.value = TopicStatus.Done; runCurrent(); advanceTimeBy(3_600_000); assertEquals(2, calls)
        status.value = TopicStatus.Paused; runCurrent(); assertEquals(3, calls)
    }
    @Test fun demandDisappearanceCancelsInFlightRefresh() = runTest {
        val demand = MutableStateFlow(true)
        var finished = 0; var released = 0
        backgroundScope.launch { demand.refreshWhileNeeded(1000) {
            try { delay(10_000); finished++ } finally { released++ }
        } }
        runCurrent(); demand.value = false; runCurrent(); advanceTimeBy(20_000)
        assertEquals(0, finished); assertEquals(1, released)
    }
    @Test fun failureExpiryWaitsForDeadlineAndDataChangeCancelsOldDeadline() = runTest {
        val deadline = MutableStateFlow<Long?>(null)
        var calls = 0
        backgroundScope.launch { deadline.refreshAtDeadline({ testScheduler.currentTime }) { calls++ } }
        runCurrent(); advanceTimeBy(3_600_000); assertEquals(1, calls)
        deadline.value = testScheduler.currentTime + 120_000; runCurrent(); assertEquals(2, calls)
        advanceTimeBy(60_000); deadline.value = null; runCurrent(); assertEquals(3, calls)
        advanceTimeBy(120_000); assertEquals(3, calls)
        deadline.value = testScheduler.currentTime + 60_000; runCurrent()
        advanceTimeBy(60_000); runCurrent(); assertEquals(5, calls)
        advanceTimeBy(3_600_000); assertEquals(5, calls)
    }
    @Test fun audioMinuteSweepOnlyRunsWithEnabledAudioChannel() = runTest {
        val channel = PushChannel(1, PushKind.MarkdownFolder, "test", true, emptySet(), PushFormat.Markdown, "",
            subscription = PushSubscription(audio = AudioPush.All))
        val channels = MutableStateFlow(emptyList<PushChannel>())
        var calls = 0
        backgroundScope.launch { channels.map(BackgroundRefreshPolicy::audioNeedsTick).refreshWhileNeeded(60_000) { calls++ } }
        runCurrent(); advanceTimeBy(3_600_000); assertEquals(0, calls)
        channels.value = listOf(channel); runCurrent(); assertEquals(1, calls)
        advanceTimeBy(60_000); runCurrent(); assertEquals(2, calls)
        channels.value = listOf(channel.copy(enabled = false)); runCurrent()
        advanceTimeBy(3_600_000); assertEquals(2, calls)
        channels.value = listOf(channel.copy(subscription = PushSubscription(audio = AudioPush.Off))); runCurrent()
        advanceTimeBy(3_600_000); assertEquals(2, calls)
    }

}
