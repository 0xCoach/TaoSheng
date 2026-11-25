package app.resound.transcribe

import app.resound.platform.KeyValueStores
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object Hotwords {
    fun normalize(words: List<String>, limit: Int = 200): List<String> = words.asSequence()
        .map(String::trim)
        .filter { word -> word.codePointCount() in 1..20 && word.none(Char::isISOControl) }
        .distinct().take(limit).toList()

    private fun String.codePointCount() = indices.count { index ->
        index == 0 || !this[index].isLowSurrogate() || !this[index - 1].isHighSurrogate()
    }

    fun merge(manual: List<String>, automatic: List<String>) = normalize(manual + automatic, Int.MAX_VALUE)
    // 当前使用 bigmodel_nostream，热词直传上限 5000 词。
    fun doubao(words: List<String>) = words.take(5000)
    fun customPrompt(words: List<String>): String = if (words.isEmpty()) "" else
        ("可能出现的词：" + words.joinToString("、")).take(200).let {
            if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it
        }
}

class HotwordPreferenceStore(stores: KeyValueStores, onReadFailure: (Throwable) -> Unit = {}) {
    private val prefs = stores.open(TranscribeConfiguration.GROUP)
    private val current = MutableStateFlow(runCatching {
        Hotwords.normalize(prefs.getStrings("hotwords"))
    }.onFailure(onReadFailure).getOrDefault(emptyList()))
    val words = current.asStateFlow()

    fun setWords(words: List<String>) {
        val normalized = Hotwords.normalize(words)
        check(prefs.commit { putStrings("hotwords", normalized) }) { "热词保存失败" }
        current.value = normalized
    }
}
