package app.resound.transcribe

import android.content.Context
import app.resound.scheduling.PipelineDue
import kotlinx.coroutines.flow.MutableStateFlow

/** 只在流水线锁内写；判断只读内存快照。写盘成功后才允许开始网络请求。 */
internal class PipelineScheduleStore(context: Context, now: Long) {
    private val prefs = context.getSharedPreferences("pipeline-due-188", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(PipelineDue.Mode.entries.associateWith { mode ->
        val key = mode.name
        PipelineDue.History(prefs.getLong("$key.attempt", now),
            prefs.getLong("$key.success", 0).takeIf { it > 0 },
            runCatching { PipelineDue.Failure.valueOf(prefs.getString("$key.failure", "None")!!) }
                .getOrDefault(PipelineDue.Failure.None), prefs.getInt("$key.failures", 0))
    })
    init { state.value.forEach { (mode, history) -> save(mode, history) } }
    fun history(mode: PipelineDue.Mode) = state.value.getValue(mode)
    fun save(mode: PipelineDue.Mode, history: PipelineDue.History) {
        val key = mode.name
        check(prefs.edit().putLong("$key.attempt", history.lastAttempt)
            .putLong("$key.success", history.lastSuccess ?: 0)
            .putString("$key.failure", history.failure.name).putInt("$key.failures", history.failures).commit())
        state.value = state.value + (mode to history)
    }
}
