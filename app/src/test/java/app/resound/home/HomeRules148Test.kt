package app.resound.home

import app.resound.topic.TopicTimeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 卡 148：首页主卡与专题时间轴的纯规则 */
class HomeRules148Test {
    @Test fun speechSplitsNumberAndUnit() {
        val p = HomeRules.speechParts((2 * 60 + 13) * 60_000L)
        assertEquals(listOf("2", "小时", "13", "分"), p.map { it.text })
        assertEquals(listOf(false, true, false, true), p.map { it.isUnit })
        assertEquals(listOf("45", "秒"), HomeRules.speechParts(45_000).map { it.text })
    }

    @Test fun controlsFollowState() {
        assertEquals(HomeControls.TopicAndPause, HomeRules.controls(HomeCapture.Active, topicRunning = false))
        assertEquals(HomeControls.TopicAndResume, HomeRules.controls(HomeCapture.Paused, topicRunning = false))
        assertEquals(HomeControls.None, HomeRules.controls(HomeCapture.InCall, topicRunning = false))
        assertEquals(HomeControls.TopicRunning, HomeRules.controls(HomeCapture.Paused, topicRunning = true))
    }

    @Test fun modelButtonNameTruncateAndEmphasize() {
        assertEquals(ModelButton("设置模型", emphasize = true, alertDot = false), HomeRules.modelButton(null, hasKey = false, lastRunFailed = true))
        val long = HomeRules.modelButton("我的私有部署服务商名", hasKey = true, lastRunFailed = true)
        assertEquals(8, long.label.length)
        assertTrue(long.alertDot)
    }

    @Test fun timeAccentOnlyStarredOrRecent() {
        val now = 10_000_000L
        assertTrue(HomeRules.timeAccent(starred = false, endAt = now - 10 * 60_000, now = now))
        assertFalse(HomeRules.timeAccent(starred = false, endAt = now - 45 * 60_000, now = now))
        assertTrue(HomeRules.timeAccent(starred = true, endAt = 0, now = now))
    }

    @Test fun timelineMarksPauseGapAndNow() {
        val m = 60_000L
        val tl = TopicTimeline.layout(listOf(0L to 40 * m, 50 * m to null), listOf(10 * m), now = 60 * m, plannedEndAt = 100 * m)!!
        assertEquals(listOf(true, false, true), tl.bars.map { it.included })
        assertEquals(0.6f, tl.now!!, 0.001f)
        assertEquals(50 * m, tl.includedMs)
        assertEquals(0.1f, tl.marks.single().first, 0.001f)
        val done = TopicTimeline.layout(listOf(0L to 30 * m), emptyList(), now = 90 * m)!!
        assertNull(done.now)
        assertEquals("38:12", TopicTimeline.clock((38 * 60 + 12) * 1000L))
    }
}
