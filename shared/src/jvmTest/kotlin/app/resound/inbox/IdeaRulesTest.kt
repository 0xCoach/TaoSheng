package app.resound.inbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdeaRulesTest {
    @Test fun fingerprintIgnoresPunctuationAndWhitespace() {
        val a = IdeaRules.fingerprint("做一个 语音 灵感收件箱！")
        val b = IdeaRules.fingerprint("做一个语音灵感收件箱")
        assertEquals(a, b)
        assertTrue(a.isNotBlank())
    }
    @Test fun duplicateDetectionMatchesSameFingerprintOnly() {
        val a = IdeaRules.fingerprint("把录音变成待办")
        val b = IdeaRules.fingerprint("把录音变成待办。")
        val c = IdeaRules.fingerprint("完全不同的点子")
        assertTrue(IdeaRules.isDuplicate(a, b))
        assertFalse(IdeaRules.isDuplicate(a, c))
    }
    @Test fun statusMovesForwardAndCanAbort() {
        assertTrue(IdeaStatus.canMove(IdeaStatus.Research, IdeaStatus.Studying))
        assertTrue(IdeaStatus.canMove(IdeaStatus.Studying, IdeaStatus.Verified))
        assertTrue(IdeaStatus.canMove(IdeaStatus.Research, IdeaStatus.Abandoned))
        assertFalse(IdeaStatus.canMove(IdeaStatus.Verified, IdeaStatus.Research))
        assertTrue(IdeaStatus.canMove(IdeaStatus.Done, IdeaStatus.Studying))
        assertTrue(IdeaStatus.canMove(IdeaStatus.Abandoned, IdeaStatus.Research))
        assertFalse(IdeaStatus.canMove(IdeaStatus.Abandoned, IdeaStatus.Verified))
    }
    @Test fun likePatternEscapesWildcards() {
        val p = IdeaRules.likePattern("100%")
        assertTrue(p.contains("\\%"))
        assertTrue(p.startsWith("%") && p.endsWith("%"))
    }
    @Test fun suggestCategoryPicksKnownWords() {
        assertEquals("产品", IdeaRules.suggestCategory("这个 App 功能可以这样做"))
        assertEquals("其他", IdeaRules.suggestCategory("随便聊了两句"))
    }
}
