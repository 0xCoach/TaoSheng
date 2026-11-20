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
        var length:Long?=5; var sizeCalls=0; var failTextDelete=false
        override suspend fun size(path:String):Long? { sizeCalls++; if(failDelete) error("offline"); return length }
        override suspend fun audio(path:String,file:File) { audio++ }
        override suspend fun text(path:String,content:String) { if(failDelete) error("offline"); texts[path]=content }
        override suspend fun delete(path:String) { if(failDelete || failTextDelete && path.endsWith(".txt")) error("offline"); deleted+=path
            if (path.endsWith(".ogg")) length=null }
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
        assertEquals(5L,state.records.getJSONObject("1").getLong("bytes"))
    }
    @Test fun davUsesPartPropfindAndMoveAndRejectsMissingOrWrongLength() = runBlocking {
        val calls=mutableListOf<Request>(); var length:String?="5"; var responseCode:Int?=null
        val client=OkHttpClient.Builder().addInterceptor { chain ->
            val request=chain.request(); calls+=request
            val xml="<d:multistatus xmlns:d=\"DAV:\"><d:response><d:propstat><d:prop>${length?.let { "<d:getcontentlength>$it</d:getcontentlength>" }.orEmpty()}</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>"
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(responseCode ?: if(request.method=="PROPFIND")207 else 201).message("fake").apply { if(request.method == "HEAD" && length != null) header("Content-Length", length!!) }.body(xml.toResponseBody()).build()
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
        for (bad in listOf(null,"-1")) {
            length=bad
            assertTrue(runCatching { remote.size(path) }.isFailure)
            assertTrue(runCatching { s3.size(path) }.isFailure)
        }
        responseCode=404; assertNull(remote.size(path))
        // S3 的对象 404 还须确认桶可访问。
        val missingClient=OkHttpClient.Builder().addInterceptor { chain ->
            val request=chain.request(); calls+=request
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(if(request.url.encodedPath.endsWith("/")) 200 else 404).message("fake").body("".toResponseBody()).build()
        }.build()
        val missingS3=S3FolderRemote(app.resound.backup.S3Client(app.resound.backup.S3Settings("custom", "https://example.invalid", "region", "bucket", "", true), "test", "test", missingClient), "archive")
        assertNull(missingS3.size(path))
        responseCode=403
        assertTrue(runCatching { remote.size(path) }.isFailure); assertTrue(runCatching { s3.size(path) }.isFailure)
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
    @Test fun privacyNeverDeletesRemoteEvenWithOldSwitchOn() = runBlocking {
        // 远端文件一律不自动删：旧设置 audioPrivacyDelete=true 也只列清单，不探测、不删除；逐字稿在下次上传时重写。
        val channels=channels()
        val id=channels.saveWithSubscription(null,PushKind.LocalFolder,"手机",mapOf("treeUri" to "content://test/tree/folder","displayName" to "folder"),
            PushSubscription(audio=AudioPush.All,audioPrivacyDelete=true,backfillDays=null),PushFormat.Markdown,false).getOrThrow()
        val row=row(); val file=File(context.filesDir,"recording-uploads/$id.json")
        val state=RecordingUploadState(file); state.path(row).put("audio",true).put("bytes",5L); state.dailyHash.put(day.toString(),"old-content"); state.save()
        val remote=Remote()
        val runtime=RecordingAudioPush(context,channels,{listOf(row)},{_,_->remote},{})
        runtime.withPrivacy(listOf(1)) { app.resound.privacy.ForgetResult(1,0,0,0) }
        val pending=runtime.pendingDeletes.first(); assertEquals(3,pending.size); assertTrue(pending.all { it.manual })
        assertFalse(runtime.retryDeletes()); assertTrue(remote.deleted.isEmpty()); assertEquals(0,remote.sizeCalls)
        assertFalse(runtime.run(id))
        assertTrue(remote.deleted.isEmpty())
        assertEquals("\uFEFF$day 逐字稿\n", remote.texts[RecordingKeepRules.dailyPath(day.toString())])
        assertEquals(3,runtime.pendingDeletes.first().size)
        assertTrue(runtime.protectedIds().isEmpty())
    }
    @Test fun deletedChannelKeepsPendingListUntilUserDismisses() = runBlocking {
        val channels=channels(); val state=RecordingUploadState(File(context.filesDir,"recording-uploads/99.json"))
        state.path(row()).put("audio",true); state.save()
        val runtime=RecordingAudioPush(context,channels,{emptyList()},enqueueDeletes={})
        runtime.withPrivacy(listOf(1)){app.resound.privacy.ForgetResult(1,0,0,0)}
        assertFalse(runtime.retryDeletes()); val pending=runtime.pendingDeletes.first(); assertEquals(2,pending.size); assertTrue(pending.all { it.manual })
        pending.forEach { runtime.dismiss(it.channelId,it.path) }; assertTrue(runtime.pendingDeletes.first().isEmpty())
    }
    private suspend fun privacyFixture(enabled:Boolean, bytes:Long?=5, daily:Boolean=false): Triple<RecordingAudioPush,Remote,Long> {
        val channels=channels()
        val id=channels.saveWithSubscription(null,PushKind.LocalFolder,"手机",mapOf("treeUri" to "content://test/tree/folder","displayName" to "folder"),
            PushSubscription(audio=AudioPush.All,audioPrivacyDelete=enabled,backfillDays=null),PushFormat.Markdown,false).getOrThrow()
        val row=row(); val state=RecordingUploadState(File(context.filesDir,"recording-uploads/$id.json"))
        state.path(row).put("audio",true).put("bytes",bytes)
        if(daily) state.dailyHash.put(day.toString(),"old")
        state.save()
        val remote=Remote()
        val runtime=RecordingAudioPush(context,channels,{listOf(row)},{_,_->remote},{})
        runtime.withPrivacy(listOf(1)){app.resound.privacy.ForgetResult(1,0,0,0)}
        return Triple(runtime,remote,id)
    }
    @Test fun privacyOffPersistsManualSkipsDeletionAndStillRewritesDaily() = runBlocking {
        val (runtime,remote,id)=privacyFixture(false,daily=true)
        assertEquals(3,runtime.pendingDeletes.first().count { it.manual }) // 录音、同名 txt、当天逐字稿
        assertFalse(runtime.retryDeletes()); assertTrue(remote.deleted.isEmpty()); assertEquals(0,remote.sizeCalls)
        assertFalse(runtime.run(id)) // 人工清单不阻塞后续上传/逐字稿任务。
        assertEquals("\uFEFF$day 逐字稿\n",remote.texts[RecordingKeepRules.dailyPath(day.toString())])
        val pending=runtime.pendingDeletes.first(); assertEquals(3,pending.size); assertTrue(pending.all { it.manual })
        val stored=RecordingUploadState(File(context.filesDir,"recording-uploads/pending-deletes.json"))
        assertTrue(stored.records.keys().asSequence().all { stored.records.getJSONObject(it).getBoolean("manual") })
        pending.forEach { runtime.dismiss(it.channelId,it.path) }; assertTrue(runtime.pendingDeletes.first().isEmpty())
    }
    @Test fun privacyMismatchAndLegacyWithoutBytesBecomeManualTogether() = runBlocking {
        for(bytes in listOf(4L,null)) {
            File(context.filesDir,"recording-uploads").deleteRecursively()
            val (runtime,remote,_)=privacyFixture(true,bytes)
            assertFalse(runtime.retryDeletes()); assertTrue(remote.deleted.isEmpty())
            val pending=runtime.pendingDeletes.first(); assertEquals(2,pending.size); assertTrue(pending.all { it.manual })
            val probes=remote.sizeCalls
            assertFalse(runtime.retryDeletes()); assertEquals(probes,remote.sizeCalls)
            pending.forEach { runtime.dismiss(it.channelId,it.path) }; assertTrue(runtime.pendingDeletes.first().isEmpty())
        }
    }
    @Test fun privacyRollsBackWhenLocalDeleteFails() = runBlocking {
        val channels=channels()
        val id=channels.saveWithSubscription(null,PushKind.LocalFolder,"手机",mapOf("treeUri" to "content://test/tree/folder","displayName" to "folder"),
            PushSubscription(audio=AudioPush.All,backfillDays=null),PushFormat.Markdown,false).getOrThrow()
        val row=row(); val state=RecordingUploadState(File(context.filesDir,"recording-uploads/$id.json"))
        state.path(row).put("audio",true).put("bytes",5L); state.save()
        val before=File(context.filesDir,"recording-uploads/$id.json").readText()
        val runtime=RecordingAudioPush(context,channels,{listOf(row)},{_,_->Remote()},{})
        val failure=runCatching { runtime.withPrivacy(listOf(1)) { error("数据库写入失败") } }.exceptionOrNull()
        assertEquals("数据库写入失败", failure?.message)
        // 本地没删成：不留删除标记和清单，录音照旧在推送和保护的视野里。
        assertTrue(runtime.pendingDeletes.first().isEmpty())
        assertFalse(File(context.filesDir,"recording-uploads/pending-deletes.json").exists())
        assertEquals(before, File(context.filesDir,"recording-uploads/$id.json").readText())
    }
    @Test fun legacyAutoDeleteEntriesBecomeManual() = runBlocking {
        // 旧版本留下的“正在删”条目：不再去删远端，转成“需要你自己删”。
        val (runtime,remote,_)=privacyFixture(true)
        val journal=RecordingUploadState(File(context.filesDir,"recording-uploads/pending-deletes.json"))
        journal.records.keys().asSequence().forEach { journal.records.getJSONObject(it).put("manual",false) }; journal.save()
        assertFalse(runtime.retryDeletes()); assertTrue(remote.deleted.isEmpty()); assertEquals(0,remote.sizeCalls)
        val pending=runtime.pendingDeletes.first(); assertEquals(2,pending.size); assertTrue(pending.all { it.manual })
    }
    @Test fun privacyPartlyManualJournalNeverDeletesItsSiblingAfterRestart() = runBlocking {
        val (runtime,remote,_)=privacyFixture(true)
        // 模拟关开关时只来得及把一项标为人工就中断，随后用户重新打开开关。
        val journal=RecordingUploadState(File(context.filesDir,"recording-uploads/pending-deletes.json"))
        journal.records.getJSONObject(journal.records.keys().next()).put("manual",true); journal.save()
        assertFalse(runtime.retryDeletes()); assertTrue(remote.deleted.isEmpty()); assertEquals(0,remote.sizeCalls)
        assertTrue(runtime.pendingDeletes.first().all { it.manual })
    }
    @Test fun localConflictAndChargingConstraints() = runBlocking {
        val channels=channels(); val values=mapOf("treeUri" to "content://test/tree/folder","displayName" to "folder")
        assertTrue(channels.saveWithSubscription(null,PushKind.LocalFolder,"a",values,PushTemplates.keepRecordings(),PushFormat.Markdown,false).isSuccess)
        assertTrue(channels.saveWithSubscription(null,PushKind.LocalFolder,"b",values,PushTemplates.keepRecordings(),PushFormat.Markdown,false).isFailure)
        val constraints=RecordingAudioPush.request(1,true,true,true).workSpec.constraints
        assertTrue(constraints.requiresCharging()); assertEquals(androidx.work.NetworkType.NOT_REQUIRED,constraints.requiredNetworkType)
    }
}
