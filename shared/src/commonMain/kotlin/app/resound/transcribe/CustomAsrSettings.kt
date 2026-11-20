package app.resound.transcribe

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class CustomAsrApi { OpenAi, Qwen }
enum class CustomSpeakerFormat { Auto, Diarized }
enum class CustomKeyState { None, Saved, Verified }
enum class CustomCapability { Speakers, TimedText, TextOnly }
data class CustomSaveResult(val keySaved: Boolean, val keyState: CustomKeyState)

/** 两套配置共用规则；密钥加解密、持久化和地址校验由平台注入。
 * 验证结论存本机（不进备份），平台按“地址+模型+写法+说话人格式+密钥+请求头”的指纹存取，任一项变了读回为空。 */
class CustomAsrSettings(
    private val read: (String, String?) -> String?,
    private val readBoolean: (String, Boolean) -> Boolean,
    private val write: (String, String) -> Unit,
    private val writeBoolean: (String, Boolean) -> Unit,
    private val writeFields: (CustomAsrApi, String, String, CustomEndpointMode) -> Unit,
    private val validate: (String, CustomEndpointMode) -> Unit,
    private val hasCredential: (CustomAsrApi) -> Boolean,
    private val changed: () -> Unit,
    private val loadVerified: (CustomAsrApi) -> CustomCapability? = { null },
    private val saveVerified: (CustomAsrApi, CustomCapability?) -> Unit = { _, _ -> },
) {
    private inline fun <reified T : Enum<T>> enum(key: String, default: T): T =
        enumValues<T>().firstOrNull { it.name == read(key, null) } ?: default
    private val _api = MutableStateFlow(enum("customApi", CustomAsrApi.OpenAi))
    val api = _api.asStateFlow()
    private val _speaker = MutableStateFlow(enum("customSpeakerFormat", CustomSpeakerFormat.Auto))
    val speakerFormat = _speaker.asStateFlow()
    private val _mode = MutableStateFlow(mode())
    val endpointMode = _mode.asStateFlow()
    private val _prompt = MutableStateFlow(prompt())
    val customPrompt = _prompt.asStateFlow()
    private val _keyState = MutableStateFlow(CustomKeyState.None)
    val keyState = _keyState.asStateFlow()
    private val _capability = MutableStateFlow<CustomCapability?>(null)
    val capability = _capability.asStateFlow()
    private val verified = mutableMapOf<CustomAsrApi, CustomCapability>()
    private var revision = 0L
    val endpoint get() = if (api.value == CustomAsrApi.Qwen) read("qwenEndpoint", QWEN_ENDPOINT).orEmpty() else read("endpoint", "").orEmpty()
    val model get() = if (api.value == CustomAsrApi.Qwen) read("qwenModel", QWEN_MODEL).orEmpty() else read("model", "").orEmpty()
    private fun mode() = if (api.value == CustomAsrApi.Qwen) CustomEndpointMode.Full else enum("endpointMode", CustomEndpointMode.Auto)
    private fun prompt() = if (api.value == CustomAsrApi.Qwen) readBoolean("qwenPrompt", true) else readBoolean("customPrompt", false)
    fun setApi(value: CustomAsrApi) {
        if (value == api.value) return
        write("customApi", value.name)
        // 接口格式变化即清除当前验证结论；两套配置和密钥仍各自保留。
        revision++
        _api.value = value; _mode.value = mode(); _prompt.value = prompt()
        refresh(); changed()
    }
    fun setSpeakerFormat(value: CustomSpeakerFormat) {
        if (value == _speaker.value) return
        write("customSpeakerFormat", value.name); _speaker.value = value
        invalidate(CustomAsrApi.OpenAi); changed()
    }
    fun setPrompt(on: Boolean) {
        writeBoolean(if (api.value == CustomAsrApi.Qwen) "qwenPrompt" else "customPrompt", on)
        _prompt.value = on; changed()
    }
    fun validateFields(endpoint: String, model: String, mode: CustomEndpointMode) {
        validate(endpoint, if (api.value == CustomAsrApi.Qwen) CustomEndpointMode.Full else mode)
        require(model.trim().isNotEmpty()) { "请填写或选择模型" }
    }
    fun save(endpoint: String, model: String, mode: CustomEndpointMode) {
        validateFields(endpoint, model, mode)
        val actualMode = if (api.value == CustomAsrApi.Qwen) CustomEndpointMode.Full else mode
        val different = this.endpoint != endpoint.trim() || this.model != model.trim() || _mode.value != actualMode
        writeFields(api.value, endpoint.trim(), model.trim(), actualMode)
        _mode.value = actualMode
        if (different) invalidate(api.value) else refresh()
        changed()
    }
    fun invalidate(api: CustomAsrApi) { revision++; verified.remove(api); saveVerified(api, null); refresh() }
    fun invalidateAll() { revision++; verified.clear(); CustomAsrApi.entries.forEach { saveVerified(it, null) }; refresh() }
    fun testTicket() = revision to api.value
    fun tested(ticket: Pair<Long, CustomAsrApi>, capability: CustomCapability) {
        if (ticket != testTicket()) return
        verified[ticket.second] = capability; saveVerified(ticket.second, capability); refresh()
    }
    fun refresh() {
        _capability.value = verified[api.value] ?: loadVerified(api.value)?.also { verified[api.value] = it }
        _keyState.value = when {
            _capability.value != null -> CustomKeyState.Verified
            hasCredential(api.value) -> CustomKeyState.Saved
            else -> CustomKeyState.None
        }
    }
    companion object {
        const val QWEN_ENDPOINT = "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation"
        const val QWEN_MODEL = "qwen-audio-3.1-asr-flash"
        fun keyField(api: CustomAsrApi) = if (api == CustomAsrApi.Qwen) "qwenEncryptedKey" else "customEncryptedKey"
    }
}
