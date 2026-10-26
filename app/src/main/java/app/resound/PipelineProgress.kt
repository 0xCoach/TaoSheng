package app.resound

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 后台这一轮（转写 → 识别说话人 → 整理 → 技能卡片）走到哪一步、进度多少。
 * 首页“现在整理”据此逐步显示，免得以为卡死。只存本进程内存，进程重启即清空。
 */
object PipelineProgress {
    enum class Stage(val step: Int, val title: String, val short: String) {
        Queued(0, "排队中", "排队"),
        Transcribe(1, "转写录音", "转写"),
        Voiceprint(2, "识别说话人", "认人"),
        Digest(3, "整理成事件", "整理"),
        Skills(4, "生成技能卡片", "卡片"),
    }

    data class State(
        val stage: Stage? = null,
        val done: Int = 0,
        val total: Int = 0,
        /** 步骤内的细节，如本地转写的“区分说话人（CPU）· 45%”，或排队的原因 */
        val detail: String? = null,
        /** 进入这一步的时刻（elapsedRealtime），界面显示“本步已用多久” */
        val since: Long = 0,
    )

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    /** 已点了“现在整理”但还没开始跑（等网络、等上一轮结束）。正在跑时不覆盖。 */
    fun queued(reason: String) = _state.update { if (it.stage == null || it.stage == Stage.Queued) State(Stage.Queued, detail = reason, since = now()) else it }
    fun stage(stage: Stage) { _state.value = State(stage, since = now()) }
    fun progress(done: Int, total: Int) = _state.update { it.copy(done = done, total = total) }
    fun detail(text: String?) = _state.update { it.copy(detail = text) }
    fun finish() { _state.value = State() }

    private fun now() = SystemClock.elapsedRealtime()
}
