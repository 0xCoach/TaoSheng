package app.resound.transcribe

import org.junit.Assert.assertEquals
import org.junit.Test

/** 卡 223：没存过转写方式的默认本机识别；填过豆包密钥的老用户不变；存过的照旧 */
class DefaultSource223Test {
    private fun store(saved: MutableMap<String, String>, cloudKey: Boolean) = TranscribePreferenceStore(
        { k, d -> saved[k] ?: d }, { _, d -> d }, { _, d -> d }, { saved.containsKey(it) },
        { k, v -> saved[k] = v; true }, { _, _ -> true }, { _, _ -> true },
        { _, d -> d }, { _, _ -> }, {}, {}, {}, { cloudKey })

    @Test fun freshInstallDefaultsToLocalAndRemembers() {
        val saved = mutableMapOf<String, String>()
        assertEquals(TranscribeSource.Local, store(saved, cloudKey = false).source.value)
        assertEquals("Local", saved["source"])
    }

    @Test fun oldUserWithDoubaoKeyStaysOnDoubao() {
        val saved = mutableMapOf<String, String>()
        assertEquals(TranscribeSource.Doubao, store(saved, cloudKey = true).source.value)
        // 存下来了：之后删了密钥也不会悄悄换
        assertEquals(TranscribeSource.Doubao, store(saved, cloudKey = false).source.value)
    }

    @Test fun savedChoiceWins() {
        assertEquals(TranscribeSource.Custom, store(mutableMapOf("source" to "Custom"), cloudKey = true).source.value)
        assertEquals(TranscribeSource.Doubao, store(mutableMapOf("source" to "Doubao"), cloudKey = false).source.value)
    }
}
