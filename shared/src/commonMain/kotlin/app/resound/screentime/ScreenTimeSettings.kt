package app.resound.screentime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 屏幕时间采集开关，默认打开（还要用户授予“使用情况访问”才真正采集）；关闭时不调用事件源、不拉取。不管播放记录。
 *  一次开关切换涉及的键由 [persist] 同一次提交写入，成功后才发布状态。 */
class ScreenTimeSettings(
    readBoolean: (String, Boolean) -> Boolean,
    private val persist: (Map<String, Boolean>, Map<String, Long>, Map<String, String>) -> Boolean,
    private val readLong: (String, Long) -> Long,
    private val now: () -> Long,
    private val readString: (String, String) -> String = { _, def -> def },
) {
    private val _enabled = MutableStateFlow(readBoolean(KEY, true))
    private val _showOnHome = MutableStateFlow(readBoolean(HOME_KEY, true))
    private val _eventInUse = MutableStateFlow(readBoolean(EVENT_IN_USE_KEY, true))
    /** 首页录音卡显示屏幕用时、节奏图下显示手机使用条。 */
    val showOnHome = _showOnHome.asStateFlow()
    /** 时间线事件下面标“当时在用 某 App”。 */
    val eventInUse = _eventInUse.asStateFlow()

    fun setShowOnHome(on: Boolean) = storeFlag(HOME_KEY, _showOnHome, on)

    fun setEventInUse(on: Boolean) = storeFlag(EVENT_IN_USE_KEY, _eventInUse, on)

    private fun storeFlag(key: String, value: MutableStateFlow<Boolean>, on: Boolean) {
        check(persist(mapOf(key to on), emptyMap(), emptyMap()))
        value.value = on
    }

    val enabled = _enabled.asStateFlow()

    fun setEnabled(on: Boolean) {
        val transition = when {
            on == _enabled.value -> emptyMap()
            on -> mapOf(ENABLED_AT_KEY to now())
            else -> mapOf(DISABLED_AT_KEY to now())
        }
        val history = transition[ENABLED_AT_KEY]?.let(::closedWindow).orEmpty()
        check(persist(mapOf(KEY to on), transition, history))
        _enabled.value = on
    }

    private fun closedWindow(enabledAt: Long): Map<String, String> {
        val from = disabledAt
        if (from <= 0 || from >= enabledAt) return emptyMap()
        val stopped = from to enabledAt
        val ranges = disabledWindows()
        if (stopped in ranges) return emptyMap()
        return mapOf(DISABLED_WINDOWS_KEY to (ranges + stopped).joinToString(";") { (a, b) -> "$a,$b" })
    }

    /** 最近一次从关到开的时刻（毫秒）；从未打开为 0。 */
    val enabledAt: Long get() = readLong(ENABLED_AT_KEY, 0)

    /** 最近一次从开到关的时刻（毫秒）；从未关闭为 0。 */
    val disabledAt: Long get() = readLong(DISABLED_AT_KEY, 0)

    /** 历次已闭合的停采窗口（左闭右开）。不只保留最后一对关/开时刻。 */
    fun disabledWindows(): List<Pair<Long, Long>> {
        val raw = readString(DISABLED_WINDOWS_KEY, "")
        if (raw.isEmpty()) return emptyList()
        return buildList {
            for (entry in raw.split(';')) {
                val separator = entry.indexOf(',')
                if (separator < 0) continue
                val from = entry.substring(0, separator).toLongOrNull() ?: continue
                val until = entry.substring(separator + 1).toLongOrNull() ?: continue
                if (from < until) add(from to until)
            }
        }
    }

    /** 上次成功拉取时刻（毫秒）；0 表示从未拉过，只作节流判据，不进数据库。 */
    var lastPullAt: Long
        get() = readLong(LAST_PULL_KEY, 0)
        private set(value) { check(persist(emptyMap(), mapOf(LAST_PULL_KEY to value), emptyMap())) }
    internal fun markPulled(at: Long) { lastPullAt = at }

    companion object {
        const val KEY = "screenTimeEnabled"
        const val HOME_KEY = "screenTimeShowOnHome"
        const val EVENT_IN_USE_KEY = "screenTimeEventInUse"
        const val LAST_PULL_KEY = "screenTimeLastPullAt"
        const val ENABLED_AT_KEY = "screenTimeEnabledAt"
        const val DISABLED_AT_KEY = "screenTimeDisabledAt"
        const val DISABLED_WINDOWS_KEY = "screenTimeDisabledWindows"
    }
}
