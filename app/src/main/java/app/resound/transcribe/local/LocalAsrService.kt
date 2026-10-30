package app.resound.transcribe.local

import android.app.Service
import android.content.Intent
import android.os.*
import app.resound.transcribe.AsrAudioReader
import app.resound.voiceprint.LocalSpeakerDiarizer
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** 单消费者队列 + 会话模型；忙时排队。客户端最后一个请求结束后保留绑定 60 秒。 */
class LocalAsrService : Service() {
    private data class Request(val id:String,val data:Bundle,val reply:Messenger)
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default.limitedParallelism(1))
    private val queue=Channel<Request>(Channel.UNLIMITED)
    private val cancelled=ConcurrentHashMap.newKeySet<String>()
    private val queued=AtomicInteger()
    private val accepted=ConcurrentHashMap.newKeySet<String>()
    private var recognizer:OfflineRecognizer?=null
    private var recognizerKey=""
    private var loads=0
    private val diarizer by lazy { LocalSpeakerDiarizer(this) }
    private val incoming=Messenger(Handler(Looper.getMainLooper()){msg->
        val id=msg.data.getString("id") ?: return@Handler true
        when(msg.what){
            1->{
                val reply=msg.replyTo ?: return@Handler true
                accepted.add(id)
                val position=queued.incrementAndGet()
                val request=Request(id,Bundle(msg.data),reply)
                if(queue.trySend(request).isFailure) {queued.decrementAndGet();accepted.remove(id);send(request,4){putString("phase","asr")}}
                else send(request,5){putInt("position",position)}
            }
            7->if(id in accepted)cancelled.add(id)
        }
        true
    })
    override fun onCreate(){
        super.onCreate()
        scope.launch {for(request in queue){
            try {if(request.id !in cancelled)process(request)}
            finally{cancelled.remove(request.id);accepted.remove(request.id);queued.decrementAndGet()}
        }}
    }
    private fun send(request:Request,type:Int,fields:Bundle.()->Unit={}) {
        runCatching {request.reply.send(Message.obtain(null,type).apply{data=Bundle().apply{putString("id",request.id);fields()}})}
    }
    private fun model(backend:LocalBackend):OfflineRecognizer {
        require(backend!=LocalBackend.Npu || LocalModels.npuSupported())
        val spec=LocalModels.spec(backend);val dir=LocalModels.directory(this,spec)
        check(ModelVerification.ensure(dir,spec))
        val key="${spec.id}:${File(dir,spec.modelName).lastModified()}"
        if(recognizer!=null && key==recognizerKey)return recognizer!!
        recognizer?.release();recognizer=null;recognizerKey=""
        val libs=applicationInfo.nativeLibraryDir
        OfflineRecognizer.prependAdspLibraryPath(libs)
        recognizer=OfflineRecognizer(config=OfflineRecognizerConfig(modelConfig=OfflineModelConfig(
            provider=if(backend==LocalBackend.Npu)"qnn" else "cpu",numThreads=2,debug=false,tokens=File(dir,"tokens.txt").path,
            senseVoice=OfflineSenseVoiceModelConfig(model=File(dir,spec.modelName).path,language="auto",useInverseTextNormalization=true,
                qnnConfig=if(backend==LocalBackend.Npu)QnnConfig(backendLib="$libs/libQnnHtp.so",systemLib="$libs/libQnnSystem.so",contextBinary=File(dir,"model.bin").path) else QnnConfig()))))
        recognizerKey=key;loads++
        return recognizer!!
    }
    private suspend fun process(request:Request) {
        var phase="audio"
        send(request,6)
        try {
            val shared=request.data.getString("pcmPath")
            val pcm=if(shared != null) readLocalAudio(shared, filesDir, cacheDir) { file ->
                require(file.canonicalPath.startsWith(cacheDir.canonicalPath + "/asr-shared-"))
                app.resound.transcribe.readPcm16(file)
            } else readLocalAudio(request.data.getString("path"), filesDir, cacheDir, AsrAudioReader(cacheDir)::readPcm)
            phase="speakers"
            val separateSpeakers=request.data.getBoolean("separateSpeakers",true)
            val turns=if (!separateSpeakers) listOf(app.resound.voiceprint.SpeakerTurns.Turn(0,pcm.size,null)) else diarizer.turns(pcm){done,total->send(request,2){putInt("done",done);putInt("total",total);putString("phase","区分说话人")}}
            if(request.id in cancelled)return
            phase="asr"
            val backend=LocalBackend.valueOf(request.data.getString("backend")!!)
            val rec=model(backend)
            val chunks=PauseChunks.split(pcm,turns)
            val sentences=TimedSentences()
            val speakers=separateSpeakers && LocalModels.speakersEnabled(this)
            for((index,chunk) in chunks.withIndex()){
                currentCoroutineContext().ensureActive()
                if(request.id in cancelled)return
                val stream=rec.createStream()
                try {
                    stream.acceptWaveform(FloatArray(chunk.end-chunk.start){pcm[chunk.start+it]/32768f},16000)
                    rec.decode(stream)
                    sentences.append(rec.getResult(stream),chunk,chunk.speaker?.toString() ?: if(speakers)"uncertain:${chunk.groupStart}" else null)
                } finally{stream.release()}
                send(request,2){putInt("done",index+1);putInt("total",chunks.size)}
            }
            val result=sentences.result()
            send(request,3){
                putStringArrayList("texts",ArrayList(result.map{it.text}));putStringArrayList("tags",ArrayList(result.map{it.speakerTag ?: ""}))
                putLongArray("starts",result.map{it.startMillis}.toLongArray());putLongArray("ends",result.map{it.endMillis}.toLongArray());putLong("duration",pcm.size/16L)
                putInt("pid",Process.myPid());putInt("loads",loads);putInt("timedChunks",sentences.timedChunks);putInt("chunks",chunks.size)
            }
        } catch(e:CancellationException){throw e}
        catch(e:Exception){
            if(phase=="asr"){runCatching{recognizer?.release()};recognizer=null;recognizerKey=""}
            send(request,4){LocalAsrFailure.write(this,phase,e)}
        } catch(e:LinkageError){send(request,4){LocalAsrFailure.write(this,phase,e)}}
    }
    override fun onBind(intent:Intent):IBinder=incoming.binder
    override fun onDestroy(){
        queue.close();scope.cancel()
        // 进程退出统一回收原生资源，避免取消时与尚在执行的 JNI 并发释放。
        super.onDestroy();Process.killProcess(Process.myPid())
    }
}
