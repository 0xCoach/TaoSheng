package app.resound.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 卡 167：桌面小组件的显示与按钮 */
class WidgetRules167Test {
    @Test fun listeningShowsPauseAndStartTopic() {
        val v = WidgetRules.view(HomeCapture.Active, null, false, null, null)
        assertEquals("正在聆听", v.status); assertTrue(v.active)
        assertEquals(WidgetTopicButton.StartTopic, v.topicButton); assertEquals(WidgetToggle.PauseListening, v.toggle)
    }

    @Test fun pausedShowsResumeTimeAndQuietName() {
        val v = WidgetRules.view(HomeCapture.Paused, null, false, "15:30", "午休")
        assertEquals("午休中", v.status); assertEquals("15:30 自动恢复", v.sub); assertFalse(v.active)
        assertEquals(WidgetToggle.ResumeListening, v.toggle)
    }

    @Test fun topicTurnsMiddleButtonIntoMarkAndToggleControlsTopic() {
        val running = WidgetRules.view(HomeCapture.Active, "会议专题 · 23 分", false, null, null)
        assertEquals(WidgetTopicButton.MarkTopic, running.topicButton); assertEquals(WidgetToggle.PauseTopic, running.toggle)
        val paused = WidgetRules.view(HomeCapture.Active, "会议专题 · 已暂停 · 23 分", true, null, null)
        assertEquals(WidgetToggle.ResumeTopic, paused.toggle); assertFalse(paused.active)
    }

    @Test fun noPermissionOpensApp() {
        assertEquals(WidgetToggle.OpenApp, WidgetRules.view(HomeCapture.NoPermission, null, false, null, null).toggle)
    }
}
