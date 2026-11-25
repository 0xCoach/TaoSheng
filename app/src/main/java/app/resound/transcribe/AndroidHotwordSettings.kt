package app.resound.transcribe

import android.content.Context
import app.resound.data.VoiceprintDao
import app.resound.diagnostics.FailureNotes
import app.resound.platform.AndroidKeyValueStores
import app.resound.platform.KeyValueStores
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class AndroidHotwordSettings(stores: KeyValueStores, dao: VoiceprintDao, scope: CoroutineScope) : HotwordSettings {
    constructor(context: Context, dao: VoiceprintDao, scope: CoroutineScope) :
        this(AndroidKeyValueStores(context), dao, scope)

    private val preferences = HotwordPreferenceStore(stores) {
        FailureNotes.record("transcribe/AndroidHotwordSettings.kt:24", it)
    }
    override val words = preferences.words
    override val automatic = dao.observePeople().map { people ->
        Hotwords.normalize(people.map { it.name }.filterNot { it == "我" }, Int.MAX_VALUE)
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())
    override fun setWords(words: List<String>) = preferences.setWords(words)
    var topicWords: () -> List<String> = { emptyList() }
    fun merged() = Hotwords.merge(words.value, automatic.value + topicWords())
}
