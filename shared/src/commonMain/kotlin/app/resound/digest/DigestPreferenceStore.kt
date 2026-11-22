package app.resound.digest

import app.resound.transcribe.MAX_INTERVAL
import app.resound.transcribe.MIN_INTERVAL

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 旧 Int/Boolean 格式由内部读写回调保留；成功提交后才发布状态。 */
class DigestPreferenceStore(
    readBoolean: (String, Boolean) -> Boolean,
    readInt: (String, Int) -> Int,
    legacyInterval: () -> Int,
    private val saveBoolean: (String, Boolean) -> Boolean,
    private val saveInt: (String, Int) -> Boolean,
    /** 是否全新安装（不是从旧版升级上来）；新装的不用提示“已为你打开先思考” */
    newInstall: () -> Boolean = { false },
) {
    private val _includeSituation = MutableStateFlow(readBoolean("includeSituation", false))
    val includeSituation = _includeSituation.asStateFlow()
    fun setIncludeSituation(on: Boolean) {
        check(saveBoolean("includeSituation", on))
        _includeSituation.value = on
    }
    /** 整理时把 App 与节目标题发给大模型；默认开，与地点/环境开关互不影响。 */
    private val _includeAppPlayback = MutableStateFlow(readBoolean("includeAppPlayback", true))
    val includeAppPlayback = _includeAppPlayback.asStateFlow()
    fun setIncludeAppPlayback(on: Boolean) {
        check(saveBoolean("includeAppPlayback", on))
        _includeAppPlayback.value = on
    }
    /** 两句话之间停多久算这段对话结束（分钟）；结束后才整理，避免一件事被切成两半 */
    private val _pauseMinutes = MutableStateFlow(readInt("pauseMinutes", DEFAULT_PAUSE).coerceIn(PAUSE_RANGE))
    val pauseMinutes = _pauseMinutes.asStateFlow()
    /** 一直在说、对话迟迟不结束时，最多等多久先整理前面已稳定的部分（分钟） */
    private val _maxWaitMinutes = MutableStateFlow(readInt("maxWaitMinutes", DEFAULT_MAX_WAIT).coerceIn(MAX_WAIT_RANGE))
    val maxWaitMinutes = _maxWaitMinutes.asStateFlow()
    /** 多久整理一次（分钟）；第一次沿用原来“多久转写一次”的设置（以前两者是同一个定时任务） */
    private val _intervalMinutes = MutableStateFlow(readInt("interval",
        legacyInterval())
        .coerceIn(MIN_INTERVAL, MAX_INTERVAL))
    val intervalMinutes = _intervalMinutes.asStateFlow()
    /** 间隔改了要重排整理任务；由 ResoundApp 装配 */
    var onIntervalChanged: () -> Unit = {}
    fun setIntervalMinutes(value: Int) {
        val v = value.coerceIn(MIN_INTERVAL, MAX_INTERVAL)
        check(saveInt("interval", v)); _intervalMinutes.value = v; onIntervalChanged()
    }
    /**
     * 整理前先让模型思考：分条更准，不容易切碎，但费用约为原来的 3～5 倍。
     * 卡 222：所有人默认开，包括老用户——升级到这一版时统一打开一次，原来是关着的会在首页提示一次（说明好处、可自己关）。
     * 打开过一次后就尊重用户自己的选择，之后升级不再改。
     */
    private val _deepThinkingNotice = MutableStateFlow(readBoolean("deepThinkingNotice", false))
    /** 老用户升级后被打开了“整理前先思考”，首页提示一次 */
    val deepThinkingNotice = _deepThinkingNotice.asStateFlow()
    fun dismissDeepThinkingNotice() { saveBoolean("deepThinkingNotice", false); _deepThinkingNotice.value = false }
    private val _deepThinking = MutableStateFlow(
        if (readBoolean("deepThinkingDefaultOn", false)) readBoolean("deepThinking", true)
        else {
            val wasOff = !newInstall() && !readBoolean("deepThinking", false)
            saveBoolean("deepThinking", true); saveBoolean("deepThinkingDefaultOn", true)
            if (wasOff) { saveBoolean("deepThinkingNotice", true); _deepThinkingNotice.value = true }
            true
        })
    val deepThinking = _deepThinking.asStateFlow()
    fun setDeepThinking(on: Boolean) { check(saveBoolean("deepThinking", on)); _deepThinking.value = on }
    fun setPauseMinutes(value: Int) {
        val v = value.coerceIn(PAUSE_RANGE)
        check(saveInt("pauseMinutes", v)); _pauseMinutes.value = v
    }
    fun setMaxWaitMinutes(value: Int) {
        val v = value.coerceIn(MAX_WAIT_RANGE)
        check(saveInt("maxWaitMinutes", v)); _maxWaitMinutes.value = v
    }
    companion object {
        const val DEFAULT_PAUSE = 10
        const val DEFAULT_MAX_WAIT = 30
        val PAUSE_RANGE = 3..60
        val MAX_WAIT_RANGE = 15..240
    }
}
