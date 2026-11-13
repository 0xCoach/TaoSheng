package app.resound.digest

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 旧 Int/Boolean 格式由内部读写回调保留；成功提交后才发布状态。 */
class DigestPreferenceStore(
    readBoolean: (String, Boolean) -> Boolean,
    readInt: (String, Int) -> Int,
    legacyInterval: () -> Int,
    private val saveBoolean: (String, Boolean) -> Boolean,
    private val saveInt: (String, Int) -> Boolean,
) {
    private val _includeSituation = MutableStateFlow(readBoolean("includeSituation", false))
    val includeSituation = _includeSituation.asStateFlow()
    fun setIncludeSituation(on: Boolean) {
        check(saveBoolean("includeSituation", on))
        _includeSituation.value = on
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
        .coerceIn(app.resound.transcribe.MIN_INTERVAL, app.resound.transcribe.MAX_INTERVAL))
    val intervalMinutes = _intervalMinutes.asStateFlow()
    /** 间隔改了要重排整理任务；由 ResoundApp 装配 */
    var onIntervalChanged: () -> Unit = {}
    fun setIntervalMinutes(value: Int) {
        val v = value.coerceIn(app.resound.transcribe.MIN_INTERVAL, app.resound.transcribe.MAX_INTERVAL)
        check(saveInt("interval", v)); _intervalMinutes.value = v; onIntervalChanged()
    }
    /** 整理前先让模型思考：分条更准，但费用约为原来的 3～5 倍；默认关 */
    private val _deepThinking = MutableStateFlow(readBoolean("deepThinking", false))
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
