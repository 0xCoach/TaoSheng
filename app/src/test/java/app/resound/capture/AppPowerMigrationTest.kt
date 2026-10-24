package app.resound.capture

import android.app.Application
import android.content.Context
import androidx.room.Room
import app.resound.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class AppPowerMigrationTest {
    @Test fun versionThreeMigratesAndRepositoryExportsRawCounters() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        val name = "power-migration.db"
        context.deleteDatabase(name)
        val schema = JSONObject(File("schemas/app.resound.data.CaptureDatabase/3.json").readText()).getJSONObject("database")
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                fun sql(value: String) = value.replace("\u0024{TABLE_NAME}", entity.getString("tableName"))
                old.execSQL(sql(entity.getString("createSql")))
                val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                for (j in 0 until indices.length()) old.execSQL(sql(indices.getJSONObject(j).getString("createSql")))
            }
            val queries = schema.getJSONArray("setupQueries")
            for (i in 0 until queries.length()) old.execSQL(queries.getString(i))
            old.execSQL("INSERT INTO battery VALUES (1,1000,80,0)")
            old.version = 3
        }
        val db = Room.databaseBuilder(context, CaptureDatabase::class.java, name).allowMainThreadQueries()
            .addMigrations(*CaptureDatabase.MIGRATIONS).build()
        try {
            assertEquals(CaptureDatabase.VERSION, db.openHelper.writableDatabase.version)
            assertEquals(80, db.capture().batteries(0, 2000).first().single().percent)
            val date = LocalDate.now()
            val start = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            for (i in 0..3) db.capture().appPower(AppPowerEntity(at = start + i * 600_000L,
                elapsedMs = i * 600_000L, session = "s", cpuMs = i * 1000L, txBytes = i * 1_000_000L,
                rxBytes = i * 1_000_000L, micMs = i * 600_000L, charging = false, chargeEpoch = 0, capacityMah = 4880.0))
            val repo = RoomCaptureStatsRepository(context, db.capture())
            assertNotNull(repo.dayStats(date).first().appPowerPerHour)
            val export = repo.exportDiagnostics()
            try {
                val text = export.readText()
                assertTrue(text.contains("cpuMs=3000")); assertTrue(text.contains("rxBytes=3000000"))
                assertTrue(text.contains("micMs=1800000")); assertTrue(text.contains("capacityMah=4880.0"))
            } finally { export.delete() }
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
