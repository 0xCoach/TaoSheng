package app.resound.transcribe

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DoubaoFallbackPolicyTest {
    @Test fun onlyAutomaticDoubaoWithZeroSentencesAndOptInMayUseCustom() {
        val empty = AsrResult(emptyList(), 1000, null)
        fun eligible(source: TranscribeSource = TranscribeSource.Doubao, manual: Boolean = false,
                     result: AsrResult = empty, enabled: Boolean = true) =
            TranscribePolicy.shouldTryCustomFallback(source, manual, result, enabled)

        assertTrue(eligible())
        assertFalse(eligible(enabled = false))
        assertFalse(eligible(manual = true))
        assertFalse(eligible(source = TranscribeSource.Local))
        assertFalse(eligible(source = TranscribeSource.Custom))
        assertFalse(eligible(result = empty.copy(sentences = listOf(AsrSentence(0, 1000, "有字", null)))))
    }
}
