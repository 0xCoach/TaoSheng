package app.resound.transcribe

import app.resound.transcribe.local.LocalAsrLanguage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 偏好值、默认与变更顺序；凭据/硬件状态和一次多键提交保留平台回调。 */
class TranscribePreferenceStore(
    private val readString: (String, String?) -> String?,
    readBoolean: (String, Boolean) -> Boolean, readInt: (String, Int) -> Int,
    private val contains: (String) -> Boolean,
    private val saveString: (String, String) -> Boolean,
    private val saveBoolean: (String, Boolean) -> Boolean,
    private val saveInt: (String, Int) -> Boolean,
    private val readLocalString: (String, String?) -> String?,
    private val saveLocalString: (String, String) -> Unit,
    private val clearNpuCheck: () -> Unit,
    private val saveCustomFields: (String, String, CustomEndpointMode) -> Boolean,
    private val validateEndpoint: (String, CustomEndpointMode) -> Unit,
    private val saveKey: (String) -> Unit,
    private val sourceChanged: () -> Unit, private val refreshReady: () -> Unit,
) {
    private val _source = MutableStateFlow(runCatching { TranscribeSource.valueOf(readString("source", "Doubao")!!) }.getOrDefault(TranscribeSource.Doubao))
    val source = _source.asStateFlow()
    private val _backend = MutableStateFlow(runCatching { app.resound.transcribe.local.LocalBackend.valueOf(readString("backend", "Npu")!!) }.getOrDefault(app.resound.transcribe.local.LocalBackend.Npu))
    val backend = _backend.asStateFlow()
    private val _language = MutableStateFlow(LocalAsrLanguage.fromCode(readString("localLanguage", null)))
    val language = _language.asStateFlow()
    fun setLanguage(value: LocalAsrLanguage) {
        if (value == _language.value) return
        check(saveString("localLanguage", value.code)) { "识别语言保存失败" }
        _language.value = value
        onChanged()
    }
    private val _localStatus = MutableStateFlow(readLocalString("actual", "尚未运行；首次转写会检查加速能力")!!)
    val localStatus = _localStatus.asStateFlow()
    private val _modelRevision = MutableStateFlow(0)
    val modelRevision = _modelRevision.asStateFlow()
    /** 记住的 NPU 检查结果：true 通过、false 没通过、null 还没查过（或版本、模型变了） */
    fun npuCheck(key: String): Boolean? = when (readLocalString("npuCheck", null)) {
        "pass:$key" -> true
        "fail:$key" -> false
        else -> null
    }
    fun recordNpuCheck(key: String, ok: Boolean) { saveLocalString("npuCheck", "${if (ok) "pass" else "fail"}:$key") }
    val customEndpoint get() = readString("endpoint", "")!!
    val customModel get() = readString("model", "")!!
    /** 自定义地址的写法：自动补路径（OpenAI 兼容）或按填写的原样请求（MiniMax 等）。 */
    private val _endpointMode = MutableStateFlow(runCatching { CustomEndpointMode.valueOf(readString("endpointMode", CustomEndpointMode.Auto.name)!!) }.getOrDefault(CustomEndpointMode.Auto))
    val endpointMode = _endpointMode.asStateFlow()
    val customEndpointMode get() = _endpointMode.value
    /** 完整地址模式下要不要同时发热词（prompt）：MiniMax 等不认这个参数，默认不发。 */
    private val _customPrompt = MutableStateFlow(readBoolean("customPrompt", false))
    val customPrompt = _customPrompt.asStateFlow()
    fun setCustomPrompt(on: Boolean) { check(saveBoolean("customPrompt", on)); _customPrompt.value = on; onChanged() }
    fun setLocalStatus(value: String) { _localStatus.value=value;saveLocalString("actual", value) }
    fun setSource(value: TranscribeSource) { saveString("source", value.name);_source.value=value
        if(!contains("realtime"))_realtime.value=value==TranscribeSource.Local
        sourceChanged();refreshReady();onChanged() }
    fun setBackend(value: app.resound.transcribe.local.LocalBackend) { saveString("backend", value.name);_backend.value=value;clearNpuCheck();setLocalStatus(if(value==app.resound.transcribe.local.LocalBackend.Npu) "已选择 NPU 识别" else "已选择 CPU 识别");refreshReady();onChanged() }
    fun saveCustom(endpoint: String, model: String, mode: CustomEndpointMode = _endpointMode.value) {
        validateEndpoint(endpoint, mode)
        require(model.trim().isNotEmpty()) { "请填写模型名" }
        saveCustomFields(endpoint.trim(), model.trim(), mode)
        _endpointMode.value=mode
        refreshReady(); onChanged()
    }
    fun saveCustomAll(endpoint: String, model: String, key: String, mode: CustomEndpointMode = _endpointMode.value) {
        validateEndpoint(endpoint, mode)
        require(model.trim().isNotEmpty()) { "请填写或选择模型" }
        if(key.isNotBlank()) saveKey(key)
        saveCustom(endpoint,model,mode)
    }
    private val _interval = MutableStateFlow(readInt("interval", 30).takeIf { it in MIN_INTERVAL..MAX_INTERVAL } ?: 30)
    val intervalMinutes = _interval.asStateFlow()
    /** 实时转写：录完一段就转。没手动设过时，本机默认开、云端默认关（云端每段都上传，更耗电和流量） */
    private val _realtime = MutableStateFlow(readBoolean("realtime", _source.value == TranscribeSource.Local))
    val realtime = _realtime.asStateFlow()
    fun setRealtime(on: Boolean) { check(saveBoolean("realtime", on)); _realtime.value = on; onChanged() }
    private val _wifi = MutableStateFlow(readBoolean("wifi", true))
    val wifiOnly = _wifi.asStateFlow()
    var onChanged: () -> Unit = {}
    fun setIntervalMinutes(minutes: Int) {
        require(minutes in MIN_INTERVAL..MAX_INTERVAL) { "间隔需在 $MIN_INTERVAL～$MAX_INTERVAL 分钟之间" }
        check(saveInt("interval", minutes))
        _interval.value = minutes; refreshReady(); onChanged()
    }
    fun setWifiOnly(value: Boolean) {
        check(saveBoolean("wifi", value))
        _wifi.value = value; refreshReady(); onChanged()
    }
    fun localPreferenceChanged() { _modelRevision.value++ }
}
