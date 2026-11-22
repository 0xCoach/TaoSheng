package app.resound.digest

import org.junit.Test
import org.junit.Assert.assertEquals

class DeepThinkingDefault222Test {
    private fun store(saved: MutableMap<String, Boolean>, newInstall: Boolean) = DigestPreferenceStore(
        { k, d -> saved[k] ?: d }, { _, d -> d }, { 30 }, { k, v -> saved[k] = v; true }, { _, _ -> true }, { newInstall })

    @Test fun newInstallDefaultsOnAndStaysOnAfterUpgrade() {
        val saved = mutableMapOf<String, Boolean>()
        assertEquals(true, store(saved, newInstall = true).deepThinking.value)
        // 存下来了：之后升级（不再是全新安装）也不变
        assertEquals(true, store(saved, newInstall = false).deepThinking.value)
    }

    @Test fun upgradingUserKeepsOffAndExplicitChoiceWins() {
        assertEquals(false, store(mutableMapOf(), newInstall = false).deepThinking.value)
        assertEquals(false, store(mutableMapOf("deepThinking" to false), newInstall = true).deepThinking.value)
    }
}
