package app.resound.importer

import kotlin.math.abs
import kotlin.math.sqrt

internal object ImportKindPolicy {
    data class BatchFile(val id: String, val start: Long, val duration: Long, val recorded: Boolean)
    fun batch(files: List<BatchFile>, merge: Boolean): List<List<BatchFile>> {
        var previousEnd: Long? = null
        val resolved = files.map { file ->
            val start = if (file.recorded) file.start else previousEnd ?: file.start
            file.copy(start = start).also { previousEnd = start + file.duration }
        }.sortedBy { it.start }
        if (!merge) return files.map { listOf(it) }
        val groups = mutableListOf<MutableList<BatchFile>>()
        for (file in resolved) {
            val end = groups.lastOrNull()?.maxOf { it.start + it.duration }
            if (end == null || file.start - end > 5 * 60_000) groups += mutableListOf<BatchFile>()
            groups.last() += file
        }
        return groups
    }
    fun suggest(name: String, source: String, dual: Boolean, last: ImportKind?): KindSuggestion {
        val path = source.replace('\\', '/').lowercase()
        val phone = Regex("(?<![0-9])(?:\\+?86[- ]?)?1[3-9][0-9]{9}(?![0-9])|(?<![0-9])0[0-9]{2,3}[- ][0-9]{7,8}(?![0-9])")
        return when {
            path.contains("miui/sound_recorder/call_rec") || "通话" in name || phone.containsMatchIn(name) -> KindSuggestion(ImportKind.Call, KindReason.CallFolder)
            dual -> KindSuggestion(ImportKind.Call, KindReason.DualChannel)
            last != null -> KindSuggestion(last, KindReason.LastUsed)
            else -> KindSuggestion(ImportKind.Meeting, KindReason.Default)
        }
    }
    fun separate(kind: ImportKind, byChannel: Boolean) = kind != ImportKind.Training && !byChannel
    fun speakers(kind: ImportKind, byChannel: Boolean): Int? = if (kind == ImportKind.Training) 0 else if (kind == ImportKind.Call && !byChannel) 2 else null
}

/** 有效双声道的保守线索：两侧都有声音且绝对相关系数低；同内容的增益差/反相不算。 */
internal class StereoEvidence {
    private var count = 0L
    private var left = 0.0; private var right = 0.0; private var cross = 0.0
    fun accept(samples: ShortArray, channels: Int) {
        if (channels != 2) return
        var i = 0
        while (i + 1 < samples.size) {
            val l = samples[i].toDouble(); val r = samples[i + 1].toDouble()
            left += l * l; right += r * r; cross += l * r; count++; i += 2
        }
    }
    fun distinct() = count >= 16_000 && left / count > 40_000 && right / count > 40_000 &&
        abs(cross) / sqrt(left * right) < 0.8
}
