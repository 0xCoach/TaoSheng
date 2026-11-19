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
    // 固定 wire 契约：不再依赖另一份生产实现作为测试预言机。
    private fun request(r: RecordedRequest, method: String, path: String, auth: String? = null, custom: Boolean = false) {
        assertEquals(method, r.method); assertEquals(path, r.path)
        assertEquals(auth, r.getHeader("Authorization"))
        assertEquals("okhttp/4.12.0", r.getHeader("User-Agent"))
        assertNull(r.getHeader("Accept-Charset"))
        assertNull(r.getHeader("X-Resound-Local-Socket-Route"))
        val socket = path == "/asr"
        val expected = linkedMapOf(
            "host" to listOf("${r.requestUrl!!.host}:${r.requestUrl!!.port}"),
            "connection" to listOf(if(socket) "Upgrade" else "Keep-Alive"),
            "accept-encoding" to listOf("gzip"),
            "user-agent" to listOf("okhttp/4.12.0"),
        )
        auth?.let { expected["authorization"] = listOf(it) }
        if(custom) expected["x-test"] = listOf("two")
        if(method == "POST") {
            val contentType = r.getHeader("Content-Type")!!
            if(path.startsWith("/speech")) assertTrue(contentType.startsWith("multipart/form-data; boundary="))
            else assertEquals("application/json; charset=utf-8",contentType)
            expected["content-type"] = listOf(contentType)
            expected["content-length"] = listOf(r.bodySize.toString())
        }
        if(path == "/balance") expected["accept"] = listOf("application/json")
        if(socket) {
            expected["upgrade"] = listOf("websocket")
            expected["sec-websocket-version"] = listOf("13")
            expected["sec-websocket-extensions"] = listOf("permessage-deflate")
            expected["sec-websocket-key"] = listOf(r.getHeader("Sec-WebSocket-Key")!!)
            expected["x-api-key"] = listOf("fake")
            expected["x-api-resource-id"] = listOf("resource")
            expected["x-api-connect-id"] = listOf(r.getHeader("X-Api-Connect-Id")!!)
        }
        assertEquals(expected, r.headers.names().associate { it.lowercase() to r.headers.values(it) })
        if (custom) {
            assertEquals(listOf("two"), r.headers.values("X-Test"))
            val names = r.headers.names().map { it.lowercase() }
            assertTrue(names.containsAll(listOf("x-test", "authorization")))
            assertTrue(r.headers.indexOfFirst { it.first.equals("X-Test", true) } <
                r.headers.indexOfFirst { it.first.equals("Authorization", true) })
        }
    }
    private val chatBody = """{"model":"test","messages":[{"role":"system","content":"系统\/指令"},{"role":"user","content":"用户"}],"response_format":{"type":"json_object"},"temperature":0.2,"max_tokens":8192}"""
    private suspend fun chat(status: Int, body: String, extra: List<HeaderEntry> = emptyList()): Result<LlmReply> = MockWebServer().use { s ->
        s.enqueue(MockResponse().setResponseCode(status).setBody(body))
        val result = runCatching { LlmClient(requestHeaders={extra}).chatJson(preset(s),"fake","系统/指令","用户") }
        val r = s.takeRequest()
        request(r,"POST","/proxy/v1/chat/completions",if(extra.isEmpty()) "Bearer fake" else "custom",extra.isNotEmpty())
        assertEquals("application/json; charset=utf-8", r.getHeader("Content-Type"))
        assertEquals(chatBody.encodeToByteArray().size.toString(), r.getHeader("Content-Length"))
        assertEquals(chatBody, r.body.readUtf8()); assertNull(r.getHeader("Accept"))
        result
    }
    private fun error(result: Result<LlmReply>, type: LlmError, status: Int = 0, detail: String = type.human, usage: LlmReply? = null) {
        val e = result.exceptionOrNull() as LlmException
        assertEquals(type,e.type); assertEquals(status,e.status); assertEquals(detail,e.message); assertEquals(usage,e.usage)
    }
    @Test fun chatContentUsageAndHeaders() = runBlocking {
        assertEquals(LlmReply("回答",9,3),chat(200,"""{"choices":[{"message":{"content":"回答"},"finish_reason":"stop"}],"usage":{"prompt_tokens":9,"completion_tokens":3}}""",headers).getOrThrow())
    }
    @Test fun chatAuthQuotaRateAndServer() = runBlocking {
        for((code,type) in listOf(401 to LlmError.Auth,402 to LlmError.Quota,403 to LlmError.Auth,429 to LlmError.RateLimit,500 to LlmError.Server))
            error(chat(code,"""{"usage":{"prompt_tokens":2,"completion_tokens":1}}"""),type,code,usage=LlmReply("",2,1))
    }
    @Test fun chatRejectionAndInvalidJson() = runBlocking {
        error(chat(400,"context length exceeded"),LlmError.Server,400,"这批原话太长，大模型服务拒收")
        error(chat(200,"not json"),LlmError.Format)
    }
    @Test fun chatEmptyReplySemantics() = runBlocking {
        assertEquals(LlmReply("",0,0),chat(200,"""{"choices":[{"message":{"content":""}}]}""").getOrThrow())
        error(chat(200,"{}"),LlmError.Format)
    }
    @Test fun fallbackPathsAndRedirectRejection() = runBlocking {
        MockWebServer().use { s ->
            s.enqueue(MockResponse().setResponseCode(404)); s.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"ok"}}]}"""))
            val p=preset(s).copy(baseUrl=s.url("/proxy").toString())
            assertEquals(LlmReply("ok",0,0),LlmClient().chatJson(p,"","s","u"))
            val expected="""{"model":"test","messages":[{"role":"system","content":"s"},{"role":"user","content":"u"}],"response_format":{"type":"json_object"},"temperature":0.2,"max_tokens":8192}"""
            for(path in listOf("/proxy/v1/chat/completions","/proxy/chat/completions")) {
                val r=s.takeRequest(); request(r,"POST",path); assertEquals(expected,r.body.readUtf8())
            }
        }
        error(chat(302,""),LlmError.Server,302)
    }
    @Test fun callTimeoutClassification() = runBlocking {
        MockWebServer().use { s ->
            s.enqueue(MockResponse().setBody("{}").setBodyDelay(1,TimeUnit.SECONDS))
            val h=OkHttpClient.Builder().callTimeout(200,TimeUnit.MILLISECONDS).build()
            error(runCatching { LlmClient(h).chatJson(preset(s),"","s","u") },LlmError.Network,
                detail="等待超时：本地模型首次加载可能较慢，稍后再试")
        }
    }
    private val stream = "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"思考\",\"content\":\"你\"}}]}\r\n\r\n" +
        "data: {\"choices\":[{\"delta\":{\"content\":\"好\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2}}\n\n" + "data: [DONE]\n\n"
    @Test fun streamFragmentsReasoningAndCallbackOrder() = runBlocking {
        MockWebServer().use { s ->
            s.enqueue(MockResponse().setBody(stream).throttleBody(13,1,TimeUnit.MILLISECONDS))
            val callbacks=mutableListOf<String>()
            val result=LlmClient().streamChat(preset(s),"",JSONArray(),JSONArray(),{callbacks.add(it)},{x,y->callbacks.add("$x/$y")})
            assertEquals(LlmStreamReply("你好",emptyList(),"思考"),result)
            assertEquals(listOf("你","3/2","好"),callbacks)
            val r=s.takeRequest(); request(r,"POST","/proxy/v1/chat/completions")
            assertEquals("""{"model":"test","messages":[],"stream":true,"stream_options":{"include_usage":true},"temperature":0.2,"max_tokens":4096}""",r.body.readUtf8())
        }
    }
    @Test fun streamInterruptedAndTruncated() = runBlocking {
        for(truncated in listOf(false,true)) MockWebServer().use { s ->
            s.enqueue(MockResponse().setBody(if(truncated) stream.replace("stop","length") else stream.substringBefore("data: [DONE]")))
            val e=runCatching { LlmClient().streamChat(preset(s),"",JSONArray(),JSONArray(),{},{_,_->}) }.exceptionOrNull() as LlmException
            assertEquals(if(truncated) LlmError.Format else LlmError.Network,e.type)
            assertEquals(if(truncated) LlmException.TRUNCATED else "网络连接失败，等待重试",e.message)
            assertEquals(truncated,e.truncated)
        }
    }
    @Test fun cancellationStopsBodyAndCallbacks() = runBlocking {
        MockWebServer().use { s ->
            s.enqueue(MockResponse().setBody(stream.substringBefore("data: [DONE]") + ": keepalive\n\n".repeat(2000)).throttleBody(64,100,TimeUnit.MILLISECONDS))
            var callbacks=0
            val first=CompletableDeferred<Unit>()
            val job=launch(start=CoroutineStart.UNDISPATCHED) {
                LlmClient().streamChat(preset(s),"",JSONArray(),JSONArray(),{callbacks++;first.complete(Unit)},{_,_->})
            }
            try {
                withTimeout(3000) { first.await() }
                withTimeout(2000) { job.cancelAndJoin() }; assertTrue(job.isCancelled)
                val n=callbacks; delay(30); assertEquals(n,callbacks)
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
            s.enqueue(MockResponse().setBody("{\"n\":4}"));val src=BalanceSource(s.url("/balance").toString(),"fake","n",headers)
            assertEquals(LlmBalance(4.0,null,null,"CNY",true,1),checker.fetch(src))
            val r=s.takeRequest(); request(r,"GET","/balance","custom",true)
            assertEquals("application/json",r.getHeader("Accept")); assertEquals(0L,r.bodySize)
            s.enqueue(MockResponse().setBody(" ".repeat(65537)))
            assertEquals("余额响应过大",runCatching{checker.fetch(src)}.exceptionOrNull()?.message)
        }
    }
    @Test fun modelsFallbackSortingAndHeaders() = runBlocking {
        MockWebServer().use { s ->
            s.enqueue(MockResponse().setResponseCode(405));s.enqueue(MockResponse().setBody("""{"data":[{"id":"z"},{"id":"a"},{"id":"z"}]}"""))
            val url=s.url("/").toString()
            assertEquals(RemoteModels(url.trimEnd('/'),listOf("a","z")),fetchRemoteModels(url,"fake",headers=headers))
            for(path in listOf("/v1/models","/models")) { val r=s.takeRequest(); request(r,"GET",path,"custom",true); assertEquals(0L,r.bodySize) }
        }
    }
    @Test fun modelsAuthAndEmptyList() = runBlocking {
        for((status,body) in listOf(403 to "{}",200 to "{\"data\":[]}")) MockWebServer().use { s ->
            s.enqueue(MockResponse().setResponseCode(status).setBody(body));val url=s.url("/v1").toString()
            assertEquals(if(status==403) "鉴权失败，请检查密钥" else "没有可选模型，请手动填写",runCatching{fetchRemoteModels(url,"")}.exceptionOrNull()?.message)
        }
    }
    private fun audio()=AsrAudio(java.io.File.createTempFile("net151",".ogg",context.cacheDir).apply{writeBytes(byteArrayOf(1,2,3))},"ogg",1200)
    @Test fun multipartBytesPromptAndResult() = runBlocking {
        MockWebServer().use { s ->
            s.enqueue(MockResponse().setBody("{\"text\":\" 你好 \"}"))
            val settings=AndroidTranscribeSettings(context);settings.saveCustomAll(s.url("/speech?x=1").toString(),"asr","",CustomEndpointMode.Full);settings.setCustomPrompt(true)
            val audio=audio()
            try {
                var count=0
                val b=CustomAsrProvider(settings){listOf("声声不息")}.recognize(audio,""){count++}
                assertEquals(AsrResult(listOf(AsrSentence(0,1200,"你好",null)),1200,null),b);assertEquals(1,count)
                val r=s.takeRequest(); request(r,"POST","/speech?x=1")
                val boundary=r.getHeader("Content-Type")!!.substringAfter("boundary=")
                val expected="--BOUNDARY\r\nContent-Disposition: form-data; name=\"model\"\r\nContent-Length: 3\r\n\r\nasr\r\n" +
                    "--BOUNDARY\r\nContent-Disposition: form-data; name=\"file\"; filename=\"${audio.file.name}\"\r\nContent-Type: audio/ogg\r\nContent-Length: 3\r\n\r\n" +
                    "\u0001\u0002\u0003\r\n--BOUNDARY\r\nContent-Disposition: form-data; name=\"response_format\"\r\nContent-Length: 4\r\n\r\njson\r\n" +
                    "--BOUNDARY\r\nContent-Disposition: form-data; name=\"prompt\"\r\nContent-Length: 33\r\n\r\n可能出现的词：声声不息\r\n--BOUNDARY--\r\n"
                assertEquals(expected,r.body.clone().readUtf8().replace(boundary,"BOUNDARY"))
                assertEquals(r.bodySize.toString(),r.getHeader("Content-Length"))
            } finally { audio.file.delete() }
        }
    }
    @Test fun transcriptionErrorsAndEmpty() {
        assertEquals("",customTranscriptionText("{\"text\":\"\"}"))
        for((code,type) in listOf(1004 to AsrError.Auth,9 to AsrError.Protocol)) {
            val e=runCatching { customTranscriptionText("{\"base_resp\":{\"status_code\":$code}}") }.exceptionOrNull() as AsrException
            assertEquals(type,e.type)
            assertEquals(if(code==1004) "鉴权失败，请检查密钥" else "服务返回错误，请检查地址、密钥和参数",e.message)
        }
    }
    @Test fun doubaoConfigAndAudioFrameBytes() {
        for(format in listOf("pcm","ogg")) {
            assertArrayEquals(DoubaoProtocol.config(format,listOf("声声不息")),NetDoubaoProtocol.config(format,listOf("声声不息")))
            for(flag in listOf(0,2)) assertArrayEquals(DoubaoProtocol.frame(2,flag,byteArrayOf(1,2,3)),NetDoubaoProtocol.frame(2,flag,byteArrayOf(1,2,3)))
        }
    }
    private suspend fun doubao(error: Boolean, disconnect: Boolean = false): Result<AsrResult> = MockWebServer().use { s ->
        val received=mutableListOf<ByteArray>()
        run {
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
            var accounted=0
            val b=runCatching{DoubaoAsrProvider(AsrAudioReader(context.cacheDir),cfg).recognize(audio,"fake") {accounted++}}
            val r=s.takeRequest(); request(r,"GET","/asr")
            assertEquals("fake",r.getHeader("X-Api-Key")); assertEquals("resource",r.getHeader("X-Api-Resource-Id"))
            assertTrue(r.getHeader("X-Api-Connect-Id")!!.matches(Regex("[a-f0-9-]{36}")))
            assertEquals("websocket",r.getHeader("Upgrade")); assertNotNull(r.getHeader("Sec-WebSocket-Key"))
            assertEquals(1,accounted); assertEquals(2,received.size)
            val config=DoubaoProtocol.config("pcm")
            assertArrayEquals(byteArrayOf(0x11,0x10,0x10,0) + java.nio.ByteBuffer.allocate(4).putInt(config.size).array() + config,received[0])
            assertArrayEquals(byteArrayOf(0x11,0x22,0,0,0,0,0x19,0) + ByteArray(6400),received[1])
            b
        } finally { audio.file.delete() }
    }
    @Test fun doubaoHandshakeFramesAndResult()=runBlocking {
        assertEquals(AsrResult(listOf(AsrSentence(0,1000,"你好",null)),1200,"safe-id"),doubao(false).getOrThrow())
    }
    @Test fun doubaoServerError()=runBlocking { assertEquals(AsrError.Auth,(doubao(true).exceptionOrNull() as AsrException).type) }
    @Test fun doubaoConnectionLossStaysNetwork()=runBlocking {
        assertEquals(AsrError.Network,(doubao(false,disconnect=true).exceptionOrNull() as AsrException).type)
    }
    @Test fun headersRejectInvalidInputsWithoutEcho() {
        for(h in listOf(HeaderEntry("Host","secret"),HeaderEntry("x","secret\n"))) {
            val old=runCatching{validateHeaders(listOf(h))};val new=runCatching{validateNetworkHeaders(listOf(h))}
            assertEquals(old.exceptionOrNull()?.message,new.exceptionOrNull()?.message)
        }
    }
}
