package app.resound.screentime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 「记录播放标题」开关，默认关闭；关闭时不写播放记录。写法照 DigestPreferenceStore：成功提交后才发布状态。 */
class PlaybackTitleSettings(
    readBoolean: (String, Boolean) -> Boolean,
    private val saveBoolean: (String, Boolean) -> Boolean,
    private val readLong: (String, Long) -> Long,
    private val saveLong: (String, Long) -> Boolean,
    private val now: () -> Long,
) {
    private val _enabled = MutableStateFlow(readBoolean(KEY, false))
    val enabled = _enabled.asStateFlow()

    fun setEnabled(on: Boolean) {
        val was = _enabled.value
        check(saveBoolean(KEY, on))
        if (!was && on) check(saveLong(ENABLED_AT_KEY, now()))
        _enabled.value = on
    }

    /** 最近一次从关到开的时刻（毫秒）；从未打开为 0。 */
    val enabledAt: Long get() = readLong(ENABLED_AT_KEY, 0)

    companion object {
        const val KEY = "playbackTitleEnabled"
        const val ENABLED_AT_KEY = "playbackTitleEnabledAt"
    }
}
