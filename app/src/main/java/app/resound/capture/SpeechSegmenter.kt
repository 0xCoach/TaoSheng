package app.resound.capture

/** 纯 Kotlin，保留精确的 1 秒前滚；五分钟硬切保留一秒重叠，不丢样本。 */
class SpeechSegmenter(private val sink: Sink) {
    interface Sink {
        fun begin(startSample: Long)
        fun write(samples: ShortArray)
        fun confirmed() {}
        fun playback(samples: Long) {}
        fun end(endSample: Long, keep: Boolean)
    }
    private val preRoll = ArrayDeque<Short>()
    private val playbackHistory = ArrayDeque<Boolean>()
    private var playbackSamples = 0L
    private var playing = false
    private var newSamples = 0L
    private var continuationSegment = false
    private var trailingSpeech = 0
    private var confirmed = false
    private var cursor = 0L
    private var start = 0L
    private var voiced = 0
    private var silence = 0
    var active = false
        private set

    fun accept(pcm: ShortArray, speech: Boolean, playback: Boolean = false) {
        playing = playback
        require(pcm.isNotEmpty())
        if (!active && speech) {
            start = cursor - preRoll.size
            active = true
            sink.begin(start)
            if (preRoll.isNotEmpty()) sink.write(preRoll.toShortArray())
            playbackSamples = playbackHistory.count { it }.toLong()
            sink.playback(playbackSamples)
            preRoll.clear(); playbackHistory.clear()
        }
        if (!active) {
            remember(pcm)
            cursor += pcm.size
            return
        }
        var offset = 0
        while (offset < pcm.size) {
            val room = (CaptureTuning.MAX_SEGMENT_SAMPLES - (cursor - start)).toInt()
            val untilSilence = if (speech) Int.MAX_VALUE else CaptureTuning.END_SILENCE_SAMPLES - silence
            val count = minOf(pcm.size - offset, room, untilSilence)
            val part = pcm.copyOfRange(offset, offset + count)
            sink.write(part)
            newSamples += count
            if (playing) playbackSamples += count
            sink.playback(playbackSamples)
            trailingSpeech = if (speech) (trailingSpeech + count).coerceAtMost(CaptureTuning.PRE_ROLL_SAMPLES) else 0
            remember(part)
            cursor += count
            offset += count
            if (speech) {
                voiced += count
                if (!confirmed && voiced >= CaptureTuning.MIN_SPEECH_SAMPLES) { sink.confirmed(); confirmed = true }
                silence = 0
            } else silence += count
            if (cursor - start == CaptureTuning.MAX_SEGMENT_SAMPLES.toLong() || silence == CaptureTuning.END_SILENCE_SAMPLES) {
                val continuation = silence < CaptureTuning.END_SILENCE_SAMPLES
                val tailSpeech = trailingSpeech
                closeSegment(clearHistory = false)
                if (continuation && (speech || offset < pcm.size)) {
                    start = cursor - preRoll.size
                    active = true
                    continuationSegment = true
                    voiced = tailSpeech
                    sink.begin(start)
                    sink.write(preRoll.toShortArray())
                    playbackSamples = playbackHistory.count { it }.toLong()
                    sink.playback(playbackSamples)
                } else if (offset < pcm.size) {
                    remember(pcm.copyOfRange(offset, pcm.size))
                    cursor += pcm.size - offset
                    return
                }
            }
        }
    }

    /** 暂停、麦克风占用、停止时收尾；不足 1 秒实际人声不被前滚和尾静音凑长。 */
    fun finish() = closeSegment(clearHistory = true)

    /** 隐私删除不保留当前人声，连前滚也清空。 */
    fun discard() {
        if (active) sink.end(cursor, false)
        active = false
        closeSegment(clearHistory = true)
    }

    private fun closeSegment(clearHistory: Boolean) {
        if (active) sink.end(cursor, voiced >= CaptureTuning.MIN_SPEECH_SAMPLES && (!continuationSegment || newSamples > 0))
        active = false
        voiced = 0
        silence = 0
        playbackSamples = 0; newSamples = 0; continuationSegment = false; confirmed = false
        if (clearHistory) { preRoll.clear(); playbackHistory.clear(); trailingSpeech = 0 }
    }

    private fun remember(pcm: ShortArray) {
        pcm.forEach {
            preRoll.addLast(it); playbackHistory.addLast(playing)
            if (preRoll.size > CaptureTuning.PRE_ROLL_SAMPLES) { preRoll.removeFirst(); playbackHistory.removeFirst() }
        }
    }
}
