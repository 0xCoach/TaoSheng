package app.resound.transcribe

import android.content.Context
import app.resound.data.VoiceprintDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import org.json.JSONArray

object Hotwords {
    fun normalize(words: List<String>, limit: Int = 200): List<String> = words.asSequence()
        .map(String::trim).filter { it.codePointCount(0, it.length) in 1..20 && it.none(Char::isISOControl) }
        .distinct().take(limit).toList()
    fun merge(manual: List<String>, automatic: List<String>) = normalize(manual + automatic, Int.MAX_VALUE)
    // 当前使用 bigmodel_nostream，热词直传上限 5000 词。
    fun doubao(words: List<String>) = words.take(5000)
    fun customPrompt(words: List<String>): String = if (words.isEmpty()) "" else
        ("可能出现的词：" + words.joinToString("、")).take(200).let {
            if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it
        }
}

class AndroidHotwordSettings(context: Context, dao: VoiceprintDao, scope: CoroutineScope) : HotwordSettings {
    private val prefs = context.getSharedPreferences("transcribe", Context.MODE_PRIVATE)
    private val current = MutableStateFlow(runCatching {
        val array = JSONArray(prefs.getString("hotwords", "[]"))
        Hotwords.normalize((0 until array.length()).map { array.getString(it) })
    }.getOrDefault(emptyList()))
    override val words = current.asStateFlow()
    override val automatic = dao.observePeople().map { people ->
        Hotwords.normalize(people.map { it.name }.filterNot { it == "我" }, Int.MAX_VALUE)
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())
    override fun setWords(words: List<String>) {
        val normalized = Hotwords.normalize(words)
        check(prefs.edit().putString("hotwords", JSONArray(normalized).toString()).commit()) { "热词保存失败" }
        current.value = normalized
    }
    fun merged() = Hotwords.merge(words.value, automatic.value)
}
