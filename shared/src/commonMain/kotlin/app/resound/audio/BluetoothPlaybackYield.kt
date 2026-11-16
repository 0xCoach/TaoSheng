package app.resound.audio

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*

/** 播放立即让出，连续停播三秒才接回；重复状态不会重置确认窗口。 */
object BluetoothPlaybackYield {
    const val REASON = "正在播放视频或音乐，蓝牙麦已暂时让出"
    fun eligible(kind: InputKind, enabled: Boolean) = enabled &&
        (kind == InputKind.BluetoothCall || kind == InputKind.BluetoothLe)

    @OptIn(ExperimentalCoroutinesApi::class)
    fun held(playing: Flow<Boolean>): Flow<Boolean> = flow {
        var held = false
        playing.distinctUntilChanged().transformLatest { active ->
            if (!active && held) delay(3_000)
            held = active
            emit(active)
        }.collect { emit(it) }
    }.distinctUntilChanged()
}
