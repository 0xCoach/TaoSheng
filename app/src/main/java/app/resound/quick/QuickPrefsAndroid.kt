package app.resound.quick

import android.content.Context
import app.resound.topic.TopicKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class AndroidQuickPrefs(context: Context) : QuickPrefs {
    private val prefs = context.getSharedPreferences("quick_prefs", Context.MODE_PRIVATE)
    private val pause = MutableStateFlow(QuickPrefsRules.pauseMinutes((prefs.all["pauseMinutes"] as? Int) ?: QuickPrefsRules.DEFAULT_PAUSE))
    private val topic = MutableStateFlow(QuickPrefsRules.topicKind(prefs.all["topicKind"] as? String))
    override val lastPauseMinutes = pause.asStateFlow()
    override val lastTopicKind = topic.asStateFlow()
    override fun setLastPauseMinutes(minutes: Int) {
        val value = QuickPrefsRules.pauseMinutes(minutes)
        prefs.edit().putInt("pauseMinutes", value).apply()
        pause.value = value
    }
    override fun setLastTopicKind(kind: TopicKind) {
        prefs.edit().putString("topicKind", kind.name).apply()
        topic.value = kind
    }
}
