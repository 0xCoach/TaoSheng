package app.resound.transcribe

import kotlinx.coroutines.flow.MutableStateFlow

/** 录音服务串行处理播放变化和按钮动作。采样协程只读取时间窗。 */
class PlaybackSession {
    private var identity: String? = null
    private var speaker = false
    private var rule = PlaybackOverride.Default
    private val window = MutableStateFlow<SpeakerInterval?>(null)
    val canRemember get() = speaker && rule == PlaybackOverride.Skip && window.value?.until != Long.MAX_VALUE

    fun update(playing: Boolean, audible: Boolean, session: String?, selection: PlaybackOverride, at: Long) {
        if (!playing || session != identity) {
            window.value?.takeIf { it.until == Long.MAX_VALUE }?.let { window.value = it.copy(until = at) }
        }
        identity = session
        speaker = audible
        rule = selection
    }
    fun remember(at: Long) {
        if (canRemember) window.value = SpeakerInterval(at, Long.MAX_VALUE)
    }
    fun remembers(at: Long) = window.value?.let { at >= it.from && at < it.until } == true
}
