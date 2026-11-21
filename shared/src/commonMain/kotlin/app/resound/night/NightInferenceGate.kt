package app.resound.night

/** Full 专用。前后各两个连续窗口都低于背景时才可跳过，缺口和流尾保守识别。 */
class NightInferenceGate(private val process: suspend (NightWindow, Boolean) -> Boolean) {
    private val pending = ArrayDeque<NightWindow>()
    private var previousAt: Long? = null
    private var quietBefore = 0
    private var protectedAfter = 0

    suspend fun offer(window: NightWindow) {
        if (previousAt?.let { window.start - it != 2_000L } == true) flush()
        previousAt = window.start
        pending.addLast(window)
        while (pending.isNotEmpty()) {
            val quiet = pending.all { it.features.aboveBackground < -1 }
            val candidate = quietBefore >= 2 && protectedAfter == 0 && quiet
            if (candidate && pending.size < 3) return
            consume(infer = !candidate)
        }
    }

    private suspend fun consume(infer: Boolean) {
        val window = pending.removeFirst()
        val protected = process(window, infer)
        protectedAfter = if (protected) 2 else (protectedAfter - 1).coerceAtLeast(0)
        quietBefore = if (window.features.aboveBackground < -1) (quietBefore + 1).coerceAtMost(2) else 0
    }

    suspend fun flush() {
        while (pending.isNotEmpty()) consume(infer = true)
        previousAt = null
        quietBefore = 0
        protectedAfter = 0
    }
}
