package app.resound.envsound

/** IntRange.count() 会遍历范围；不能放在逐采样点循环里。保持原来的归一化和一秒补零。 */
internal object EnvSoundSamples {
    fun window(pcm: ShortArray, window: IntRange): FloatArray {
        require(!window.isEmpty() && window.first >= 0 && window.last < pcm.size)
        val start = window.first
        val length = window.last - start + 1
        return FloatArray(maxOf(16000, length)) { i ->
            if (i < length) pcm[start + i] / 32768f else 0f
        }
    }
}
