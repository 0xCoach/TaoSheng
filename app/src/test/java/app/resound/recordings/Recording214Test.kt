package app.resound.recordings

import android.app.Application
import android.content.Context
import app.resound.data.*
import app.resound.push.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import java.io.File
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29], manifest=Config.NONE, application=Application::class)
class Recording214Test {
    private val context get() = RuntimeEnvironment.getApplication() as Context
    private lateinit var root: File
    private val day = LocalDate.of(2026,11,1)
    private val now = day.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    @Before fun setup() { root = createTempDir(prefix="keep214-"); File(context.filesDir,"recording-uploads").deleteRecursively() }
    @After fun cleanup() { root.deleteRecursively(); File(context.filesDir,"recording-uploads").deleteRecursively() }
    private fun row(id: Long=1): RecordingSource {
        val at = day.atTime(9,31,7).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val file = File(root,"$id.ogg").apply { writeText("audio") }
        return RecordingSource(SegmentWithLines(SegmentEntity(id,at,at+1000,"mic",file.path,5,"Done"),
            listOf(TranscriptLineEntity(id,id,at,at+1000,"你好","1"))))
    }
    private open class Remote: RecordingRemote {
        val texts = mutableMapOf<String,String>(); var audio=0; val deleted = mutableListOf<String>(); var failDelete=false
        override suspend fun audio(path:String,file:File) { audio++ }
        override suspend fun text(path:String,content:String) { if(failDelete) error("offline"); texts[path]=content }
        override suspend fun delete(path:String) { if(failDelete) error("offline"); deleted+=path }
    }
    @Test fun dailyWaitsUntilTomorrowAndRewritesAfterEditWithoutAudio() = runBlocking {
        val row = row(); val state=RecordingUploadState(File(root,"state.json")); val remote=Remote()
        val sub=PushSubscription(audio=AudioPush.All)
        val uploader=RecordingUploader(state,remote)
        uploader.run(listOf(row),sub,now-1000); assertTrue(remote.texts.isEmpty())
        uploader.run(listOf(row),sub,now)
        val content=remote.texts.values.single()
        assertTrue(content.toByteArray().take(3)==listOf(0xef.toByte(),0xbb.toByte(),0xbf.toByte()))
        assertTrue(content.contains("09:31（录音：${row.stem}.ogg）")); assertTrue(content.contains("[09:31:07] 说话人2：你好"))
        row.file.delete()
        val changed=row.copy(row=row.row.copy(lines=row.row.lines.map { it.copy(text="修改",speakerName="我") }))
        uploader.run(listOf(changed),sub,now)
        assertEquals(1,remote.audio); assertTrue(remote.texts.values.single().contains("我：修改"))
        assertEquals(1,state.records.getJSONObject("1").getInt("verified"))
    }
    @Test fun davUsesPartPropfindAndMoveAndRejectsMissingOrWrongLength() = runBlocking {
        val calls=mutableListOf<Request>(); var length:String?="5"
        val client=OkHttpClient.Builder().addInterceptor { chain ->
            val request=chain.request(); calls+=request
            val xml="<d:multistatus xmlns:d=\"DAV:\"><d:response><d:propstat><d:prop>${length?.let { "<d:getcontentlength>$it</d:getcontentlength>" }.orEmpty()}</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>"
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if(request.method=="PROPFIND")207 else 201).message("fake").apply { if(request.method == "HEAD" && length != null) header("Content-Length", length!!) }.body(xml.toResponseBody()).build()
        }.build()
        val remote=RecordingDav(mapOf("url" to "https://example.invalid/dav/","folder" to "archive","account" to "test","password" to "test"),client)
        val row=row(); val path="录音/$day/${row.stem}.ogg"
        remote.audio(path,row.file)
        assertEquals(listOf("MKCOL","MKCOL","MKCOL","PUT","PROPFIND","MOVE"),calls.map { it.method })
        assertTrue(calls[3].url.pathSegments.last().startsWith('.')); assertEquals("0",calls[4].header("Depth"))
        for (bad in listOf(null,"4")) { length=bad; calls.clear(); assertTrue(runCatching { remote.audio(path,row.file) }.isFailure); assertFalse(calls.any { it.method=="MOVE" }) }
        assertFalse(calls.any { it.method=="GET" })
        val s3 = S3FolderRemote(app.resound.backup.S3Client(app.resound.backup.S3Settings("custom", "https://example.invalid", "region", "bucket", "", true), "test", "test", client), "archive")
        calls.clear(); length="5"; s3.audio(path,row.file)
        assertEquals(listOf("PUT","HEAD"),calls.map { it.method })
        for (bad in listOf(null,"4")) { length=bad; assertTrue(runCatching { s3.audio(path,row.file) }.isFailure) }
        assertFalse(calls.any { it.method=="GET" })
    }
    @Test fun verificationFailureNeverAcknowledgesAndRetryUsesSamePath() = runBlocking {
        val state=RecordingUploadState(File(root,"state.json")); val row=row(); val paths=mutableListOf<String>()
        var fail=true
        val remote=object:Remote(){ override suspend fun audio(path:String,file:File){ paths+=path; if(fail) error("bad length") } }
        val sub=PushSubscription(audio=AudioPush.All)
        assertTrue(runCatching { RecordingUploader(state,remote).run(listOf(row),sub,now) }.isFailure)
        assertFalse(state.uploaded(1)); assertTrue(state.records.getJSONObject("1").has("temporary"))
        fail=false
        RecordingUploader(RecordingUploadState(File(root,"state.json")),remote).run(listOf(row),sub,now)
        assertEquals(paths.first(),paths.last()); assertTrue(RecordingUploadState(File(root,"state.json")).uploaded(1))
    }
    private fun channels(): AndroidPushChannels = AndroidPushChannels(context.getSharedPreferences("214channels",0).also { it.edit().clear().commit() },
        context.getSharedPreferences("214outbox",0),object:PushSecrets {
            override fun read(ref:String)=""; override fun write(ref:String,value:String){}; override fun remove(ref:String){}
        },PushTransport(),{},stateDirectory=File(root,"push"))
    @Test fun privacyPersistsExactFilesAndRetriesAfterRestartWithoutReupload() = runBlocking {
        val channels=channels()
        val id=channels.saveWithSubscription(null,PushKind.LocalFolder,"手机",mapOf("treeUri" to "content://test/tree/folder","displayName" to "folder"),
            PushSubscription(audio=AudioPush.All),PushFormat.Markdown,false).getOrThrow()
        val row=row(); val file=File(context.filesDir,"recording-uploads/$id.json")
        val state=RecordingUploadState(file); val record=state.path(row); record.put("audio",true); state.dailyHash.put(day.toString(),"old-content"); state.save()
        val remote=Remote().apply { failDelete=true }
        fun runtime()=RecordingAudioPush(context,channels,{listOf(row)},{_,_->remote},{})
        val runtime=runtime()
        runtime.withPrivacy(listOf(1)) { app.resound.privacy.ForgetResult(1,0,0,0) }
        assertEquals(3,runtime.pendingDeletes.first().size); assertTrue(runtime.retryDeletes())
        val restored=runtime(); assertEquals(3,restored.pendingDeletes.first().size)
        channels.saveWithSubscription(id,PushKind.LocalFolder,"手机",mapOf("treeUri" to "content://test/tree/folder","displayName" to "folder"),
            PushSubscription(audio=AudioPush.Off),PushFormat.Markdown,false).getOrThrow()
        remote.failDelete=false; assertFalse(restored.retryDeletes())
        assertEquals(RecordingPrivacyRules.files(record.getString("path")).toSet(),remote.deleted.toSet())
        assertTrue(restored.pendingDeletes.first().isEmpty()); assertEquals(0,RecordingUploadState(file).records.length())
        assertEquals("\uFEFF$day 逐字稿\n", remote.texts[RecordingKeepRules.dailyPath(day.toString())])
        assertTrue(restored.protectedIds().isEmpty())
    }
    @Test fun deletedChannelKeepsPendingListUntilUserDismisses() = runBlocking {
        val channels=channels(); val state=RecordingUploadState(File(context.filesDir,"recording-uploads/99.json"))
        state.path(row()).put("audio",true); state.save()
        val runtime=RecordingAudioPush(context,channels,{emptyList()},enqueueDeletes={})
        runtime.withPrivacy(listOf(1)){app.resound.privacy.ForgetResult(1,0,0,0)}
        assertFalse(runtime.retryDeletes()); val pending=runtime.pendingDeletes.first(); assertEquals(2,pending.size)
        pending.forEach { runtime.dismiss(it.channelId,it.path) }; assertTrue(runtime.pendingDeletes.first().isEmpty())
    }
    @Test fun localConflictAndChargingConstraints() = runBlocking {
        val channels=channels(); val values=mapOf("treeUri" to "content://test/tree/folder","displayName" to "folder")
        assertTrue(channels.saveWithSubscription(null,PushKind.LocalFolder,"a",values,PushTemplates.keepRecordings(),PushFormat.Markdown,false).isSuccess)
        assertTrue(channels.saveWithSubscription(null,PushKind.LocalFolder,"b",values,PushTemplates.keepRecordings(),PushFormat.Markdown,false).isFailure)
        val constraints=RecordingAudioPush.request(1,true,true,true).workSpec.constraints
        assertTrue(constraints.requiresCharging()); assertEquals(androidx.work.NetworkType.NOT_REQUIRED,constraints.requiredNetworkType)
    }
}
