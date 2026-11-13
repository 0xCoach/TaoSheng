package app.resound.digest

import kotlinx.coroutines.flow.StateFlow

/** 整理用的大模型服务：模型检测平台适配留安卓，签名与默认实现不变。 */

interface DigestSettings {
    /** 卡 130：整理时把每段原话当时的地点和环境音一起交给大模型（位置会发给大模型服务商，默认关） */
    val includeSituation: kotlinx.coroutines.flow.StateFlow<Boolean> get() = kotlinx.coroutines.flow.MutableStateFlow(false)
    fun setIncludeSituation(on: Boolean) {}
    fun headers(presetId: String): List<HeaderEntry> = emptyList()
    fun saveHeaders(presetId: String, headers: List<HeaderEntry>) {}
    val presets: List<LlmPreset>

    /** 当前选中的服务 id */
    val presetId: StateFlow<String>

    fun setPreset(id: String)

    /** 当前服务是否已填密钥 */
    val hasKey: StateFlow<Boolean>

    fun saveKey(key: String)

    fun clearKey()

    /** 发一句很短的话确认能用 */
    suspend fun test(): Result<String>

    /** 按功能逐项检测当前模型（连接、按格式回答、问问的流式回答/工具调用/标注出处）；没法开始检测（没填密钥等）给 failure */
    suspend fun check(): Result<List<ModelCheckItem>> = test().map { listOf(ModelCheckItem(ModelCheck.CONNECT, true, it)) }
}
