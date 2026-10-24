package app.resound.transcribe.local

import android.content.*
import android.os.*
import app.resound.transcribe.*
import kotlinx.coroutines.*
import java.util.UUID

/** 应用进程持有一个共享连接，最后一个请求结束后空闲 60 秒释放绑定。 */
internal class LocalAsrConnection(private val context:Context) {
    data class Metrics(val pid:Int,val modelLoads:Int,val timedChunks:Int,val chunks:Int)
    private data class Pending(val started:CompletableDeferred<Unit>,val result:CompletableDeferred<AsrResult>,val progress:(String)->Unit)
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val pending=linkedMapOf<String,Pending>()
    private var idle:Job?=null
    private var bound=false
    private var connection:ServiceConnection?=null
    private var binding:CompletableDeferred<Messenger>?=null
    private var death=CompletableDeferred<Unit>()
    private var closing:CompletableDeferred<Unit>?=null
    var lastMetrics:Metrics?=null;private set
    private val receiver=Messenger(Handler(Looper.getMainLooper()){message->
        val data=message.data
        val request=pending[data.getString("id")] ?: return@Handler true
        when(message.what){
            2->request.progress("${data.getString("phase") ?: "本地转写"} · ${data.getInt("done")}/${data.getInt("total")}")
            5->if(data.getInt("position")>1)request.progress("等待前面的本地转写 · 队列 ${data.getInt("position")}")
            6->request.started.complete(Unit)
            3->{
                runCatching {
                    val texts=data.getStringArrayList("texts").orEmpty();val starts=requireNotNull(data.getLongArray("starts"));val ends=requireNotNull(data.getLongArray("ends"));val tags=data.getStringArrayList("tags")
                    check(texts.size==starts.size && starts.size==ends.size)
                    val result=AsrResult(texts.mapIndexed{i,text->AsrSentence(starts[i],ends[i],text,tags?.getOrNull(i)?.takeIf{it.isNotBlank()})},data.getLong("duration"),null)
                    lastMetrics=Metrics(data.getInt("pid"),data.getInt("loads"),data.getInt("timedChunks"),data.getInt("chunks"))
                    request.result.complete(result)
                }.onFailure{request.result.completeExceptionally(AsrException(AsrError.Protocol))}
                request.started.complete(Unit)
            }
            4->{request.started.complete(Unit);request.result.completeExceptionally(AsrException(if(data.getString("phase")=="speakers")AsrError.Speakers else AsrError.LocalEngine))}
        }
        true
    })
    private fun broken() {
        death.complete(Unit)
        binding?.completeExceptionally(AsrException(AsrError.LocalEngine))
        for(request in pending.values){request.started.complete(Unit);request.result.completeExceptionally(AsrException(AsrError.LocalEngine))}
        disconnect()
    }
    private fun disconnect() {
        idle?.cancel();idle=null
        if(bound){closing=death;runCatching{context.unbindService(connection!!)}}
        bound=false;connection=null;binding=null
    }
    private suspend fun connect():Messenger {
        binding?.let{return awaitBinding(it)}
        closing?.let{withTimeoutOrNull(3000){it.await()};closing=null}
        binding?.let{return awaitBinding(it)}
        val ready=CompletableDeferred<Messenger>();binding=ready;death=CompletableDeferred()
        val generation=death
        val conn=object:ServiceConnection {
            override fun onServiceConnected(name:ComponentName,binder:IBinder){
                if(binding!==ready)return
                try {binder.linkToDeath({scope.launch{generation.complete(Unit);if(binding===ready)broken()}},0);ready.complete(Messenger(binder))}
                catch(_:Exception){broken()}
            }
            override fun onServiceDisconnected(name:ComponentName){if(binding===ready)broken()}
            override fun onBindingDied(name:ComponentName){if(binding===ready)broken()}
            override fun onNullBinding(name:ComponentName){if(binding===ready)broken()}
        }
        connection=conn
        bound=context.bindService(Intent(context,LocalAsrService::class.java),conn,Context.BIND_AUTO_CREATE)
        if(!bound)broken()
        return awaitBinding(ready)
    }
    private suspend fun awaitBinding(ready:CompletableDeferred<Messenger>):Messenger = try {
        withTimeout(15_000){ready.await()}
    } catch(e:TimeoutCancellationException) {
        // 绑定超时也属于引擎失败，清掉旧连接后 CPU 才能重新绑定。
        if(binding===ready)broken()
        throw AsrException(AsrError.LocalEngine)
    }
    suspend fun recognize(audio:AsrAudio,backend:LocalBackend,progress:(String)->Unit):AsrResult=withContext(Dispatchers.Main.immediate){
        idle?.cancel();idle=null
        val id=UUID.randomUUID().toString()
        val request=Pending(CompletableDeferred(),CompletableDeferred(),progress)
        pending[id]=request
        var messenger:Messenger?=null
        try {
            messenger=connect()
            messenger.send(Message.obtain(null,1).apply{replyTo=receiver;data=Bundle().apply{putString("id",id);putString("path",audio.file.path);putString("backend",backend.name)}})
            // 排队期间不消耗模型执行超时，调用方取消仍会立即生效。
            request.started.await()
            withTimeout(maxOf(300_000L,60_000L+audio.durationMillis*2)){request.result.await()}
        } finally {
            if(!request.result.isCompleted)runCatching{messenger?.send(Message.obtain(null,7).apply{data=Bundle().apply{putString("id",id)}})}
            pending.remove(id)
            if(pending.isEmpty())idle=scope.launch{delay(60_000);if(pending.isEmpty())disconnect()}
        }
    }
    companion object {
        private val instances=mutableMapOf<String,LocalAsrConnection>()
        @Synchronized fun get(context:Context)=instances.getOrPut(context.packageName){LocalAsrConnection(context.applicationContext)}
    }
}
