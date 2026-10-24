package app.resound.capture

import org.junit.Assert.*
import org.junit.Test

class VoiceInputTerminalTest {
    private val layout = OcrLayout(1000, 2000, 70, 1950, 1200)
    private fun line(text: String) = OcrLine(text, 100, 1000, 800, 1040, 1)

    @Test fun codexPlaceholderIsNeverAnUtteranceEvenWithFooterInSameBlock() {
        for (text in listOf("❯ Ask Codex", "> Ask Codex…", "› Ask Codex...", "❯ Ask Codex |")) {
            assertNull(VoiceInputOcr.extract(listOf(line(text),
                OcrLine("模型状态说明", 100, 1060, 800, 1100, 1)), layout, "com.netease.uuremote"))
        }
        assertEquals("请解释 Ask Codex 是什么意思",
            VoiceInputOcr.extract(listOf(line("❯ 请解释 Ask Codex 是什么意思")), layout, "com.netease.uuremote")!!.text)
    }

    private fun capture(results: List<OcrInput?>): List<InputRecord> {
        val requests = mutableListOf<OcrRequest>()
        val changes = mutableListOf<InputChange>()
        val engine = VoiceInputEngine(requests::add, changes::add)
        val screen = InputScreen("com.netease.uuremote", "UU远程", "page", null, null, null)
        val settings = VoiceInputSettings(enabled = true)
        engine.recording(true, 0, screen, settings)
        engine.recording(false, 100, screen, settings)
        for (at in listOf(600L, 1300L, 3100L, 6100L)) {
            engine.tick(at, screen, settings)
            requests.filter(engine::accepts).forEach { engine.recognized(it, results.getOrNull(it.shot)) }
        }
        return changes.filterIsInstance<InputChange.Save>().map { it.record }
    }

    @Test fun placeholderAfterSendDoesNotReplaceEarlierRealInputOrUseRightAlignedOutput() {
        val input = OcrInput("帮我检查这段代码", listOf("终端历史"), null, prompt = true)
        val placeholder = OcrInput("Ask Codex", emptyList(), null, "无关的右侧终端输出", prompt = true)
        val saved = capture(listOf(input, input, placeholder, placeholder)).single()
        assertEquals("帮我检查这段代码", saved.text)
        assertEquals("终端历史", saved.meta.before)
    }

    @Test fun placeholderOnlyAndChatBubbleFallbackNeverProduceTerminalRecord() {
        assertTrue(capture(List(4) { OcrInput("Ask Codex", emptyList(), null, prompt = true) }).isEmpty())
        assertTrue(capture(List(4) { OcrInput("", emptyList(), null, "看似气泡的终端输出") }).isEmpty())
    }
}
