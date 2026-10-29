package app.resound.envsound

import android.content.Context
import app.resound.transcribe.local.*
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File

/** 只下载，不修改本地 ASR 运行库；所有文件校验成功才可加载。 */
class EnvSoundModel(private val context: Context) {
    private val dir = File(context.noBackupFilesDir, "env-sound/zipformer-small-2024-04-15")
    val state = MutableStateFlow<EnvModelState>(EnvModelState.NotDownloaded)
    private val lock = Mutex()
    private var verified = false
    suspend fun checkReady(): Boolean = withContext(Dispatchers.IO) {
        if (!verified) verified = files.all { (name, info) -> File(dir, name).let { it.isFile && it.length() == info.first && LocalModels.hash(it) == info.second } }
        if (verified) state.value = EnvModelState.Ready
        verified
    }
    suspend fun download() = lock.withLock {
        if(checkReady()) return@withLock
        state.value = EnvModelState.Downloading(0f)
        try {
            dir.mkdirs()
            try {
                files.entries.forEachIndexed { index, (name, info) ->
                    val spec = ModelSpec(name, "$mirror/$name", info.first, info.second, name, info.first, info.second, false)
                    val part = File(dir, "$name.part")
                    downloadModelFile(spec, part, status = {
                        val percent = Regex("下载 ([0-9]+)%").find(it)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
                        state.value = EnvModelState.Downloading((index + percent / 100) / files.size)
                    })
                    check(part.renameTo(File(dir, name)))
                }
            } catch(e: java.io.IOException) {
                val archive = File(dir, "upstream.part")
                val spec = ModelSpec("env-sound", upstream, 111034072, "07e2fafcdcbc461f2816188d9b0bbafced12584030cf67d5652e549ef256a2c6", "model.int8.onnx", 27038066, files.getValue("model.int8.onnx").second, false)
                downloadModelFile(spec, archive, status = {
                    val percent = Regex("下载 ([0-9]+)%").find(it)?.groupValues?.get(1)?.toFloatOrNull()
                    if(percent != null) state.value = EnvModelState.Downloading(percent/100)
                })
                TarArchiveInputStream(BZip2CompressorInputStream(archive.inputStream().buffered())).use { tar ->
                    while(true) {
                        currentCoroutineContext().ensureActive()
                        val entry = tar.nextTarEntry ?: break
                        val name = entry.name.substringAfterLast('/')
                        if(!entry.isFile || name !in files) continue
                        check(entry.size == files.getValue(name).first)
                        File(dir, name).outputStream().use { tar.copyTo(it) }
                    }
                }
                archive.delete()
            }
            check(checkReady()) { "模型校验失败" }
        } catch(e: CancellationException) { state.value = EnvModelState.NotDownloaded; throw e }
        catch(_: Exception) { state.value = EnvModelState.Failed("模型下载或校验失败，请重试") }
    }
    fun infer(pcm: ShortArray): Map<String, Float> {
        check(verified)
        val tagger = AudioTagging(config = AudioTaggingConfig(model = AudioTaggingModelConfig(
            zipformer = OfflineZipformerAudioTaggingModelConfig(File(dir,"model.int8.onnx").path), numThreads = 2),
            labels = File(dir,"class_labels_indices.csv").path, topK = 527))
        try {
            val scores = mutableMapOf<String,Float>()
            for(window in EnvSoundRules.windows(pcm.size)) {
                val stream = tagger.createStream()
                try {
                    val samples = FloatArray(maxOf(16000, window.count())) { i -> if(i < window.count()) pcm[window.first+i]/32768f else 0f }
                    stream.acceptWaveform(samples, 16000)
                    val events = tagger.compute(stream, 527)
                    check(events.size == 527 && events.all { it.prob.isFinite() && it.prob in 0f..1f })
                    events.forEach { scores[it.name] = maxOf(scores[it.name] ?: 0f, it.prob) }
                } finally { stream.release() }
            }
            return scores
        } finally { tagger.release() }
    }
    companion object {
        const val mirror = "https://modelscope.cn/models/OpenRecode/TaoSheng-models/resolve/master/audio-tagging/zipformer-small-2024-04-15"
        const val upstream = "https://github.com/k2-fsa/sherpa-onnx/releases/download/audio-tagging-models/sherpa-onnx-zipformer-small-audio-tagging-2024-04-15.tar.bz2"
        val files = linkedMapOf("model.int8.onnx" to (27038066L to "69304b8a1b96bbe6b7d16c24079f0732c65faf3568a14cb82a8238c709afe76c"),
            "class_labels_indices.csv" to (14675L to "cdd1049833c4b86127c2773ac0d14a2754b6a6d0d1798002ed5c66e699708429"))
    }
}
