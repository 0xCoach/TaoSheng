package app.resound.digest

import app.resound.data.MemoryEventEntity
import java.time.LocalDate
import kotlin.math.roundToInt

internal object DayMoodCalculator {
    private val valences = mapOf("愉快" to 1.0, "兴奋" to .9, "平静" to .3, "紧张" to -.4,
        "焦虑" to -.6, "烦躁" to -.7, "疲惫" to -.7, "生气" to -.8, "低落" to -1.0)

    fun energy(weightedValence: Double, weight: Double): Int? =
        if (weight <= 0) null else ((weightedValence / weight + 1) * 50).roundToInt().coerceIn(0, 100)

    fun calculate(date: LocalDate, events: List<MemoryEventEntity>): DayMood {
        val minutes = sortedMapOf<String, Int>()
        var weight = 0.0
        var weightedValence = 0.0
        var count = 0
        for (event in events) {
            if (event.deleted || event.scene in setOf("媒体", "旁人")) continue
            val emotion = event.emotion?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            val duration = (event.endAt - event.startAt).coerceAtLeast(0)
            val durationMinutes = (duration / 60_000 + if (duration % 60_000 > 0) 1 else 0).coerceAtLeast(1).toInt()
            minutes[emotion] = (minutes[emotion] ?: 0) + durationMinutes
            count++
            // 未知情绪保留分钟，不猜测效价。
            val valence = valences[emotion] ?: continue
            val w = durationMinutes * (event.intensity ?: 3).coerceIn(1, 5) / 3.0
            weight += w
            weightedValence += valence * w
        }
        return DayMood(date, minutes, minutes.maxByOrNull { it.value }?.key, energy(weightedValence, weight), count)
    }
}
