package app.resound.onboarding

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/** 全部状态只在 onboarding 偏好中，不加入任何备份白名单。 */
internal class LocalOnboarding(
    private val prefs: SharedPreferences,
    private val backupPrefs: SharedPreferences,
    earliestTranscript: Flow<Long?>,
    multipleSpeakersAt: Flow<Long?>,
    needsLocalSpeakers: Flow<Boolean>,
    heartbeat: Flow<Long>,
    scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) : Onboarding {
    private val first = MutableStateFlow(prefs.getLong(FIRST, 0).takeIf { prefs.contains(FIRST) })
    override val firstTranscriptAt = first.asStateFlow()
    private val currentTips = MutableStateFlow<List<OnboardingTip>>(emptyList())
    override val tips = currentTips.asStateFlow()
    private var multipleAt: Long? = null
    private var needsSpeakers = false
    // 强引用监听器，避免 SharedPreferences 的弱引用被回收。
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refresh() }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
        backupPrefs.registerOnSharedPreferenceChangeListener(listener)
        scope.coroutineContext[kotlinx.coroutines.Job]?.invokeOnCompletion {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
            backupPrefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
        refresh()
        if (first.value == null) scope.launch {
            var initialSnapshot = true
            earliestTranscript.first { at ->
                // 升级/恢复已有数据用最早一句时间；启动后新产出的第一句用实际入库观察时间。
                if (at != null) rememberFirst(if (initialSnapshot) at else now())
                initialSnapshot = false
                at != null
            }
        }
        scope.launch {
            combine(multipleSpeakersAt, needsLocalSpeakers) { at, needed -> at to needed }
                .collect { (at, needed) -> updateSpeakers(at, needed) }
        }
        // 只有提示正在显示时订阅分钟心跳；设置/数据变化仍即时更新。
        scope.launch { currentTips.subscriptionCount.map { it > 0 }.distinctUntilChanged().collectLatest { visible ->
            if (visible) heartbeat.collect { refresh() }
        } }
    }

    @Synchronized private fun rememberFirst(at: Long) {
        if (first.value != null) return
        val edit = prefs.edit().putLong(FIRST, at)
        if (prefs.contains(WIZARD)) edit.putLong(ELAPSED, (at - prefs.getLong(WIZARD, at)).coerceAtLeast(0))
        check(edit.commit())
        first.value = at
        refresh()
    }

    @Synchronized private fun updateSpeakers(at: Long?, needed: Boolean) {
        multipleAt = at
        needsSpeakers = needed
        refresh()
    }

    @Synchronized private fun refresh() {
        // 兼容升级前已成功的备份；成功记录随后因切换目录/账号清空也不再提示。
        if (!prefs.getBoolean(BACKED_UP, false) &&
            (backupPrefs.getLong("lastAt", 0) > 0 || backupPrefs.getLong("localLastAt", 0) > 0)) {
            check(prefs.edit().putBoolean(BACKED_UP, true).commit())
        }
        val at = now()
        currentTips.value = buildList {
            if (prefs.getBoolean(INTERRUPTED, false)) add(OnboardingTip.KeepAlive)
            if (needsSpeakers && multipleAt?.let { it <= at && at - it < 2 * DAY } == true) add(OnboardingTip.Speakers)
            if (!prefs.getBoolean(BACKED_UP, false) && first.value?.let { at - it > 3 * DAY } == true) add(OnboardingTip.Backup)
        }.filterNot { prefs.getBoolean("dismissed_${it.name}", false) }
    }

    @Synchronized override fun dismiss(tip: OnboardingTip) {
        check(prefs.edit().putBoolean("dismissed_${tip.name}", true).commit())
        refresh()
    }

    @Synchronized override fun record(event: String) {
        // 未约定的输入归为 other，不把误传的句子、密钥或任意字符串落盘。
        val name = if (event in EVENTS) event else "other"
        val key = "count_$name"
        check(prefs.edit().putLong(key, prefs.getLong(key, 0).let { if (it == Long.MAX_VALUE) it else it + 1 }).commit())
    }

    @Synchronized override fun wizardFinished() {
        if (prefs.contains(WIZARD)) return
        val at = now()
        val edit = prefs.edit().putLong(WIZARD, at)
        // 第四页试说通常先出句子再点完成，此时等待时间为 0。
        first.value?.let { edit.putLong(ELAPSED, (it - at).coerceAtLeast(0)) }
        check(edit.commit())
    }

    companion object {
        internal const val DAY = 86_400_000L
        private const val FIRST = "firstTranscriptAt"
        private const val WIZARD = "wizardFinishedAt"
        private const val INTERRUPTED = "captureInterrupted"
        private const val BACKED_UP = "backupSucceeded"
        internal const val ELAPSED = "first_transcript_after_wizard_ms"
        private val EVENTS = setOf("wizard_shown", "mic_granted", "mic_denied", "key_saved", "key_skipped",
            "key_from_clipboard", "wizard_done", "wizard_skipped")
        private fun prefs(context: Context) = context.getSharedPreferences("onboarding", Context.MODE_PRIVATE)
        fun captureInterrupted(context: Context) { prefs(context).edit().putBoolean(INTERRUPTED, true).apply() }
        fun backupSucceeded(context: Context) { check(prefs(context).edit().putBoolean(BACKED_UP, true).commit()) }
        fun diagnostics(prefs: SharedPreferences): String = buildString {
            (EVENTS + "other").sorted().forEach { appendLine("$it count=${prefs.getLong("count_$it", 0)}") }
            listOf(FIRST, WIZARD, ELAPSED).forEach { key ->
                appendLine("$key=${if (prefs.contains(key)) prefs.getLong(key, 0).toString() else "未记录"}")
            }
        }
    }
}
