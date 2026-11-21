package app.resound.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** 38→39：memory_items.recordedAt 从原话行回填；积压整理（createdAt 远晚于说话时间）后回顾归属不再错。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class RecordedAtMigrationTest {
    @Test fun itemGetsRecordedAtFromSourceLineAndManualStaysNull() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        val name = "recorded-at-38.db"
        context.deleteDatabase(name)
        val schema = JSONObject(File("../shared/schemas/app.resound.data.CaptureDatabase/38.json").readText()).getJSONObject("database")
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i)
                fun sql(s: String) = s.replace("${'$'}{TABLE_NAME}", e.getString("tableName"))
                old.execSQL(sql(e.getString("createSql")))
                val indices = e.optJSONArray("indices") ?: JSONArray()
                for (j in 0 until indices.length()) old.execSQL(sql(indices.getJSONObject(j).getString("createSql")))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
            old.execSQL("INSERT INTO segments(id,startAt,endAt,sourceId,path,sizeBytes,status) VALUES(1,1000,2000,'mic','/a.ogg',10,'Done')")
            // 说话时间 17:00，处理时间 23:00（积压整理场景）。
            old.execSQL("INSERT INTO transcript_lines(id,segmentId,startAt,endAt,text) VALUES(7,1,1700000000000,1700000003000,'记得给小王回电话')")
            old.execSQL("INSERT INTO memory_items(id,eventId,category,kind,content,status,createdAt,lineId) VALUES(11,NULL,'待办','我要做','给小王回电话','Pending',1700010800000,7)")
            old.execSQL("INSERT INTO memory_items(id,eventId,category,kind,content,status,createdAt,lineId) VALUES(12,NULL,'待办','manual','手打任务','Pending',1700010800000,NULL)")
            old.version = 38
        }
        val db = Room.databaseBuilder(context, CaptureDatabase::class.java, name)
            .setDriver(androidx.sqlite.driver.AndroidSQLiteDriver()).allowMainThreadQueries()
            .addMigrations(*CaptureDatabase.MIGRATIONS).build()
        try {
            assertEquals(CaptureDatabase.VERSION, db.testSql.version)
            val linked = db.memory().itemIncludingDeleted(11)!!
            assertEquals(1700000000000L, linked.recordedAt)
            assertEquals(1700010800000L, linked.createdAt) // 处理时间原样保留
            assertNull(db.memory().itemIncludingDeleted(12)!!.recordedAt) // 手打无原话行
            assertEquals(1700000000000L, db.memory().lineStartAt(7))
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
