package app.resound.transcribe.local

import app.resound.diagnostics.FailureNotes

import app.resound.transcribe.TranscribePowerDiagnostics
import app.resound.transcribe.readPcm16
import app.resound.voiceprint.SpeakerTurns

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

/** 单消费者队列 + 会话模型；忙时排队，空闲解绑由客户端控制。 */
class LocalAsrService : Service() {
    private val processInstance = java.util.UUID.randomUUID().toString()
    @Volatile private var activeWake: Pair<String, InferenceWakeLock>? = null
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
            7->if(id in accepted){
                cancelled.add(id)
                // 客户端超时/取消后即释放；不等待可能阻塞的 JNI 返回。
                activeWake?.takeIf { it.first == id }?.second?.release()
            }
        }
        true
    })
    override fun onCreate(){
        super.onCreate()
        scope.launch {for(request in queue){
            try {if(request.id !in cancelled){
                val wake = InferenceWakeLock(getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "resound:local-asr")) { startedAt, millis ->
                    TranscribePowerDiagnostics.held(this@LocalAsrService, startedAt, millis)
                }
                activeWake = request.id to wake
                try {
                    if(request.id !in cancelled) wake.run(request.data.getLong("timeoutMillis", InferenceWakeLock.MAX_MILLIS)) { process(request) }
                } finally { wake.release(); activeWake = null }
            }}
            finally{cancelled.remove(request.id);accepted.remove(request.id);queued.decrementAndGet()}
        }}
    }
    private fun send(request:Request,type:Int,fields:Bundle.()->Unit={}): Boolean {
        return runCatching {request.reply.send(Message.obtain(null,type).apply{data=Bundle().apply{
            putString("id",request.id);putString("processInstance",processInstance);putInt("pid",Process.myPid());fields()
        }})}.onFailure {
            FailureNotes.record("transcribe/local/LocalAsrService.kt:69", it)
        }.isSuccess
    }
    private fun model(backend:LocalBackend,language:LocalAsrLanguage):OfflineRecognizer {
        require(backend!=LocalBackend.Npu || LocalModels.npuSupported())
        val spec=LocalModels.spec(backend);val dir=LocalModels.directory(this,spec)
        check(ModelVerification.ensure(dir,spec))
        val key=localRecognizerKey(spec,dir,language)
        if(recognizer!=null && key==recognizerKey)return recognizer!!
        recognizer?.release();recognizer=null;recognizerKey=""
        val libs=applicationInfo.nativeLibraryDir
        OfflineRecognizer.prependAdspLibraryPath(libs)
        recognizer=OfflineRecognizer(config=localRecognizerConfig(backend,language,spec,dir,libs))
        recognizerKey=key;loads++
        return recognizer!!
    }
    private suspend fun process(request:Request) {
        var phase="audio"
        var speakerDecision="未提供"
        val times=mutableMapOf<String,Long>()
        suspend fun <T> timed(name:String, block:suspend () -> T):T {
            val at=SystemClock.elapsedRealtime()
            try { return block() } finally { times[name]=(times[name] ?: 0L)+SystemClock.elapsedRealtime()-at }
        }
        fun Bundle.timingFields() { times.forEach { (name,value) -> putLong(name,value) }; putString("speakerDecision",speakerDecision) }
        send(request,6)
        try {
            val shared=request.data.getString("pcmPath")
            val pcm=timed("Prepare") { if(shared != null) readLocalAudio(shared, filesDir, cacheDir) { file ->
                require(file.canonicalPath.startsWith(cacheDir.canonicalPath + "/asr-shared-"))
                readPcm16(file)
            } else readLocalAudio(request.data.getString("path"), filesDir, cacheDir, AsrAudioReader(cacheDir)::readPcm) }
            // 音频已解码；后续进度文件 I/O 失败不能误判为录音损坏。
            phase="asr"
            val segment = requireNotNull(request.data.getString("segment"))
            val files = LocalResumeFiles(File(noBackupFilesDir, "asr-progress")) { reason ->
                send(request,8){putString("reason",reason);putString("segment",segment)}
            }
            val separateSpeakers=request.data.getBoolean("separateSpeakers",true)
            val backend=LocalBackend.valueOf(request.data.getString("backend")!!)
            val language=LocalAsrLanguage.fromCode(request.data.getString("language"))
            val spec=LocalModels.spec(backend)
            val speakerSpec=LocalModels.speakers
            val speakerFile=File(LocalModels.directory(this,speakerSpec),speakerSpec.modelName)
            val speakers=separateSpeakers && (request.data.getInt("speakerCount", -1) == 2 || LocalModels.speakersEnabled(this))
            val progressKey=LocalResumeKey(segment,pcm.size,LocalResumeFiles.pcmHash(pcm),
                backend.name + ":" + localRecognizerKey(spec,LocalModels.directory(this,spec),language),language.code,
                separateSpeakers,request.data.getInt("speakerCount",-1),request.data.getString("hotwords") ?: "",
                "${packageManager.getPackageInfo(packageName,0).longVersionCode}:${speakerFile.length()}:${speakerFile.lastModified()}",speakers)
            val saved=files.prepare(progressKey) {
                phase="speakers"
                val turns=if (!separateSpeakers) listOf(SpeakerTurns.Turn(0,pcm.size,null)) else timed("Diarize") {
                    diarizer.turns(pcm,request.data.getInt("speakerCount",-1)) { done,total ->
                        send(request,2){putInt("done",done);putInt("total",total);putString("phase","区分说话人")}
                    }
                }
                speakerDecision=if(separateSpeakers)diarizer.lastDecision else "关闭"
                phase="asr"
                LocalResumeProgress(progressKey,turns.map { ResumeTurn(it.start,it.end,it.speaker) },speakerDecision,
                    PauseChunks.split(pcm,turns).map { ResumeChunk(it.start,it.end,it.speaker,it.overlapSamples,it.groupStart) })
            }
            var progress=requireNotNull(saved)
            speakerDecision=progress.speakerDecision
            if(request.id in cancelled)return
            phase="asr"
            val chunks=progress.chunks.map { PauseChunks.Chunk(it.start,it.end,it.speaker,it.overlap,it.groupStart) }
            val sentences=TimedSentences()
            val detectedLanguages=linkedSetOf<String>()
            fun replay(value: ResumeDecoded, chunk: PauseChunks.Chunk) {
                detectedLanguages.add(value.language)
                sentences.append(OfflineRecognizerResult(value.text,value.tokens.toTypedArray(),
                    value.timestampBits.map { Float.fromBits(it) }.toFloatArray(),value.language,"","",floatArrayOf()),chunk,value.tag)
            }
            progress.decoded.forEachIndexed { index,value -> replay(value,chunks[index]) }
            send(request,8){putString("reason","resume-start");putString("segment",segment);putInt("total",chunks.size);putInt("done",progress.decoded.size)}
            val rec=if(progress.decoded.size<chunks.size) timed("ModelLoad") { model(backend,language) } else null
            timed("Recognize") {
            for(index in progress.decoded.size until chunks.size){
                val chunk=chunks[index]
                currentCoroutineContext().ensureActive()
                if(request.id in cancelled)return@timed
                val stream=requireNotNull(rec).createStream()
                try {
                    stream.acceptWaveform(FloatArray(chunk.end-chunk.start){pcm[chunk.start+it]/32768f},16000)
                    rec.decode(stream)
                    val decoded=rec.getResult(stream)
                    val value=ResumeDecoded(decoded.text,decoded.tokens.toList(),decoded.timestamps.map { it.toBits() },
                        decoded.lang,chunk.speaker?.toString() ?: if(speakers)"uncertain:${chunk.groupStart}" else null)
                    progress=progress.append(value)
                    files.save(progress)
                    replay(value,chunk)
                } finally{stream.release()}
                send(request,2){putInt("done",index+1);putInt("total",chunks.size)}
            }
            }
            if(request.id in cancelled)return
            val result=sentences.result()
            val delivered=send(request,3){
                timingFields()
                putStringArrayList("texts",ArrayList(result.map{it.text}));putStringArrayList("tags",ArrayList(result.map{it.speakerTag ?: ""}))
                putLongArray("starts",result.map{it.startMillis}.toLongArray());putLongArray("ends",result.map{it.endMillis}.toLongArray());putLong("duration",pcm.size/16L)
                putInt("pid",Process.myPid());putInt("loads",loads);putInt("timedChunks",sentences.timedChunks);putInt("chunks",chunks.size)
                putString("language",language.code);putStringArrayList("detectedLanguages",ArrayList(detectedLanguages))
            }
            if(delivered) files.deleteProgress(segment)
        } catch(e:CancellationException){throw e}
        catch(e:Exception){
            if(phase=="asr"){runCatching{
                // 仅释放/清除临时资源；失败不改变已确定的业务结果，可忽略。
                recognizer?.release()};recognizer=null;recognizerKey=""}
            send(request,4){LocalAsrFailure.write(this,phase,e);timingFields()}
        } catch(e:LinkageError){send(request,4){LocalAsrFailure.write(this,phase,e);timingFields()}}
    }
    override fun onBind(intent:Intent):IBinder=incoming.binder
    override fun onDestroy(){
        activeWake?.second?.release()
        queue.close();scope.cancel()
        // 进程退出统一回收原生资源，避免取消时与尚在执行的 JNI 并发释放。
        super.onDestroy();Process.killProcess(Process.myPid())
    }
}
