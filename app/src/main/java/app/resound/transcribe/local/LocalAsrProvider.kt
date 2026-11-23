package app.resound.transcribe.local

import android.content.Context
import app.resound.PipelineProgress
import app.resound.diagnostics.FailureNotes
import app.resound.transcribe.AndroidTranscribeSettings
import app.resound.transcribe.AsrAudio
import app.resound.transcribe.AsrError
import app.resound.transcribe.AsrException
import app.resound.transcribe.AsrProvider
import app.resound.transcribe.AsrResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

class LocalAsrProvider(
    private val context: Context,
    private val settings: AndroidTranscribeSettings,
    private val hotwords: () -> List<String> = { emptyList() },
) : AsrProvider {
    private val correction by lazy { LocalHotwordCorrection(context, hotwords) }

    override suspend fun recognize(audio: AsrAudio, key: String, beforeAudio: suspend () -> Unit): AsrResult {
        val checkKey = LocalModels.npuCheckKey(context)
        val facts = settings.localFacts(checkKey)
        val language = settings.language.value
        val request = Any()
        val showStatus: (String) -> Unit = { settings.setLocalStatus(request, it) }
        try {
            when (LocalEngine.choose(facts)) {
                null -> throw AsrException(AsrError.ModelMissing, detail = LocalEngine.status(facts))
                LocalBackend.Cpu -> {
                    showStatus(LocalEngine.status(facts))
                    return call(audio, LocalBackend.Cpu, language, showStatus)
                }
                LocalBackend.Npu -> {
                    showStatus(if (facts.npuCheck == true) "正在使用：NPU 识别" else "正在试用 NPU 识别…")
                    val result = try {
                        call(audio, LocalBackend.Npu, language, showStatus)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (e is AsrException && (e.type == AsrError.Speakers || e.type == AsrError.Audio ||
                                e.type == AsrError.Interrupted || e.detail in LOCAL_INTERRUPTION_REASONS)) throw e
                        FailureNotes.record("transcribe/local/LocalAsrProvider.kt:npu ${LocalModels.soc()} ${LocalModels.npu.id}", e)
                        // 已通过检查的 NPU 偶发失败只影响本次请求。
                        if (facts.npuCheck != true) settings.recordNpuCheck(checkKey, false)
                        if (!settings.modelReady(LocalBackend.Cpu)) {
                            throw AsrException(AsrError.ModelMissing, detail = "NPU 识别这次没成功，请先下载 CPU 模型")
                        }
                        showStatus("NPU 这次没成功，已改用 CPU")
                        return call(audio, LocalBackend.Cpu, language, showStatus)
                    }
                    if (facts.npuCheck != true) settings.recordNpuCheck(checkKey, true)
                    return result
                }
            }
        } finally {
            settings.clearLocalStatus(request)
        }
    }

    private suspend fun call(audio: AsrAudio, backend: LocalBackend, language: LocalAsrLanguage, showStatus: (String) -> Unit): AsrResult =
        withContext(LocalResumeRequest(LocalResumeFiles.hash(hotwords().joinToString("\u0000").toByteArray()))) {
            correction.apply(LocalAsrConnection.get(context).recognize(audio, backend, language) {
                showStatus(it)
                PipelineProgress.detail(it)
            })
        }
}
