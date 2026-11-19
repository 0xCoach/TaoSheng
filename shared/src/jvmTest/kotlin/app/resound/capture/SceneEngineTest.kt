package app.resound.capture

import org.junit.Assert.*
import org.junit.Test

class SceneEngineTest {
    private val now = 1_700_000_000_000L
    private fun signals(block: SceneSignals.() -> SceneSignals) =
        SceneSignals(now = now).block()
    private fun pause(d: SceneDecision): CaptureDirective.Pause {
        assertTrue(d.directive is CaptureDirective.Pause)
        return d.directive as CaptureDirective.Pause
    }

    @Test fun normalRecords() {
        val d = SceneEngine.decide(signals { this })
        assertTrue(d.directive is CaptureDirective.Record)
        assertEquals(RecordingScene.Normal, d.scene)
        assertFalse(d.deferHeavyWork)
    }

    @Test fun privacyWinsOverEverything() {
        val d = SceneEngine.decide(signals { copy(
            privacyPaused = true, manualPausedUntil = now + 60_000,
            quietEndsAt = now + 3600_000, quietName = "睡眠",
            meetingActive = true, micBusyReason = "通话中", resourceLimited = true) })
        val p = pause(d)
        assertEquals(Long.MAX_VALUE, p.until)
        assertEquals(RecordingScene.PrivacyPaused, d.scene)
        assertEquals(SceneSource.Manual, p.source)
    }

    @Test fun manualPauseStillApplies() {
        val d = SceneEngine.decide(signals { copy(manualPausedUntil = now + 30 * 60_000) })
        val p = pause(d)
        assertEquals(now + 30 * 60_000, p.until)
        assertEquals(RecordingScene.Paused, d.scene)
        assertEquals(SceneSource.Manual, p.source)
    }

    @Test fun quietWindowPausesAsRule() {
        val d = SceneEngine.decide(signals { copy(quietEndsAt = now + 3600_000, quietName = "睡眠") })
        val p = pause(d)
        assertEquals(now + 3600_000, p.until)
        assertEquals(RecordingScene.QuietHours, d.scene)
        assertEquals(SceneSource.Rule, p.source)
        assertEquals("睡眠", p.label)
    }

    @Test fun meetingOverridesQuiet() {
        val d = SceneEngine.decide(signals { copy(quietEndsAt = now + 3600_000, quietName = "睡眠", meetingActive = true) })
        assertTrue(d.directive is CaptureDirective.Record)
        assertEquals(RecordingScene.Meeting, d.scene)
    }

    @Test fun micInterruptedDoesNotPause() {
        val d = SceneEngine.decide(signals { copy(micBusyReason = "通话中") })
        assertTrue(d.directive is CaptureDirective.Record)
        assertEquals(RecordingScene.MicInterrupted, d.scene)
    }

    @Test fun resourceLimitedDefersHeavyButKeepsRecording() {
        val d = SceneEngine.decide(signals { copy(resourceLimited = true) })
        assertTrue(d.directive is CaptureDirective.Record)
        assertEquals(RecordingScene.ResourceLimited, d.scene)
        assertTrue(d.deferHeavyWork)
    }

    @Test fun sceneAwareOffMatchesLegacyPlan() {
        // 关闭总开关：用户规则（手动/停录/会议覆盖）照旧，自动派生项关闭
        val paused = SceneEngine.decide(signals { copy(sceneAwareEnabled = false, quietEndsAt = now + 3600_000, quietName = "睡眠") })
        assertTrue(paused.directive is CaptureDirective.Pause)
        val deferred = SceneEngine.decide(signals { copy(sceneAwareEnabled = false, resourceLimited = true) })
        assertFalse(deferred.deferHeavyWork)
        assertEquals(RecordingScene.Normal, deferred.scene)
    }

    @Test fun expiredSignalsDoNotPause() {
        val d = SceneEngine.decide(signals { copy(manualPausedUntil = now - 1, quietEndsAt = now - 1) })
        assertTrue(d.directive is CaptureDirective.Record)
    }
}
