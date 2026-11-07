package app.resound.remote

import app.resound.transcribe.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

private class MemoryRemoteDao : RemoteSpeechDao {
    val records = mutableMapOf<String, RemoteSpeechTask>()
    override suspend fun task(key: String) = records[key]
    override suspend fun task(row: RemoteSpeechTask) { records[row.key] = row }
    override suspend fun removeTask(key: String) { records.remove(key) }
    override suspend fun tasks(scope: String) = records.values.filter { it.scope == scope }
    override suspend fun consumeSegment(id: Long) { records.entries.removeAll { it.value.segmentId == id } }
    override fun orphanTasks(): Flow<List<RemoteSpeechTask>> = flowOf(emptyList())
    override suspend fun people(scope: String) = emptyList<RemoteSpeechPerson>()
    override fun observePeople(): Flow<List<RemoteSpeechPerson>> = flowOf(emptyList())
    override suspend fun person(row: RemoteSpeechPerson) = Unit
    override suspend fun action(row: RemoteSpeechAction) = Unit
    override suspend fun actions(scope: String) = emptyList<RemoteSpeechAction>()
    override suspend fun removeAction(id: String) = Unit
    override suspend fun clearResults(scope: String, id: Long) = Unit
    override suspend fun clearExplicitResults(scope: String, id: Long) = Unit
    override suspend fun findAction(id: String): RemoteSpeechAction? = null
    override suspend fun sample(row: RemoteSpeechSample) = Unit
    override suspend fun samples(scope: String, id: Long) = emptyList<RemoteSpeechSample>()
    override suspend fun samplesFor(scope: String, lines: List<Long>) = emptyList<RemoteSpeechSample>()
    override suspend fun removeSample(id: String) = Unit
    override suspend fun clearSamples(scope: String, id: Long) = Unit
    override fun observeSamples(scope: String, id: Long): Flow<List<RemoteSampleRow>> = flowOf(emptyList())
    override fun observeActions(scope: String, id: Long): Flow<List<RemoteActionRow>> = flowOf(emptyList())
}

class SpeechServiceClientTest {
    private fun reply(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private suspend fun pending(block: suspend () -> Unit) {
        try { block(); fail("expected pending") } catch (e: AsrException) { assertEquals(AsrError.RemotePending, e.type) }
    }
    @Test fun discoversExplicitCapabilitiesAndLeavesGenericProvidersAlone() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(reply("""{"data":[{"id":"glm-asr-nano","capabilities":["async_jobs","diarization","identification"]}]}"""))
            server.enqueue(reply("""{"data":[{"id":"whisper-1"}]}"""))
            val client = SpeechServiceClient(MemoryRemoteDao())
            assertNotNull(client.discover(server.url("/speech/v1").toString(), "glm-asr-nano", "key"))
            assertNull(client.discover(server.url("/speech/v1").toString(), "whisper-1", "key"))
            assertEquals("/speech/v1/models", server.takeRequest().path)
        }
    }
    @Test fun resumesPersistedJobWithoutAnotherUpload() = runBlocking {
        MockWebServer().use { server ->
            val dao = MemoryRemoteDao(); val c = SpeechConnection(server.url("/v1").toString(), "glm-asr-nano", "key")
            val id = "a".repeat(32)
            server.enqueue(reply("""{"id":"$id","state":"queued"}"""))
            pending { SpeechServiceClient(dao, waitMillis = 0).operation(c, "task", "POST", "/transcription-jobs", { SpeechServiceClient.jsonBody(JSONObject()) }) }
            assertEquals(id, dao.task("task")!!.jobId)
            server.enqueue(reply("""{"id":"$id","state":"succeeded","result":{"text":"恢复成功"}}"""))
            val result = SpeechServiceClient(dao).operation(c, "task", "POST", "/transcription-jobs", { error("must not upload") })
            assertEquals("恢复成功", result.getString("text"))
            assertEquals("POST", server.takeRequest().method); assertEquals("GET", server.takeRequest().method)
        }
    }
    @Test fun slowJobCanCompleteWithoutMinuteBackoff() = runBlocking {
        MockWebServer().use { server ->
            val dao = MemoryRemoteDao()
            val c = SpeechConnection(server.url("/v1").toString(), "model", "key")
            val id = "a".repeat(32)
            server.enqueue(reply("""{"id":"$id","state":"queued"}"""))
            // 第一轮查询超过旧30秒窗口后仍在处理，随后完成。使用真实等待验证默认预算。
            server.enqueue(reply("""{"id":"$id","state":"running"}""").setBodyDelay(31, java.util.concurrent.TimeUnit.SECONDS))
            server.enqueue(reply("""{"id":"$id","state":"succeeded","result":{"text":"完成"}}"""))
            val result = withTimeout(45_000) {
                SpeechServiceClient(dao, pollMillis = 1).operation(c, "slow", "POST", "/transcription-jobs",
                    { SpeechServiceClient.jsonBody(JSONObject()) })
            }
            assertEquals("完成", result.getString("text"))
            assertEquals("POST", server.takeRequest().method)
            assertEquals("GET", server.takeRequest().method)
            assertEquals("GET", server.takeRequest().method)
            assertEquals(3, server.requestCount)
        }
    }
    @Test fun retriesUnacknowledgedSubmissionWithSameIdempotencyKey() = runBlocking {
        MockWebServer().use { server ->
            val dao = MemoryRemoteDao(); val c = SpeechConnection(server.url("/v1").toString(), "glm-asr-nano", "key")
            server.enqueue(MockResponse().setResponseCode(503))
            val client = SpeechServiceClient(dao, waitMillis = 0)
            try { client.operation(c,"task","POST","/transcription-jobs",{ SpeechServiceClient.jsonBody(JSONObject()) }); fail() } catch (_: AsrException) { }
            server.enqueue(reply("""{"id":"${"a".repeat(32)}","state":"queued"}"""))
            pending { client.operation(c,"task","POST","/transcription-jobs",{ SpeechServiceClient.jsonBody(JSONObject()) }) }
            assertEquals(server.takeRequest().getHeader("Idempotency-Key"),server.takeRequest().getHeader("Idempotency-Key"))
        }
    }
    @Test fun credentialChangeCannotReuseOldJob() = runBlocking {
        val dao = MemoryRemoteDao(); val old = SpeechConnection("http://localhost/v1", "model", "old")
        dao.task(RemoteSpeechTask("task",old.scope,"request","a".repeat(32),1,0))
        try { SpeechServiceClient(dao).operation(old.copy(key="new"),"task","POST","/transcription-jobs",{null}); fail() }
        catch (e: AsrException) { assertEquals(AsrError.Protocol,e.type) }
    }
    @Test fun deletedLocalRecordingCancelsRemoteTask() = runBlocking {
        MockWebServer().use { server ->
            val dao=MemoryRemoteDao(); val c=SpeechConnection(server.url("/v1").toString(),"model","key")
            dao.task(RemoteSpeechTask("task",c.scope,"request","a".repeat(32),1,0))
            server.enqueue(reply("{}"))
            try { SpeechServiceClient(dao).operation(c,"task","POST","/transcription-jobs",{null},valid={false});fail() }
            catch (e: AsrException) { assertEquals(AsrError.Interrupted,e.type) }
            assertEquals("DELETE",server.takeRequest().method);assertNull(dao.task("task"))
        }
    }
    @Test fun networkPauseKeepsRemoteJobForLater() = runBlocking {
        val dao=MemoryRemoteDao(); val c=SpeechConnection("http://localhost/v1","model","key")
        dao.task(RemoteSpeechTask("task",c.scope,"request","a".repeat(32),1,0))
        try { SpeechServiceClient(dao,canRequest={false}).operation(c,"task","POST","/transcription-jobs",{null});fail() }
        catch(e:AsrException) { assertEquals(AsrError.Network,e.type) }
        assertEquals("a".repeat(32),dao.task("task")!!.jobId)
    }
    @Test fun matchedIdentityUsesMappingAndUnknownScoreDoesNotBecomeMaybeMe() = runBlocking {
        val id="b".repeat(32)
        val json=JSONObject("""{"segments":[{"start":0,"end":1,"text":"你好","speaker_id":"S1","person_id":"$id","person_name":"我","similarity":0.52,"identity_status":"matched"},{"start":1,"end":2,"text":"陌生人","speaker_id":"S2","person_id":null,"person_name":null,"similarity":0.6,"identity_status":"unknown"}]}""")
        val result=parseSpeechResult(json,2000,"scope") { remote,name -> assertEquals(id,remote);assertEquals("我",name);0L }
        assertEquals("scope",result.remoteSpeakerScope);assertEquals(0L,result.sentences[0].personId)
        assertEquals("我",result.sentences[0].personName);assertNull(result.sentences[1].similarity);assertNull(result.sentences[1].personName)
    }
    @Test fun invalidTimestampCannotBeWrittenToTranscript() = runBlocking {
        try { parseSpeechResult(JSONObject("""{"segments":[{"start":-1,"end":3,"text":"错误"}]}"""),2000,"scope") { _,_->null };fail() }
        catch (e: AsrException) { assertEquals(AsrError.Protocol,e.type) }
    }
    @Test fun servicePayloadHonorsSpeakerSwitchAndDoesNotSendLegacyPrompt() {
        val file=File.createTempFile("remote-audio", ".wav").apply { writeBytes(byteArrayOf(1,2)) }
        try {
            val body=SpeechServiceClient.audioBody(SpeechConnection("http://localhost/v1","glm-asr-nano","key"),AsrAudio(file,"wav",2000,false))
            val buffer=okio.Buffer();body.writeTo(buffer);val text=buffer.readUtf8()
            assertTrue(Regex("name=\"diarize\".*?\r\n\r\nfalse", RegexOption.DOT_MATCHES_ALL).containsMatchIn(text));assertTrue(Regex("name=\"identify\".*?\r\n\r\nfalse", RegexOption.DOT_MATCHES_ALL).containsMatchIn(text));assertFalse(text.contains("name=\"prompt\""))
        } finally { file.delete() }
    }
}
