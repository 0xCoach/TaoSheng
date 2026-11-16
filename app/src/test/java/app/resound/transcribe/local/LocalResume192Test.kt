package app.resound.transcribe.local

import android.app.Application
import app.resound.backup.BackupArchive
import app.resound.transcribe.originalAudioRemainingDays
import com.k2fsa.sherpa.onnx.OfflineRecognizerResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class LocalResume192Test {
    private val key = LocalResumeKey("segment:7", 480000,"pcm","cpu:model","auto",true,-1,"words","speakers",true)
    private val chunks = listOf(ResumeChunk(0,448000,0,0,0),ResumeChunk(432000,480000,0,16000,0),ResumeChunk(480000-16000,480000,1,0,464000))
    private fun initial() = LocalResumeProgress(key,listOf(ResumeTurn(0,key.samples,0)),"完整",chunks)
    private val values = listOf(
        ResumeDecoded("你好。",listOf("你","好","。"),listOf(27.1f,27.4f,27.6f).map { it.toBits() },"zh","0"),
        ResumeDecoded("你好吗？",listOf("你","好","吗","？"),listOf(.1f,.4f,.7f,.9f).map { it.toBits() },"zh","0"),
        ResumeDecoded("回答",emptyList(),emptyList(),"zh","1"))
    private fun replay(progress: LocalResumeProgress): Pair<List<app.resound.transcribe.AsrSentence>,Int> {
        val sentences = TimedSentences()
        progress.decoded.forEachIndexed { i,v ->
            val c=progress.chunks[i]
            sentences.append(OfflineRecognizerResult(v.text,v.tokens.toTypedArray(),v.timestampBits.map { Float.fromBits(it) }.toFloatArray(),v.language,"","",floatArrayOf()),
                PauseChunks.Chunk(c.start,c.end,c.speaker,c.overlap,c.groupStart),v.tag)
        }
        return sentences.result() to sentences.timedChunks
    }
    private fun files(block: (LocalResumeFiles, File, MutableList<String>) -> Unit) {
        val dir=Files.createTempDirectory("resume192").toFile(); val notes=mutableListOf<String>()
        try { block(LocalResumeFiles(dir,notes::add),dir,notes) } finally { dir.deleteRecursively() }
    }
    @Test fun everyKeyDimensionInvalidatesOldProgress() = files { store,_,_ ->
        val changes=listOf(key.copy(segment="segment:8"),key.copy(samples=480001),key.copy(fingerprint="new"),
            key.copy(recognizer="npu"),key.copy(language="en"),key.copy(separate=false),key.copy(speakerCount=2),
            key.copy(hotwords="changed"),key.copy(speakerModel="changed"),key.copy(speakersEnabled=false))
        changes.forEach { changed -> store.save(initial()); assertNull(store.restore(changed)); assertFalse(initial().matches(changed)) }
    }
    @Test fun everyInterruptionBoundaryReplaysIdenticalSentencesTimesTagsAndLanguage() = files { store,_,_ ->
        val full=values.fold(initial()) { p,v -> p.append(v) }
        for(k in 0..values.size) {
            store.save(values.take(k).fold(initial()) { p,v -> p.append(v) })
            val loaded=store.restore(key)!!
            val resumed=values.drop(loaded.decoded.size).fold(loaded) { p,v -> p.append(v) }
            assertEquals(replay(full),replay(resumed))
            assertEquals(full.decoded.map { it.language },resumed.decoded.map { it.language })
        }
        assertEquals("你好吗？回答",replay(full).first.joinToString("") { it.text })
    }
    @Test fun restoredProgressSkipsDiarizationAndSplitting() = files { store,_,_ -> runBlocking {
        store.save(initial())
        assertEquals(initial(),store.prepare(key) { error("不应重新分人或切块") })
    } }
    @Test fun corruptedFileRestartsAndRecordsOnlyFixedReason() = files { store,dir,notes -> runBlocking {
        store.save(initial());dir.listFiles()!!.single().writeText("损坏及私密文字")
        var calls=0
        assertEquals(initial(),store.prepare(key) { calls++; initial() })
        assertEquals(1,calls);assertEquals(listOf("progress-read-failed"),notes)
    } }
    @Test fun successClearsProgressAndInterruptionCountAndBackupRejectsPaths() = files { store,_,_ ->
        store.save(initial());store.state(key.segment,LocalInterruptionState(2));store.clear(key.segment)
        assertNull(store.load(key.segment));assertEquals(0,store.state(key.segment).count)
        assertFalse(BackupArchive.allowed("no_backup/asr-progress/record.progress"))
        assertFalse(BackupArchive.allowed("files/asr-progress/record.progress"))
    }
    @Test fun twoInterruptionsDeferOnlyWhileDarkAndUnplugged() {
        val once=LocalInterruptionState().interrupted(); val twice=once.interrupted()
        assertFalse(once.deferred(false,false));assertTrue(twice.deferred(false,false))
        assertFalse(twice.deferred(true,false));assertFalse(twice.deferred(false,true))
        assertFalse(LocalInterruptionState().deferred(false,false))
    }
    @Test fun sweepRemovesDeletedAndExpiredFilesButKeepsLiveProgress() = files { store,dir,_ ->
        store.save(initial());store.sweep(setOf(key.segment),System.currentTimeMillis());assertNotNull(store.load(key.segment))
        dir.listFiles()!!.forEach { it.setLastModified(1) };store.sweep(setOf(key.segment),4*86_400_000L);assertNull(store.load(key.segment))
        store.save(initial());store.sweep(emptySet(),System.currentTimeMillis());assertNull(store.load(key.segment))
    }
    @Test fun remainingDaysMatchRetentionAndProtectionRules() {
        val day=86_400_000L
        assertEquals(3,originalAudioRemainingDays(0,"SkippedPlayback",3,false,1))
        assertEquals(1,originalAudioRemainingDays(0,"SkippedPlayback",3,false,2*day))
        assertEquals(0,originalAudioRemainingDays(0,"SkippedPlayback",3,false,3*day))
        assertNull(originalAudioRemainingDays(0,"SkippedPlayback",3,true,4*day))
        assertNull(originalAudioRemainingDays(null,"SkippedPlayback",3,false,day))
        assertNull(originalAudioRemainingDays(0,"Pending",3,false,day))
        assertNull(originalAudioRemainingDays(0,"Done",3,false,day,protected=true))
        assertEquals(1,originalAudioRemainingDays(0,"SkippedEnv",0,false,1))
    }
}
