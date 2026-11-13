package app.resound.transcribe.local

import com.k2fsa.sherpa.onnx.*
import java.io.File

internal fun localRecognizerKey(spec: ModelSpec, dir: File, language: LocalAsrLanguage) =
    "${spec.id}:${File(dir, spec.modelName).lastModified()}:${language.code}"

/** CPU 和 QNN 共用语言参数，不更换模型权重。 */
internal fun localRecognizerConfig(backend: LocalBackend, language: LocalAsrLanguage, spec: ModelSpec, dir: File, libs: String) =
    OfflineRecognizerConfig(modelConfig = OfflineModelConfig(
        provider = if (backend == LocalBackend.Npu) "qnn" else "cpu", numThreads = 2, debug = false,
        tokens = File(dir, "tokens.txt").path,
        senseVoice = OfflineSenseVoiceModelConfig(model = File(dir, spec.modelName).path, language = language.code,
            useInverseTextNormalization = true, qnnConfig = if (backend == LocalBackend.Npu)
                QnnConfig(backendLib = "$libs/libQnnHtp.so", systemLib = "$libs/libQnnSystem.so", contextBinary = File(dir, "model.bin").path)
            else QnnConfig())))
