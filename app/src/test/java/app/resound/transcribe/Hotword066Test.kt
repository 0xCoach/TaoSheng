package app.resound.transcribe

import android.app.Application
import android.content.Context
import androidx.room.Room
import app.resound.data.*
import app.resound.transcribe.local.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class Hotword066Test {
    private val context get() = RuntimeEnvironment.getApplication() as Context
    private fun result(text: String) = AsrResult(listOf(AsrSentence(0, 100, text, "speaker")), 100, "log")
    private fun corrector() = HomophoneCorrector(HomophoneLexicon.load(context))
    private fun text(c: HomophoneCorrector, original: String, vararg words: String) =
        c.correct(result(original), words.toList()).result.sentences.single().text

    @Test fun correctsAndKeepsExactWordWithMeasuredCachedPerformance() {
        val began = System.nanoTime()
        val c = corrector()
        assertEquals("城改", text(c, "承改", "城改"))
        val firstCall = (System.nanoTime() - began) / 1e6
        val unchanged = c.correct(result("城改"), listOf("城改"))
        assertEquals(0, unchanged.replacements); assertNull(unchanged.result.sentences.single().originalText)
        val words = HomophoneLexicon.load(context).commonWords.take(199) + "城改"
        val input = result("今天讨论承改计划，城改进展需要继续确认。".repeat(20))
        c.correct(input, words)
        val times = (1..100).map {
            val start = System.nanoTime(); val output = c.correct(input, words)
            assertEquals(20, output.replacements)
            (System.nanoTime() - start) / 1e6
        }.sorted()
        println("066 performance instance_first_call_ms=$firstCall chars=${input.sentences.single().text.length} hotwords=${words.size} median_ms=${times[50]} p95_ms=${times[94]}")
    }
    @Test fun commonWordIsProtected() {
        assertEquals("黎明到了", text(corrector(), "黎明到了", "李明"))
    }
    @Test fun ignoresSingleCharacterAndNonChineseHotwords() {
        assertEquals("承改AI123", text(corrector(), "承改AI123", "城", "AI", "123", "城改AI"))
    }
    @Test fun acceptsAnyPronunciationOnEitherSide() {
        val c = corrector()
        assertEquals("长铭", text(c, "掌铭", "长铭"))
        assertEquals("掌铭", text(c, "长铭", "掌铭"))
    }
    @Test fun longestWinsTiesRemainAndHotwordChangesRefreshCache() {
        val c = corrector()
        assertEquals("城改办", text(c, "承改办", "城改", "城改办"))
        assertEquals("承改办", text(c, "承改办", "城改办", "成改办", "城改"))
        assertEquals("成改", text(c, "承改", "成改"))
        assertEquals("承改", text(c, "承改"))
    }
    @Test fun punctuationIsNotCrossedAndOriginalIsSavedForLocalImport() = runBlocking {
        val correction = LocalHotwordCorrection(context) { listOf("城改") }
        val original = "承，改。承改"
        val corrected = correction.apply(result(original))
        assertEquals("承，改。城改", corrected.sentences.single().text)
        assertEquals(original, corrected.sentences.single().originalText)
        assertEquals("speaker", corrected.sentences.single().speakerTag)
        assertTrue(PipelineTimes.dump(context).contains("同音纠正 1 处"))
        assertFalse(PipelineTimes.dump(context).contains("承改"))
        val db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java).allowMainThreadQueries().build()
        val audio = File(context.cacheDir, "hotword066.wav").apply { writeBytes(byteArrayOf(1)) }
        try {
            db.capture().segment(SegmentEntity(id = 1, startAt = 1, endAt = 101, sourceId = "import", path = audio.path, sizeBytes = 1))
            val provider = object : AsrProvider {
                override suspend fun recognize(audio: AsrAudio, key: String, beforeAudio: suspend () -> Unit) = corrected
            }
            TranscribeRunner(db.transcribe(), provider, { "local" }, { _, _ -> }, now = { 1000L }).run()
            val saved = db.transcribe().day(0, 200).first().single().lines.single()
            assertEquals(original, saved.originalText); assertEquals("承，改。城改", saved.text)
            assertEquals(1L, saved.startAt); assertEquals(101L, saved.endAt)
        } finally { db.close(); audio.delete() }
    }
    @Test fun configuredCloudDoesNotCorrectHomophones() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("{\"text\":\"承改\"}")); server.start()
        val audio = File(context.cacheDir, "cloud066.wav").apply { writeBytes(byteArrayOf(1)) }
        try {
            val settings = AndroidTranscribeSettings(context)
            settings.saveCustom(server.url("/v1/audio/transcriptions").toString(), "test")
            settings.setSource(TranscribeSource.Custom)
            val output = ConfiguredAsrProvider(context, settings) { listOf("城改") }
                .recognize(AsrAudio(audio, "wav", 100), "") {}
            assertEquals("承改", output.sentences.single().text)
            assertNull(output.sentences.single().originalText)
        } finally { server.shutdown(); audio.delete() }
    }
    @Test fun version21MigrationPreserves065DataAndOriginalDefaultsToNull() = runBlocking {
        val name = "migration066.db"; context.deleteDatabase(name)
        val schema = JSONObject(File("schemas/app.resound.data.CaptureDatabase/21.json").readText()).getJSONObject("database")
        context.openOrCreateDatabase(name, 0, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                fun sql(s: String) = s.replace("\u0024{TABLE_NAME}", entity.getString("tableName"))
                old.execSQL(sql(entity.getString("createSql")))
                val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                for (j in 0 until indices.length()) old.execSQL(sql(indices.getJSONObject(j).getString("createSql")))
            }
            val queries = schema.getJSONArray("setupQueries")
            for (i in 0 until queries.length()) old.execSQL(queries.getString(i))
            old.execSQL("INSERT INTO segments (id,startAt,endAt,sourceId,path,sizeBytes,status,firstFailedAt) VALUES (1,1,2,'mic','/old.ogg',1,'Quiet',123)")
            old.execSQL("INSERT INTO transcript_lines (segmentId,startAt,endAt,text) VALUES (1,1,2,'承改')")
            old.version = 21
        }
        val db = Room.databaseBuilder(context, CaptureDatabase::class.java, name).allowMainThreadQueries()
            .addMigrations(*CaptureDatabase.MIGRATIONS).build()
        try {
            assertEquals(CaptureDatabase.VERSION, db.openHelper.writableDatabase.version)
            assertEquals(123L, db.transcribe().segment(1).firstFailedAt)
            assertEquals("Quiet", db.transcribe().segment(1).status)
            val line = db.transcribe().day(0, 3).first().single().lines.single()
            assertEquals("承改", line.text); assertNull(line.originalText)
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
