package app.resound.night

import android.app.Application
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import app.resound.data.*
import app.resound.platform.FileRef
import app.resound.platform.ZoneHistory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.datetime.LocalDate
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
@Config(sdk=[29], manifest=Config.NONE, application=Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SleepWindows208AndroidTest {
    @Test fun upgradedNightAndNewNapHaveIndependentReadsAndDeletion() = runBlocking(Dispatchers.IO) {
        val context = RuntimeEnvironment.getApplication()
        val file = File(context.cacheDir, "windows208.db")
        val schema = JSONObject(File("../shared/schemas/app.resound.data.CaptureDatabase/41.json").readText())
            .getJSONObject("database").getJSONArray("entities")
        val date = LocalDate(2026, 11, 18)
        val old = NightSummary(1, date, NightState.Ready, null, null, 0, 0, 0, 0, 0, null, emptyList(), null, emptyList())
        AndroidSQLiteDriver().open(file.path).use { db ->
            for (i in 0 until schema.length()) {
                val table = schema.getJSONObject(i)
                fun create(sql: String) = db.execSQL(sql.replace("\${TABLE_NAME}", table.getString("tableName")))
                create(table.getString("createSql"))
                table.optJSONArray("indices")?.let { indices ->
                    for (j in 0 until indices.length()) create(indices.getJSONObject(j).getString("createSql"))
                }
            }
            db.prepare("INSERT INTO nights VALUES(1,?,0,1,'Ready',?,'{}',0,1)").use {
                it.bindText(1, date.toString()); it.bindText(2, Json.encodeToString(old)); it.step()
            }
            db.userVersion = 41
        }
        val db = CaptureDatabase.open(context, file.path)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val files = NightFiles(context)
        val privacy = app.resound.privacy.RoomPrivacyActions(db)
        val store = NightStore(db, scope, NightSettings(), {}, {}, { 1L }, { ZoneHistory(emptyList(), "UTC") },
            files::exists, { FileRef(files.cleanupPath(it)) }, { error("unused") },
            { assertTrue(privacy.retryFileCleanup()) }, {}, MutableStateFlow(emptyList()))
        try {
            assertEquals(CaptureDatabase.VERSION, db.testSql.version)
            val existing = db.night().onDate(date.toString())!!
            assertEquals("night" to "night", existing.kind to existing.windowId)
            val napWindow = store.settings.value.windows.last().copy(enabled = true)
            val windows = listOf(store.settings.value.windows.first(), napWindow)
            assertNull(store.saveWindows(windows))
            assertTrue(store.saveWindows(windows.map { if (it == napWindow) it.copy(startMinute = 690) else it })!!.contains("重叠"))
            assertEquals(windows, store.settings.value.windows)

            val nap = store.create(NightSession(0, 600_000, lastAt=600_000, nap=true), date, napWindow)
            assertFalse(NightRules.canReopen(NightState.valueOf(existing.state)))
            assertEquals(nap, db.night().onDate(date.toString(), "nap")!!.id)
            assertEquals(listOf(nap), store.naps(date).first().map { it.id })
            assertEquals(nap, store.nightById(nap).first()!!.summary.id)
            assertEquals(listOf(existing.id), store.nights(date, date).first().map { it.id })
            assertEquals(existing.id, store.night(date).first()!!.summary.id)
            val path = "$nap/1-nap.ogg"
            val clip = files.resolve(path).apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
            db.night().insertClip(NightClipRow(nightId=nap, start=0, end=1, path=path, scores=byteArrayOf(),
                features="{}", confirmed=false, expiresAt=1, candidate=false))
            store.deleteNight(nap)
            assertTrue(store.naps(date).first().isEmpty())
            assertTrue(db.night().clips(nap).isEmpty())
            assertFalse(clip.exists())
            assertEquals(existing.id, store.night(date).first()!!.summary.id)
        } finally { scope.coroutineContext.job.cancelAndJoin(); db.close(); file.delete() }
    }
}
