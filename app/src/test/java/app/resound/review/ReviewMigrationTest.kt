package app.resound.review

import android.app.Application
import android.content.Context
import androidx.room.Room
import app.resound.data.CaptureDatabase
import app.resound.data.ReviewEntity
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class ReviewMigrationTest {
    @Test fun sevenToEightAndOneToEightPreserveDataAndOverwriteDaily() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        for (version in listOf(7, 1)) {
            val name = "review-migration-$version.db"
            context.deleteDatabase(name)
            val schema = JSONObject(File("schemas/app.resound.data.CaptureDatabase/$version.json").readText()).getJSONObject("database")
            context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
                val entities = schema.getJSONArray("entities")
                for (i in 0 until entities.length()) {
                    val e = entities.getJSONObject(i)
                    fun sql(s: String) = s.replace("\u0024{TABLE_NAME}", e.getString("tableName"))
                    old.execSQL(sql(e.getString("createSql")))
                    val indices = e.optJSONArray("indices") ?: JSONArray()
                    for (j in 0 until indices.length()) old.execSQL(sql(indices.getJSONObject(j).getString("createSql")))
                }
                val setup = schema.getJSONArray("setupQueries")
                for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
                old.execSQL("INSERT INTO segments(id,startAt,endAt,sourceId,path,sizeBytes,status) VALUES(1,1000,2000,'mic','/old',10,'Done')")
                if (version == 7) old.execSQL("INSERT INTO llm_usage VALUES('2026-10-23',10,5,1)")
                old.version = version
            }
            fun open() = Room.databaseBuilder(context, CaptureDatabase::class.java, name).allowMainThreadQueries()
                .addMigrations(CaptureDatabase.MIGRATION_1_2, CaptureDatabase.MIGRATION_2_3, CaptureDatabase.MIGRATION_3_4,
                    CaptureDatabase.MIGRATION_4_5, CaptureDatabase.MIGRATION_5_6, CaptureDatabase.MIGRATION_6_7, CaptureDatabase.MIGRATION_7_8, CaptureDatabase.MIGRATION_8_9, CaptureDatabase.MIGRATION_9_10).build()
            var db = open()
            try {
                assertEquals(CaptureDatabase.VERSION, db.openHelper.writableDatabase.version) // Room 校验所有表、索引、外键。
                assertEquals("/old", db.transcribe().segment(1).path)
                assertEquals(0L, db.transcribe().segment(1).playbackMillis)
                if (version == 7) assertEquals(10L, db.memory().observeUsage().first().single().promptTokens)
                val row = ReviewEntity(startDate = "2026-10-23", endDate = "2026-10-23", brief = "旧", sectionsJson = "[]", generatedAt = 1, model = "test")
                db.review().save(row); db.review().save(row.copy(brief = "新"))
                db.close(); db = open()
                assertEquals("新", db.review().recent().first().single().brief)
            } finally { db.close(); context.deleteDatabase(name) }
        }
    }
}
