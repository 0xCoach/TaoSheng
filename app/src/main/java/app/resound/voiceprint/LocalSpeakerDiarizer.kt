package app.resound.voiceprint

import android.content.Context
import app.resound.transcribe.local.LocalModels
import app.resound.transcribe.local.ModelVerification
import app.resound.audio.SileroVad
import com.k2fsa.sherpa.onnx.*
import java.io.File

/** 一次批处理会话内复用分段模型；调用者负责串行使用和关闭。 */
class LocalSpeakerDiarizer(private val context: Context) : AutoCloseable {
    private var model: OfflineSpeakerDiarization? = null
    private var stamp: Long = -1
    private var clusters = -1
    private var precheckEmbedding: CampPlusEmbedding? = null
    var lastDecision: String = "完整"; private set
    fun turns(pcm: ShortArray, speakerCount: Int = -1, progress: (Int,Int) -> Unit): List<SpeakerTurns.Turn> {
        lastDecision = "完整"
        val spec=LocalModels.speakers
        if(speakerCount != 2 && !LocalModels.speakersEnabled(context)) {
            lastDecision = "关闭"
            return SpeakerTurns.partition(emptyList(),pcm.size,Int.MAX_VALUE)
        }
        val dir=LocalModels.directory(context,spec)
        check(ModelVerification.ensure(dir,spec))
        if (speakerCount < 1 && SpeakerPrecheck.ENABLED && confidentSingle(pcm)) {
            lastDecision = "跳过"
            return listOf(SpeakerTurns.Turn(0, pcm.size, 0))
        }
        val segmentation=File(dir,spec.modelName)
        if(model==null || stamp!=segmentation.lastModified() || clusters != speakerCount) {
            model?.release(); model = null
            val embedding=File(context.cacheDir,"speaker-campplus.onnx")
            context.assets.open("voiceprint/campplus.onnx").use { input -> embedding.outputStream().use { input.copyTo(it) } }
            try {
                model=OfflineSpeakerDiarization(config=OfflineSpeakerDiarizationConfig(
                    // 分人只能跑在 CPU 上；按核数用一半（1～4 个），给录音和界面留余量
                    segmentation=OfflineSpeakerSegmentationModelConfig(pyannote=OfflineSpeakerSegmentationPyannoteModelConfig(model=segmentation.path),numThreads=VoiceprintThreads.count),
                    embedding=SpeakerEmbeddingExtractorConfig(model=embedding.path,numThreads=VoiceprintThreads.count),
                    clustering=FastClusteringConfig(numClusters=speakerCount,threshold=0.7f),minDurationOn=0.3f,minDurationOff=0.3f))
                stamp=segmentation.lastModified(); clusters=speakerCount
            } finally { embedding.delete() }
        }
        val result=model!!.processWithCallback(FloatArray(pcm.size){pcm[it]/32768f},object:(Int,Int,Long)->Int {
            // JNI 要求 invoke(IIJ)Integer，显式对象保留该桥接方法。
            override fun invoke(done:Int,total:Int,arg:Long):Int {progress(done,total);return 0}
        })
        val ordered=result.sortedBy{it.start}.map{it.speaker}.distinct()
        return SpeakerTurns.partition(result.map {
            SpeakerTurns.Turn((it.start*16000).toInt(),(it.end*16000).toInt(),ordered.indexOf(it.speaker))
        },pcm.size,Int.MAX_VALUE)
    }
    private fun confidentSingle(pcm: ShortArray): Boolean {
        if (SpeakerPrecheck.windows(pcm.size).isEmpty()) return false
        return try {
            var offset = 0
            val speech = mutableListOf<IntRange>()
            SileroVad(context).use { vad ->
                val consume: (ShortArray, Boolean, Float) -> Unit = { frame, voiced, _ ->
                    if (voiced) speech += offset until offset + frame.size
                    offset += frame.size
                }
                vad.accept(pcm, consume); vad.finish(consume)
            }
            SpeakerPrecheck.single(pcm, speech, SpeakerEmbedding { window ->
                val engine = precheckEmbedding ?: CampPlusEmbedding(context).also { precheckEmbedding = it }
                engine.compute(window)
            })
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // 预判不可用不阻断完整分人，也不能以“预判失败”作为单人的证据。
            runCatching { precheckEmbedding?.close() }; precheckEmbedding = null
            false
        }
    }
    override fun close() {model?.release();model=null;stamp=-1;precheckEmbedding?.close();precheckEmbedding=null}
    companion object {
        fun turns(context:Context,pcm:ShortArray,progress:(Int,Int)->Unit)=LocalSpeakerDiarizer(context).use{it.turns(pcm, progress = progress)}
    }
}
