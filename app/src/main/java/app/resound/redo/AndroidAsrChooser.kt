package app.resound.redo

import app.resound.digest.allowsEmptyKey
import android.content.Context
import app.resound.transcribe.*
import app.resound.digest.AndroidDigestSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal class AndroidAsrChooser(context: Context, private val settings: AndroidTranscribeSettings, scope: CoroutineScope,
    private val price: () -> Double?) : AsrChooser {
    private val prefs = context.getSharedPreferences("redo-choices", Context.MODE_PRIVATE)
    private val mutableOptions = MutableStateFlow(emptyList<AsrOption>())
    override val options = mutableOptions.asStateFlow()
    private val mutableChoice = MutableStateFlow(choice())
    override val lastChoice = mutableChoice.asStateFlow()
    private fun choice() = runCatching { TranscribeSource.valueOf(prefs.getString("asr", null)!!) }.getOrDefault(settings.source.value)
    fun refresh() {
        mutableOptions.value = TranscribeSource.entries.map { source ->
            val ready = settings.ready(source)
            AsrOption(source, source.title, ready, if (ready) null else if (source == TranscribeSource.Local) "模型未下载" else "服务未配置")
        }
        mutableChoice.value = choice()
    }
    init { refresh(); scope.launch { while (isActive) { delay(1000); refresh() } } }
    override fun remember(source: TranscribeSource) {
        check(prefs.edit().putString("asr", source.name).commit())
        mutableChoice.value = source
    }
    fun record(source: TranscribeSource?, duration: Long, elapsed: Long) {
        if ((source ?: settings.source.value) != TranscribeSource.Local || duration <= 0) return
        val ratio = elapsed.toDouble() / duration
        val old = prefs.getFloat("local-ratio", 0f)
        prefs.edit().putFloat("local-ratio", (if (old > 0) old * .7 + ratio * .3 else ratio).toFloat()).apply()
    }
    override fun estimate(durationMs: Long, source: TranscribeSource): AsrEstimate {
        val duration = durationMs.coerceAtLeast(0)
        val ratio = prefs.getFloat("local-ratio", 0f).toDouble().takeIf { it > 0 }
        val eta = if (duration == 0L) 0 else if (source == TranscribeSource.Local) ratio?.let { (duration * it).toLong() }
            else 3000 + duration / 5
        val cost = when (source) {
            TranscribeSource.Local -> 0.0
            TranscribeSource.Doubao -> price()?.let { duration / 3_600_000.0 * it }
            TranscribeSource.Custom -> null
        }
        return AsrEstimate(eta, cost)
    }
}

internal fun llmOptions(settings: AndroidDigestSettings) = settings.presets.map { preset ->
    val key = settings.skillCredentials(preset.id).second
    val ready = (key.isNotBlank() || preset.allowsEmptyKey()) && preset.model.isNotBlank() && OpenAiEndpoint.kind(preset.baseUrl) != EndpointKind.Invalid
    LlmOption(preset.id, preset.name, ready, note = if (ready) null else "未填密钥或服务未配置")
}
