package app.resound.transcribe.local

import android.content.Context
import app.resound.transcribe.AsrResult
import app.resound.transcribe.PipelineTimes
import com.github.promeg.pinyinhelper.Pinyin

/** 只由 LocalAsrProvider 调用，不依赖当前设置推断结果来源。 */
internal class LocalHotwordCorrection(context: Context, private val hotwords: () -> List<String>) {
    private val app = context.applicationContext
    private val corrector by lazy { HomophoneCorrector(HomophoneLexicon.load(app)) }

    fun apply(result: AsrResult): AsrResult {
        val words = hotwords()
        if (words.isEmpty()) return result
        val corrected = corrector.correct(result, words)
        PipelineTimes.record(app, "同音纠正 ${corrected.replacements} 处")
        return corrected.result
    }
}

internal class HomophoneLexicon(val commonWords: Set<String>, private val extraReadings: Map<Int, Set<String>>) {
    fun readings(codePoint: Int): Set<String> = extraReadings[codePoint] ?: if (
        codePoint <= Char.MAX_VALUE.code && Pinyin.isChinese(codePoint.toChar())
    ) setOf(Pinyin.toPinyin(codePoint.toChar()).replace('Ü', 'V')) else emptySet()

    companion object {
        // 词表和补充读音在进程中只加载一次；从不在日志中记录词条。
        @Volatile private var cached: HomophoneLexicon? = null
        fun load(context: Context): HomophoneLexicon = cached ?: synchronized(this) {
            cached ?: run {
                val common = context.assets.open("homophones/common-words.txt").bufferedReader().use { it.readLines().toSet() }
                val readings = context.assets.open("homophones/readings.txt").bufferedReader().useLines { lines ->
                    lines.associate { line -> line.codePointAt(0) to line.substringAfter(' ').split(',').toSet() }
                }
                HomophoneLexicon(common, readings).also { cached = it }
            }
        }
    }
}

internal class HomophoneCorrector(private val lexicon: HomophoneLexicon) {
    data class Correction(val result: AsrResult, val replacements: Int)
    private data class Word(val text: String, val sounds: List<Set<String>>)
    private var previousWords: List<String>? = null
    private var byFirstSound: Map<String, List<Word>> = emptyMap()
    private val characterCache = HashMap<Int, Set<String>>()
    private fun readings(cp: Int) = characterCache.getOrPut(cp) {
        if (Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN) lexicon.readings(cp) else emptySet()
    }

    /** 快照一致、热词变化才重建；按首字读音索引，避免每个位置扫描整个热词表。 */
    @Synchronized fun correct(result: AsrResult, hotwords: List<String>): Correction {
        if (previousWords != hotwords) {
            val words = hotwords.distinct().mapNotNull { text ->
                val points = text.codePoints().toArray()
                if (points.size < 2) return@mapNotNull null
                val sounds = points.map(::readings)
                if (sounds.any { it.isEmpty() }) null else Word(text, sounds)
            }
            byFirstSound = words.flatMap { word -> word.sounds.first().map { it to word } }
                .groupBy({ it.first }, { it.second }).mapValues { (_, words) -> words.sortedByDescending { it.sounds.size } }
            previousWords = hotwords.toList()
        }
        var count = 0
        val sentences = result.sentences.map { sentence ->
            val points = sentence.text.codePoints().toArray()
            val sounds = points.map(::readings)
            val output = StringBuilder()
            var position = 0
            while (position < points.size) {
                val candidates = sounds[position].flatMap { byFirstSound[it].orEmpty() }.distinct()
                    .filter { word -> position + word.sounds.size <= points.size && word.sounds.indices.all { offset ->
                        word.sounds[offset].any { it in sounds[position + offset] }
                    } }
                val longest = candidates.maxOfOrNull { it.sounds.size }
                if (longest == null) {
                    output.appendCodePoint(points[position++]); continue
                }
                val matches = candidates.filter { it.sounds.size == longest }
                val original = String(points, position, longest)
                // 同长歧义（包括原文就是某个热词）不改，也不退回较短候选。
                val replacement = matches.singleOrNull()?.text
                if (replacement != null && replacement != original && original !in lexicon.commonWords) {
                    output.append(replacement); count++
                } else output.append(original)
                position += longest
            }
            val text = output.toString()
            if (text == sentence.text) sentence else sentence.copy(text = text, originalText = sentence.originalText ?: sentence.text)
        }
        return Correction(result.copy(sentences = sentences), count)
    }
}
