package app.resound.envsound

import android.content.Context
import app.resound.audio.SileroVad
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** 复用随包 Silero 和批次内 session；每个文件重新清状态，PCM 直接来自073共享解码。 */
internal class EnvSoundGapExtractor(private val context: Context) {
    private var batch = false
    private var retained: SileroVad? = null
    @Synchronized fun beginBatch() { batch = true }
    @Synchronized fun endBatch() { batch = false; retained?.close(); retained = null }

    suspend fun extract(pcm: ShortArray): EnvSoundGaps.Result {
        val coroutine = currentCoroutineContext()
        val vad = retained ?: SileroVad(context).also { if (batch) retained = it }
        try {
            vad.reset()
            val collector = EnvSoundGaps.Collector()
            val consume: (ShortArray, Boolean, Float) -> Unit = { frame, speech, _ ->
                coroutine.ensureActive()
                collector.accept(frame.size, speech)
            }
            vad.accept(pcm, consume)
            vad.finish(consume)
            return collector.finish(pcm)
        } finally {
            if (!batch) vad.close()
        }
    }
}
