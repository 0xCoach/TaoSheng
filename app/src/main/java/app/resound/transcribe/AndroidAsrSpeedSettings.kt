package app.resound.transcribe

import android.content.SharedPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** 保存设置变更历史，积压录音、跨午夜、进程重启均按段开始时刻查询。 */
class AndroidAsrSpeedSettings(
    private val prefs: SharedPreferences,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) : AsrSpeedSettings {
    private data class Entry(val at: Long, val separate: Boolean, val classroom: Boolean, val until: Long?)
    private var history = JSONArray(prefs.getString("history", "[]")).let { rows ->
        (0 until rows.length()).map { i -> rows.getJSONObject(i).let {
            Entry(it.getLong("at"), it.getBoolean("separate"), it.getBoolean("classroom"),
                if (it.isNull("until")) null else it.getLong("until"))
        } }
    }
    private val separate = MutableStateFlow(latest().separate)
    override val separateSpeakers = separate.asStateFlow()
    private val mode = MutableStateFlow(state(latest(), now()))
    override val classMode = mode.asStateFlow()
    private var expiry: Job? = null
    init { refresh() }

    private fun latest() = history.lastOrNull() ?: Entry(Long.MIN_VALUE, true, false, null)
    private fun state(entry: Entry, at: Long): ClassModeState =
        if (entry.classroom && (entry.until == null || at < entry.until)) ClassModeState(true, entry.until)
        else ClassModeState()

    /** 时间变更广播也调用；定时器只更新展示，业务判断始终直接使用持久化时间。 */
    @Synchronized fun refresh() {
        expiry?.cancel()
        separate.value = latest().separate
        mode.value = state(latest(), now())
        mode.value.endsAtMillis?.let { until ->
            expiry = scope.launch { delay((until - now()).coerceAtLeast(1)); refresh() }
        }
    }

    @Synchronized private fun save(entry: Entry) {
        val updated = history + entry
        val json = JSONArray(updated.map { JSONObject().put("at", it.at).put("separate", it.separate)
            .put("classroom", it.classroom).put("until", it.until ?: JSONObject.NULL) })
        check(prefs.edit().putString("history", json.toString()).commit()) { "课堂模式设置保存失败" }
        history = updated
        refresh()
    }

    @Synchronized override fun startClassMode(durationMinutes: Int?) {
        require(durationMinutes == null || durationMinutes > 0)
        val at = now()
        save(latest().copy(at = at, classroom = true, until = durationMinutes?.let { Math.addExact(at, it * 60_000L) }))
    }
    @Synchronized override fun stopClassMode() { save(latest().copy(at = now(), classroom = false, until = null)) }
    @Synchronized override fun setSeparateSpeakers(on: Boolean) { save(latest().copy(at = now(), separate = on)) }
    @Synchronized override fun effectiveSeparateSpeakers(segmentStartMillis: Long): Boolean {
        // 回拨系统时间后以最后一次写入的同时间设置为准；不丢掉较早录音所依赖的记录。
        val entry = history.withIndex().filter { it.value.at <= segmentStartMillis }
            .maxWithOrNull(compareBy<IndexedValue<Entry>> { it.value.at }.thenBy { it.index })?.value
            ?: Entry(Long.MIN_VALUE, true, false, null)
        return entry.separate && !state(entry, segmentStartMillis).enabled
    }
}
