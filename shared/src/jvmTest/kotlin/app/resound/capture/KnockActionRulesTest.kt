package app.resound.capture

import app.resound.capture.KnockActionRules.Action
import org.junit.Assert.assertEquals
import org.junit.Test

/** 敲敲动作判定：隐私暂停是硬锁；状态→命令映射不得越权。 */
class KnockActionRulesTest {

    @Test fun `隐私暂停时敲敲一律无效`() {
        assertEquals(Action.Blocked, KnockActionRules.decide(serviceAlive = true, enabled = true, manualPaused = true, privacyPaused = true))
        assertEquals(Action.Blocked, KnockActionRules.decide(serviceAlive = true, enabled = true, manualPaused = false, privacyPaused = true))
        assertEquals(Action.Blocked, KnockActionRules.decide(serviceAlive = false, enabled = false, manualPaused = false, privacyPaused = true))
    }

    @Test fun `未录启动_暂停恢复_录音中暂停`() {
        assertEquals(Action.Start, KnockActionRules.decide(serviceAlive = false, enabled = false, manualPaused = false, privacyPaused = false))
        assertEquals(Action.Start, KnockActionRules.decide(serviceAlive = true, enabled = false, manualPaused = false, privacyPaused = false))
        assertEquals(Action.Resume, KnockActionRules.decide(serviceAlive = true, enabled = true, manualPaused = true, privacyPaused = false))
        assertEquals(Action.Pause, KnockActionRules.decide(serviceAlive = true, enabled = true, manualPaused = false, privacyPaused = false))
    }
}
