package app.resound.transcribe

import android.app.Application
import android.content.Context
import androidx.room.Room
import app.resound.data.*
import app.resound.digest.HeaderEntry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class Asr212Test {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    @Test fun migrates43AndAccumulatesTokensWithoutChangingAudioUsage() = runBlocking {
        val name = "asr212-migration.db"; context.deleteDatabase(name)
        val schema = JSONObject(File("../shared/schemas/app.resound.data.CaptureDatabase/43.json").readText()).getJSONObject("database")
        context.openOrCreateDatabase(name, 0, null).use { old ->
            val entities = schema.getJSONArray("entities")
            repeat(entities.length()) { i ->
                val entity = entities.getJSONObject(i)
                fun sql(s: String) = s.replace("\u0024{TABLE_NAME}", entity.getString("tableName"))
                old.execSQL(sql(entity.getString("createSql")))
                entity.optJSONArray("indices")?.let { indices -> repeat(indices.length()) { old.execSQL(sql(indices.getJSONObject(it).getString("createSql"))) } }
            }
            val setup = schema.getJSONArray("setupQueries"); repeat(setup.length()) { old.execSQL(setup.getString(it)) }
            old.execSQL("INSERT INTO asr_usage(day,sentMillis) VALUES('2026-11-20',3000)")
            old.version = 43
        }
        val db = Room.databaseBuilder(context, CaptureDatabase::class.java, name)
            .setDriver(androidx.sqlite.driver.AndroidSQLiteDriver()).addMigrations(*CaptureDatabase.MIGRATIONS).build()
        try {
            assertEquals(CaptureDatabase.VERSION, db.testSql.version)
            val dao = db.transcribe()
            assertEquals(AsrUsageEntity("2026-11-20", 3000), dao.observeUsage().first().single())
            dao.recordTokens("2026-11-20", 105, 83); dao.recordTokens("2026-11-20", 7, null)
            val row = app.resound.usage.RoomUsageQueries(db.memory(), dao).asr(
                kotlinx.datetime.LocalDate.parse("2026-11-20"), kotlinx.datetime.LocalDate.parse("2026-11-20")).first().single()
            assertEquals(3000L, row.cloudMillis); assertEquals(112L, row.inputTokens); assertEquals(83L, row.outputTokens)
        } finally { db.close(); context.deleteDatabase(name) }
    }
    @Test fun moderationIsNotRescheduledAndTestResponseRecordsTokens() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java)
            .setDriver(androidx.sqlite.driver.AndroidSQLiteDriver()).build()
        val file = File.createTempFile("asr212", ".ogg", context.cacheDir).apply { writeBytes(byteArrayOf(1)) }
        try {
            val dao = db.transcribe()
            db.capture().segment(SegmentEntity(startAt=1, endAt=3001, sourceId="mic", path=file.path, sizeBytes=1))
            var calls = 0
            val runner = TranscribeRunner(dao, object : AsrProvider {
                override suspend fun recognize(audio: AsrAudio, key: String, beforeAudio: suspend () -> Unit): AsrResult {
                    beforeAudio(); calls++
                    throw AsrException(AsrError.Protocol, detail="内容没通过千问审核，这段跳过")
                }
            }, { "" }, { _,_ -> }, today={ "2026-11-20" })
            assertFalse(runner.run()); assertFalse(runner.run()); assertEquals(1, calls)
            val failed = dao.observeSegments().first().single()
            assertEquals("Failed", failed.status); assertNull(failed.nextRetryAt)
            val tester = TranscribeRunner(dao, object : AsrProvider {
                override suspend fun recognize(audio: AsrAudio, key: String, beforeAudio: suspend () -> Unit): AsrResult {
                    beforeAudio(); return AsrResult(emptyList(),3000,null,105,83,CustomCapability.Speakers)
                }
            }, { "" }, { _,_ -> }, today={ "2026-11-20" })
            var capability: CustomCapability? = null
            assertTrue(tester.test(AsrAudio(file,"ogg",3000), "") { capability=it.customCapability }.isSuccess)
            assertEquals(CustomCapability.Speakers, capability)
            assertEquals(AsrUsageEntity("2026-11-20", 6000,105,83), dao.observeUsage().first().single())
        } finally { db.close(); file.delete() }
    }
    @Test fun androidSettingsExposeCurrentConfigRealCredentialStateAndTestCapability() = runBlocking {
        context.getSharedPreferences("transcribe",0).edit().clear().commit()
        val headerValues = mutableMapOf<String,String>()
        val s = AndroidTranscribeSettings(context, app.resound.digest.ServiceHeaders(
            { headerValues[it].orEmpty() }, { k,v -> headerValues[k]=v }, { headerValues.remove(it); Unit }))
        s.setSource(TranscribeSource.Custom)
        val saved = s.saveCustomAll("https://synthetic.invalid/asr", "open", "", CustomEndpointMode.Full)
        assertFalse(saved.keySaved); assertEquals(CustomKeyState.None,saved.keyState); assertTrue(s.hasKey.value)
        s.saveHeaders(TranscribeSource.Custom.name, listOf(HeaderEntry("X-Test", "synthetic")))
        assertEquals(CustomKeyState.Saved,s.customKeyState.value)
        s.testWithCapability = { _, report -> report(CustomCapability.TimedText); Result.success("可用") }
        assertTrue(s.test().isSuccess); assertEquals(CustomKeyState.Verified,s.customKeyState.value)
        val identity = s.identity()
        s.setCustomApi(CustomAsrApi.Qwen); assertNotEquals(identity,s.identity())
        assertEquals(CustomAsrSettings.QWEN_MODEL,s.customModel); assertNull(s.customCapability.value)
        s.saveCustomAll("https://synthetic.invalid/qwen", "qwen", "", CustomEndpointMode.Auto)
        assertEquals(CustomEndpointMode.Full,s.customEndpointMode)
        s.setCustomApi(CustomAsrApi.OpenAi); assertEquals("open",s.customModel)
        s.saveCustom("https://synthetic.invalid/asr","changed",CustomEndpointMode.Full)
        assertEquals(CustomKeyState.Saved,s.customKeyState.value); assertNull(s.customCapability.value)
        s.saveHeaders(TranscribeSource.Custom.name, emptyList()); assertEquals(CustomKeyState.None,s.customKeyState.value)
    }
}
