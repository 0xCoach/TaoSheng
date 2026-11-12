package app.resound.net

import android.app.Application
import android.content.Context
import app.resound.digest.*
import app.resound.transcribe.*
import app.resound.usage.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.mockwebserver.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/** 只用 loopback 和合成凭据；Robolectric 的 Android JSON 顺序与手机一致。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class)
class KtorParity151Test {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val headers = listOf(HeaderEntry("x-test","one"),HeaderEntry("X-Test","two"),HeaderEntry("Authorization","custom"))
    private fun preset(s: MockWebServer) = LlmPreset("test","test",s.url("/proxy/v1").toString(),"test")
    private fun http() = OkHttpClient.Builder().build()
    private fun same(a: RecordedRequest, b: RecordedRequest, multipart: Boolean = false, ws: Boolean = false) {
        assertEquals(a.method,b.method); assertEquals(a.path,b.path)
        // UUID、WebSocket nonce 是随机输入；两条真实链路的值本来就不同。
        val excluded = if(ws) setOf("sec-websocket-key","x-api-connect-id") else emptySet()
        fun normalized(r: RecordedRequest) = r.headers.names().filterNot { it.lowercase() in excluded }.associate { name ->
            name.lowercase() to r.headers.values(name).map { if(multipart && name.equals("content-type",true)) it.substringBefore("boundary=") else it }
        }
        assertEquals(normalized(a),normalized(b))
        if(multipart) {
            val ba = a.getHeader("Content-Type")!!.substringAfter("boundary=")
            val bb = b.getHeader("Content-Type")!!.substringAfter("boundary=")
            assertArrayEquals(a.body.readByteArray().decodeToString().replace(ba,"BOUNDARY").encodeToByteArray(),b.body.readByteArray().decodeToString().replace(bb,"BOUNDARY").encodeToByteArray())
        } else assertArrayEquals(a.body.readByteArray(),b.body.readByteArray())
    }
    private suspend fun chatPair(status: Int, body: String, extra: List<HeaderEntry> = emptyList()): Pair<Result<LlmReply>,Result<LlmReply>> = MockWebServer().use { s ->
        repeat(2) { s.enqueue(MockResponse().setResponseCode(status).setBody(body)) }
        val p = preset(s)
        val old = runCatching { LegacyLlmClient(requestHeaders={extra}).chatJson(p,"fake","系统/指令","用户") }
        val new = runCatching { LlmClient(requestHeaders={extra}).chatJson(p,"fake","系统/指令","用户") }
        same(s.takeRequest(),s.takeRequest()); old to new
    }
    private fun sameError(pair: Pair<Result<LlmReply>,Result<LlmReply>>) {
        val a = pair.first.exceptionOrNull() as LlmException; val b = pair.second.exceptionOrNull() as LlmException
        assertEquals(a.type,b.type); assertEquals(a.status,b.status); assertEquals(a.message,b.message); assertEquals(a.usage,b.usage)
    }
    @Test fun chatContentUsageAndHeaders() = runBlocking {
        val r=chatPair(200,"""{"choices":[{"message":{"content":"回答"},"finish_reason":"stop"}],"usage":{"prompt_tokens":9,"completion_tokens":3}}""",headers)
        assertEquals(r.first.getOrThrow(),r.second.getOrThrow())
    }
    @Test fun chatAuthQuotaRateAndServer() = runBlocking {
        for(code in listOf(401,402,403,429,500)) sameError(chatPair(code,"""{"usage":{"prompt_tokens":2,"completion_tokens":1}}"""))
    }
    @Test fun chatRejectionAndInvalidJson() = runBlocking {
        sameError(chatPair(400,"context length exceeded")); sameError(chatPair(200,"not json"))
    }
    @Test fun chatEmptyReplySemantics() = runBlocking {
        val r=chatPair(200,"""{"choices":[{"message":{"content":""}}]}"""); assertEquals(r.first.getOrThrow(),r.second.getOrThrow())
        sameError(chatPair(200,"{}"))
    }
    @Test fun fallbackPathsAndRedirectRejection() = runBlocking {
        MockWebServer().use { s ->
            repeat(2) { s.enqueue(MockResponse().setResponseCode(404)); s.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"ok"}}]}""")) }
            val p=preset(s).copy(baseUrl=s.url("/proxy").toString())
            assertEquals(LegacyLlmClient().chatJson(p,"","s","u"),LlmClient().chatJson(p,"","s","u"))
            val old=listOf(s.takeRequest(),s.takeRequest()); val new=listOf(s.takeRequest(),s.takeRequest()); old.zip(new).forEach { same(it.first,it.second) }
        }
        sameError(chatPair(302,""))
    }
    @Test fun callTimeoutClassification() = runBlocking {
        MockWebServer().use { s ->
            repeat(2) { s.enqueue(MockResponse().setBody("{}").setBodyDelay(1,TimeUnit.SECONDS)) }
            val h=OkHttpClient.Builder().callTimeout(200,TimeUnit.MILLISECONDS).build(); val p=preset(s)
            sameError(runCatching { LegacyLlmClient(h).chatJson(p,"","s","u") } to runCatching { LlmClient(h).chatJson(p,"","s","u") })
        }
    }
    private val stream = "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"思考\",\"content\":\"你\"}}]}\r\n\r\n" +
        "data: {\"choices\":[{\"delta\":{\"content\":\"好\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2}}\n\n" + "data: [DONE]\n\n"
    @Test fun streamFragmentsReasoningAndCallbackOrder() = runBlocking {
        MockWebServer().use { s ->
            repeat(2) { s.enqueue(MockResponse().setBody(stream).throttleBody(13,1,TimeUnit.MILLISECONDS)) }
            val a=mutableListOf<String>();val b=mutableListOf<String>();val p=preset(s)
            val old=LegacyLlmClient().streamChat(p,"",JSONArray(),JSONArray(),{a.add(it)},{x,y->a.add("$x/$y")})
            val new=LlmClient().streamChat(p,"",JSONArray(),JSONArray(),{b.add(it)},{x,y->b.add("$x/$y")})
            assertEquals(old,new);assertEquals(a,b);same(s.takeRequest(),s.takeRequest())
        }
    }
    @Test fun streamInterruptedAndTruncated() = runBlocking {
        for(body in listOf(stream.substringBefore("data: [DONE]"),stream.replace("stop","length"))) MockWebServer().use { s ->
            repeat(2){s.enqueue(MockResponse().setBody(body))}; val p=preset(s)
            val a=runCatching { LegacyLlmClient().streamChat(p,"",JSONArray(),JSONArray(),{},{_,_->}) }.exceptionOrNull() as LlmException
            val b=runCatching { LlmClient().streamChat(p,"",JSONArray(),JSONArray(),{},{_,_->}) }.exceptionOrNull() as LlmException
            assertEquals(a.type,b.type);assertEquals(a.message,b.message);assertEquals(a.truncated,b.truncated)
        }
    }
    @Test fun cancellationStopsBodyAndCallbacks() = runBlocking {
        for(legacy in listOf(true,false)) MockWebServer().use { s ->
            s.enqueue(MockResponse().setBody(stream.substringBefore("data: [DONE]") + ": keepalive\n\n".repeat(2000)).throttleBody(64,100,TimeUnit.MILLISECONDS))
            var callbacks=0
            val first = CompletableDeferred<Unit>()
            val p = preset(s)
            val job=launch(start=CoroutineStart.UNDISPATCHED) {
                val onText: (String) -> Unit = { callbacks++; first.complete(Unit) }
                if(legacy) LegacyLlmClient().streamChat(p,"",JSONArray(),JSONArray(),onText,{_,_->})
                else LlmClient().streamChat(p,"",JSONArray(),JSONArray(),onText,{_,_->})
            }
            try {
                withTimeout(3000) { first.await() }
                withTimeout(2000){job.cancelAndJoin()};val n=callbacks;delay(30);assertEquals(n,callbacks)
            } finally { job.cancelAndJoin() }

        }
    }
    @Test fun balanceNumbersAndErrors() {
        val body="""{"is_available":false,"balance_infos":[{"currency":"CNY","total_balance":"2.5","granted_balance":"0","topped_up_balance":"2.5"}]}"""
        val a=LlmBalanceChecker.parse(body,1);val b=BalanceRules.parse(body)
        assertEquals(a.total,b.total,0.0);assertEquals(a.available,b.available);assertEquals(a.granted,b.granted)
        for(path in listOf("rows.0.n","rows.4.n")) {
            val data="""{"rows":[{"n":"5.25"}]}"""
            val x=runCatching{LlmBalanceChecker.pick(data,path)};val y=runCatching{BalanceRules.pick(data,path)}
            assertEquals(x.getOrNull(),y.getOrNull());assertEquals(x.exceptionOrNull()?.message,y.exceptionOrNull()?.message)
        }
    }
    @Test fun balanceWireAndLimit() = runBlocking {
        val store=ServiceHeaders({""},{_,_->},{})
        val keys=object:ProviderKeys {override fun has(id:String)=false;override fun read(id:String)="";override fun write(id:String,key:String){};override fun remove(id:String){}}
        val providers=AndroidLlmProviders(context,LlmClient(),keys,headerStore=store)
        val checker=LlmBalanceChecker(context.getSharedPreferences("balance151",0),providers,{_,_->},now={1})
        MockWebServer().use { s ->
            repeat(2){s.enqueue(MockResponse().setBody("{\"n\":4}"))};val src=BalanceSource(s.url("/balance").toString(),"fake","n",headers)
            assertEquals(checker.legacyFetch(src),checker.fetch(src));same(s.takeRequest(),s.takeRequest())
            repeat(2){s.enqueue(MockResponse().setBody(" ".repeat(65537)))}
            assertEquals(runCatching{checker.legacyFetch(src)}.exceptionOrNull()?.message,runCatching{checker.fetch(src)}.exceptionOrNull()?.message)
        }
    }
    @Test fun modelsFallbackSortingAndHeaders() = runBlocking {
        MockWebServer().use { s ->
            repeat(2){s.enqueue(MockResponse().setResponseCode(405));s.enqueue(MockResponse().setBody("""{"data":[{"id":"z"},{"id":"a"},{"id":"z"}]}"""))}
            val url=s.url("/").toString()
            assertEquals(legacyFetchRemoteModels(url,"fake",headers=headers),fetchRemoteModels(url,"fake",headers=headers))
            val old=listOf(s.takeRequest(),s.takeRequest());val new=listOf(s.takeRequest(),s.takeRequest());old.zip(new).forEach{same(it.first,it.second)}
        }
    }
    @Test fun modelsAuthAndEmptyList() = runBlocking {
        for((status,body) in listOf(403 to "{}",200 to "{\"data\":[]}")) MockWebServer().use { s ->
            repeat(2){s.enqueue(MockResponse().setResponseCode(status).setBody(body))};val url=s.url("/v1").toString()
            assertEquals(runCatching{legacyFetchRemoteModels(url,"")}.exceptionOrNull()?.message,runCatching{fetchRemoteModels(url,"")}.exceptionOrNull()?.message)
        }
    }
    private fun audio()=AsrAudio(java.io.File.createTempFile("net151",".ogg",context.cacheDir).apply{writeBytes(byteArrayOf(1,2,3))},"ogg",1200)
    @Test fun multipartBytesPromptAndResult() = runBlocking {
        MockWebServer().use { s ->
            repeat(2){s.enqueue(MockResponse().setBody("{\"text\":\" 你好 \"}"))}
            val settings=AndroidTranscribeSettings(context);settings.saveCustomAll(s.url("/speech?x=1").toString(),"asr","",CustomEndpointMode.Full);settings.setCustomPrompt(true)
            val audio=audio()
            try {
                var count=0
                val a=LegacyCustomAsrProvider(settings){listOf("涛声")}.recognize(audio,""){count++}
                val b=CustomAsrProvider(settings){listOf("涛声")}.recognize(audio,""){count++}
                assertEquals(a,b);assertEquals(2,count);same(s.takeRequest(),s.takeRequest(),multipart=true)
            } finally { audio.file.delete() }
        }
    }
    @Test fun transcriptionErrorsAndEmpty() {
        for(body in listOf("{\"text\":\"\"}","{\"base_resp\":{\"status_code\":1004}}","{\"base_resp\":{\"status_code\":9}}")) {
            val a=runCatching{transcriptionText(JSONObject(body))};val b=runCatching{customTranscriptionText(body)}
            assertEquals(a.getOrNull(),b.getOrNull());assertEquals(a.exceptionOrNull()?.message,b.exceptionOrNull()?.message)
        }
    }
    @Test fun doubaoConfigAndAudioFrameBytes() {
        for(format in listOf("pcm","ogg")) {
            assertArrayEquals(DoubaoProtocol.config(format,listOf("涛声")),NetDoubaoProtocol.config(format,listOf("涛声")))
            for(flag in listOf(0,2)) assertArrayEquals(DoubaoProtocol.frame(2,flag,byteArrayOf(1,2,3)),NetDoubaoProtocol.frame(2,flag,byteArrayOf(1,2,3)))
        }
    }
    private suspend fun doubaoPair(error: Boolean, disconnect: Boolean = false): Pair<Result<AsrResult>,Result<AsrResult>> = MockWebServer().use { s ->
        val frames=mutableListOf<List<ByteArray>>()
        repeat(2) {
            val received=mutableListOf<ByteArray>();frames.add(received)
            s.enqueue(MockResponse().withWebSocketUpgrade(object:WebSocketListener(){
                override fun onMessage(ws:WebSocket,b:ByteString){
                    received.add(b.toByteArray())
                    if(b[1].toInt()==0x22) {
                        if(disconnect) { ws.cancel(); return }
                        val packet = if(error) {
                            val frame = DoubaoProtocol.frame(15,0,"{}".toByteArray(),true)
                            frame.copyOfRange(0,4) + java.nio.ByteBuffer.allocate(4).putInt(45000030).array() + frame.copyOfRange(4,frame.size)
                        } else DoubaoProtocol.frame(9,2,"{\"result\":{\"utterances\":[{\"text\":\"你好\",\"start_time\":0,\"end_time\":1000}]}}".toByteArray(),true)
                        ws.send(packet.toByteString())
                    }
                }
            }).addHeader("X-Tt-Logid","safe-id"))
        }
        val audio=audio().copy(format="pcm").also { it.file.writeBytes(ByteArray(6400)) };val cfg=DoubaoConfig(s.url("/asr").toString().replace("http:","ws:"),"resource",3000)
        try {
            val a=runCatching{LegacyDoubaoAsrProvider(AsrAudioReader(context.cacheDir),cfg).recognize(audio,"fake") {}}
            val b=runCatching{DoubaoAsrProvider(AsrAudioReader(context.cacheDir),cfg).recognize(audio,"fake") {}}
            same(s.takeRequest(),s.takeRequest(),ws=true)
            assertEquals(frames[0].size,frames[1].size);frames[0].zip(frames[1]).forEach{assertArrayEquals(it.first,it.second)}
            a to b
        } finally { audio.file.delete() }
    }
    @Test fun doubaoHandshakeFramesAndResult()=runBlocking{val r=doubaoPair(false);assertEquals(r.first.getOrThrow(),r.second.getOrThrow())}
    @Test fun doubaoServerError()=runBlocking{val r=doubaoPair(true);assertEquals((r.first.exceptionOrNull() as AsrException).type,(r.second.exceptionOrNull() as AsrException).type)}
    @Test fun doubaoConnectionLossStaysNetwork()=runBlocking {
        val r=doubaoPair(false,disconnect=true)
        assertEquals(AsrError.Network,(r.first.exceptionOrNull() as AsrException).type)
        assertEquals(AsrError.Network,(r.second.exceptionOrNull() as AsrException).type)
    }
    @Test fun headersRejectInvalidInputsWithoutEcho() {
        for(h in listOf(HeaderEntry("Host","secret"),HeaderEntry("x","secret\n"))) {
            val old=runCatching{validateHeaders(listOf(h))};val new=runCatching{validateNetworkHeaders(listOf(h))}
            assertEquals(old.exceptionOrNull()?.message,new.exceptionOrNull()?.message)
        }
    }
}
