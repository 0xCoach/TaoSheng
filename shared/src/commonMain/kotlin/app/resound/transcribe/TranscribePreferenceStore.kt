package app.resound.transcribe

import app.resound.diagnostics.FailureNotes

import app.resound.transcribe.local.LocalBackend

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
    private val sourceChanged: () -> Unit, private val refreshReady: () -> Unit,
    /** 从没存过转写方式时，是否已经填过豆包密钥（老用户一直在用豆包） */
    hasCloudKey: () -> Boolean = { false },
) {
    // 卡 223：没存过转写方式的默认用本机识别（不用密钥）；填过豆包密钥的老用户仍是豆包。第一次决定后存下
    private val _source = MutableStateFlow(if (!contains("source")) {
        (if (hasCloudKey()) TranscribeSource.Doubao else TranscribeSource.Local).also { saveString("source", it.name) }
    } else runCatching { TranscribeSource.valueOf(readString("source", "Local")!!) }.onFailure {
        FailureNotes.record("transcribe/TranscribePreferenceStore.kt:25", it)
    }.getOrDefault(TranscribeSource.Local))
    val source = _source.asStateFlow()
    private val _backend = MutableStateFlow(runCatching { LocalBackend.valueOf(readString("backend", "Npu")!!) }.onFailure {
        FailureNotes.record("transcribe/TranscribePreferenceStore.kt:27", it)
    }.getOrDefault(LocalBackend.Npu))
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
    fun setLocalStatus(value: String) { _localStatus.value=value;saveLocalString("actual", value) }
    fun setSource(value: TranscribeSource) { saveString("source", value.name);_source.value=value
        if(!contains("realtime"))_realtime.value=value==TranscribeSource.Local
        sourceChanged();refreshReady();onChanged() }
    fun setBackend(value: LocalBackend) { saveString("backend", value.name);_backend.value=value;clearNpuCheck();setLocalStatus(if(value==LocalBackend
        .Npu) "已选择 NPU 识别" else "已选择 CPU 识别");refreshReady();onChanged() }
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
