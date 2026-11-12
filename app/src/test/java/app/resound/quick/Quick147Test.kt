package app.resound.quick

import app.resound.capture.CaptureController
import app.resound.capture.CaptureState
import app.resound.capture.CaptureStatus
import app.resound.capture.CaptureTilePolicy
import app.resound.topic.TopicKind
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class Quick147Test {
    private val policy = DefaultQuickActionsPolicy()

    @Test fun listeningUsesRememberedDurationAndTopicInMenuAndTile() {
        val entries = policy.entries(ListenState.Listening, TopicState.None, 60, TopicKind.Training)
        assertEquals(listOf(QuickEntry(QuickAction.Pause, "暂停 1 小时"), QuickEntry(QuickAction.StartTopic, "开始培训专题")), entries)
        var paused = 0
        val controller = object : CaptureController {
            override val status = MutableStateFlow(CaptureStatus(CaptureState.Listening, 0))
            override fun start() = Unit
            override fun stop() = Unit
            override fun pause(minutes: Int) { paused = minutes }
            override fun resume() = Unit
        }
        CaptureTilePolicy.click(controller, 120)
        assertEquals(120, paused)
        assertTrue(policy.entries(ListenState.Stopped, TopicState.None, 30, TopicKind.Call).all { it.label.length <= 10 })
    }

    @Test fun runningTopicTakesPriorityOverListeningPause() {
        assertEquals(listOf(QuickEntry(QuickAction.MarkTopic, "记一下"), QuickEntry(QuickAction.Pause, "暂停专题"),
            QuickEntry(QuickAction.EndTopic, "结束专题")), policy.entries(ListenState.Listening, TopicState.Running, 30, TopicKind.Meeting))
        assertEquals("专题已暂停", QuickText.success(QuickAction.Pause, TopicState.Running, 60, TopicKind.Meeting))
    }

    @Test fun pausedTopicAndListeningResumeSeparatelyAndStaleTopicCannotRun() {
        assertEquals(listOf(QuickEntry(QuickAction.Resume, "继续专题"), QuickEntry(QuickAction.EndTopic, "结束专题")),
            policy.entries(ListenState.Paused, TopicState.Paused, 30, TopicKind.Meeting))
        assertEquals(QuickEntry(QuickAction.Resume, "现在恢复"), policy.entries(ListenState.Paused, TopicState.None, 30, TopicKind.Meeting).first())
        assertTrue(QuickExecutionRules.allowed(QuickAction.Resume, 7, 7, ListenState.Paused, TopicState.Paused, 30, TopicKind.Meeting))
        assertFalse(QuickExecutionRules.allowed(QuickAction.Resume, 6, 7, ListenState.Paused, TopicState.Paused, 30, TopicKind.Meeting))
    }
}
