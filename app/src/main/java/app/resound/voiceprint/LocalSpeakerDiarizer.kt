package app.resound.voiceprint

import android.content.Context
import app.resound.transcribe.local.LocalModels
import app.resound.transcribe.local.ModelVerification
import com.k2fsa.sherpa.onnx.*
import java.io.File

/** 一次批处理会话内复用分段模型；调用者负责串行使用和关闭。 */
class LocalSpeakerDiarizer(private val context: Context) : AutoCloseable {
    private var model: OfflineSpeakerDiarization? = null
    private var stamp: Long = -1
    fun turns(pcm: ShortArray, progress: (Int,Int) -> Unit): List<SpeakerTurns.Turn> {
        val spec=LocalModels.speakers
        if(!LocalModels.speakersEnabled(context))return SpeakerTurns.partition(emptyList(),pcm.size,Int.MAX_VALUE)
        val dir=LocalModels.directory(context,spec)
        check(ModelVerification.ensure(dir,spec))
        val segmentation=File(dir,spec.modelName)
        if(model==null || stamp!=segmentation.lastModified()) {
            close()
            val embedding=File(context.cacheDir,"speaker-campplus.onnx")
            context.assets.open("voiceprint/campplus.onnx").use { input -> embedding.outputStream().use { input.copyTo(it) } }
            try {
                model=OfflineSpeakerDiarization(config=OfflineSpeakerDiarizationConfig(
                    // 分人只能跑在 CPU 上；手机 8 核，用 4 个线程，一直有人说话时积压的长录音能快些分完
                    segmentation=OfflineSpeakerSegmentationModelConfig(pyannote=OfflineSpeakerSegmentationPyannoteModelConfig(model=segmentation.path),numThreads=DIARIZE_THREADS),
                    embedding=SpeakerEmbeddingExtractorConfig(model=embedding.path,numThreads=DIARIZE_THREADS),
                    clustering=FastClusteringConfig(numClusters=-1,threshold=0.7f),minDurationOn=0.3f,minDurationOff=0.3f))
                stamp=segmentation.lastModified()
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
    override fun close() {model?.release();model=null;stamp=-1}
    companion object {
        private const val DIARIZE_THREADS = 4
        fun turns(context:Context,pcm:ShortArray,progress:(Int,Int)->Unit)=LocalSpeakerDiarizer(context).use{it.turns(pcm,progress)}
    }
}
