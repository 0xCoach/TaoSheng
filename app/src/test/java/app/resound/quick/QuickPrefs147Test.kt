package app.resound.quick

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.resound.topic.TopicKind
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class QuickPrefs147Test {
    @Test fun defaultsValidationAndSavedSelectionsSurviveRecreation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val stored = context.getSharedPreferences("quick_prefs", Context.MODE_PRIVATE)
        stored.edit().clear().commit()
        val prefs = AndroidQuickPrefs(context)
        assertEquals(30, prefs.lastPauseMinutes.value)
        assertEquals(TopicKind.Meeting, prefs.lastTopicKind.value)
        prefs.setLastPauseMinutes(45) // 同距选短档
        prefs.setLastTopicKind(TopicKind.Training)
        assertEquals(30, prefs.lastPauseMinutes.value)
        prefs.setLastPauseMinutes(119)
        val reopened = AndroidQuickPrefs(context)
        assertEquals(120, reopened.lastPauseMinutes.value)
        assertEquals(TopicKind.Training, reopened.lastTopicKind.value)
        stored.edit().putInt("pauseMinutes", Int.MIN_VALUE).putString("topicKind", "unknown").commit()
        val repaired = AndroidQuickPrefs(context)
        assertEquals(15, repaired.lastPauseMinutes.value)
        assertEquals(TopicKind.Meeting, repaired.lastTopicKind.value)
    }
}
