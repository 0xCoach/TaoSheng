package app.resound.capture

import org.junit.Assert.*
import org.junit.Test

class VoiceInputEmptyBoxContextTest {
    private val layout = OcrLayout(1000, 2000, 70, 1950, 1200)
    private val history = listOf(
        OcrLine("老王", 400, 100, 600, 140, 0),
        OcrLine("明天来吗", 50, 400, 450, 440, 1),
        OcrLine("我会过来", 650, 550, 950, 590, 2),
        OcrLine("几点到", 50, 700, 450, 740, 3))

    @Test fun sentBubbleRetainsTitleAndBothSidesBeforeIt() {
        val result = VoiceInputOcr.screenshot(history +
            OcrLine("明天上午十点", 650, 900, 950, 940, 4), layout, "com.tencent.mm")!!
        assertEquals("", result.text)
        assertEquals("明天上午十点", result.bubble)
        assertEquals("老王", result.title)
        assertEquals(listOf("老王：明天来吗", "我：我会过来", "老王：几点到"), result.context)
    }

    @Test fun emptyBoxWithoutBubbleStillProvidesBaselineContext() {
        val result = VoiceInputOcr.screenshot(history.filter { it.left < 600 }, layout, "com.tencent.mm")!!
        assertEquals("", result.text)
        assertNull(result.bubble)
        assertEquals(listOf("老王：明天来吗", "老王：几点到"), result.context)
    }

    @Test fun engineUsesContextFromNewSentBubbleRatherThanEmptyBaseline() {
        val requests = mutableListOf<OcrRequest>()
        val changes = mutableListOf<InputChange>()
        val engine = VoiceInputEngine(requests::add, changes::add)
        val screen = InputScreen("com.tencent.mm", "微信", "page", null, null, null)
        val settings = VoiceInputSettings(enabled = true)
        engine.recording(true, 0, screen, settings)
        engine.recording(false, 100, screen, settings)
        val baseline = VoiceInputOcr.screenshot(history, layout, screen.packageName)
        val sent = VoiceInputOcr.screenshot(history +
            OcrLine("明天上午十点", 650, 900, 950, 940, 4), layout, screen.packageName)
        for (at in listOf(600L, 3100L, 10100L)) {
            engine.tick(at, screen, settings)
            requests.filter(engine::accepts).forEach {
                engine.recognized(it, if (it.shot < 1) baseline else sent)
            }
        }
        val saved = changes.filterIsInstance<InputChange.Save>().single().record
        assertEquals("明天上午十点", saved.text)
        assertEquals("老王", saved.meta.title)
        assertEquals("老王：明天来吗\n我：我会过来\n老王：几点到", saved.meta.before)
    }
}
