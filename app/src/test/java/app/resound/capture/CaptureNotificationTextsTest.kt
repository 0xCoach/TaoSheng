package app.resound.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureNotificationTextsTest {
    @Test fun android9ShowsYieldAndContinue() {
        assertEquals("让出麦克风", CaptureNotifications.texts("涛声", paused = false, legacyMic = true).button)
        val paused = CaptureNotifications.texts("涛声", paused = true, legacyMic = true)
        assertEquals("继续", paused.button)
        assertEquals("涛声 · 已让出麦克风", paused.title)
    }

    @Test fun android10AndUpKeepsPauseWording() {
        assertEquals("暂停 30 分钟", CaptureNotifications.texts("涛声", paused = false, legacyMic = false).button)
        assertEquals("恢复", CaptureNotifications.texts("涛声", paused = true, legacyMic = false).button)
    }
}
