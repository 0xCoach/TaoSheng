package app.resound.importer

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.room.Room
import app.resound.data.CaptureDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.ByteArrayInputStream
import java.io.File

/** 卡 210：点“转写”失败要说清原因、可重试、诊断不含文件名；原先一律显示“原文件不在了” */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],manifest=Config.NONE,application=Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class FolderProblem210Test {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var db: CaptureDatabase
    private lateinit var scope: CoroutineScope
    private val tree=Uri.parse("content://calls/tree/root")
    private val now=1794902400000L
    private var statError: Exception? = null
    private val file=FolderDocument(Uri.parse("content://calls/document/张三(13800138000)_20250301.mp3"),"张三(13800138000)_20250301.mp3",100,now-400*86400_000L)
    private val docs=object: FolderDocuments {
        override fun acquire(tree: Uri) {}
        override fun granted(tree: Uri)=true
        override fun root(tree: Uri)=FolderDocument(tree,"通话录音",0,0,"vnd.android.document/directory")
        override fun files(tree: Uri)=listOf(file)
        override fun stat(uri: Uri): FolderDocument { statError?.let { throw it };return file }
        override fun fingerprint(document: FolderDocument)=audioFingerprint(ByteArrayInputStream(ByteArray(100) { it.toByte() }))
        override fun metadata(document: FolderDocument)=AudioMetadata(60_000,null)
    }
    @Before fun setup() {
        listOf("folder-imports","imports","folder-import-problems").forEach { context.getSharedPreferences(it,0).edit().clear().commit() }
        File(context.cacheDir,"import").deleteRecursively()
        db=Room.inMemoryDatabaseBuilder(context,CaptureDatabase::class.java).setDriver(androidx.sqlite.driver.AndroidSQLiteDriver()).addCallback(ImportRecordSchema.callback).build()
        scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        shadowOf(context.contentResolver).registerInputStreamSupplier(file.uri) { ByteArrayInputStream(ByteArray(100) { it.toByte() }) }
    }
    @After fun close()=runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin();db.close();Unit }

    private suspend fun attempt(inspect: (File)->AudioMetadata): FolderRecording {
        val importer=AndroidAudioImporter(context,scope,inspect=inspect,enqueue={},fingerprints=db.folderImports())
        val folders=AndroidFolderImports(context,db,scope,importer,docs,{now},{_,_,_->},{})
        folders.addHistory(tree,ImportKind.Call,FolderHistory.ListAll)
        val records=AndroidFolderRecordings(context,db,scope,importer,docs,{now},folders.state,{})
        val id=db.folderRecordings().all().single().id
        records.transcribe(listOf(id));delay(200);records.pump()
        return withTimeout(5000) { records.search("张三").first { it.singleOrNull()?.state==ListedState.Missing } }.single()
    }
    @Test fun unsupportedFormatSaysSoAndCanRetry()=runBlocking {
        val r=attempt { throw ImportFailure("暂不支持这种格式") }
        assertEquals("暂不支持这种格式",r.problem)
        val dump=FolderImportProblems.dump(context)
        assertTrue(dump.contains("ext=mp3") && dump.contains("reason=暂不支持这种格式"))
        assertFalse(dump.contains("张三") || dump.contains("13800138000"))
    }
    @Test fun permissionAndMissingAreDistinguished()=runBlocking {
        statError=SecurityException("content://calls/document/张三(13800138000)_20250301.mp3 denied")
        assertEquals("文件夹需要重新授权",attempt { AudioMetadata(60_000,null) }.problem)
        assertFalse(FolderImportProblems.dump(context).contains("张三"))
    }
}
