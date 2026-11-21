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

/** 39→40：valueScore 回填与 MemoryValueRules.itemScore 同分；回顾条目按有效分排序。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class ValueScoreMigrationTest {
    private fun buildAt(context: Context, name: String, version: Int) {
        val schema = JSONObject(File("../shared/schemas/app.resound.data.CaptureDatabase/$version.json").readText()).getJSONObject("database")
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
            old.version = version
        }
    }

    @Test fun backfillMatchesRulesAndReviewOrdersByEffectiveScore() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        val name = "value-score-39.db"
        context.deleteDatabase(name)
        buildAt(context, name, 39)
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
            old.execSQL("INSERT INTO segments(id,startAt,endAt,sourceId,path,sizeBytes,status) VALUES(1,1000,2000,'mic','/a.ogg',10,'Done')")
            // id=1 普通提取备忘=25；id=2 带期限+人名的约定=85；id=3 手打待办=50
            old.execSQL("INSERT INTO memory_items(id,eventId,category,kind,content,status,createdAt,origin) VALUES(1,NULL,'备忘',NULL,'随口一提','Pending',1,'digest')")
            old.execSQL("INSERT INTO memory_items(id,eventId,category,kind,content,status,createdAt,dueAt,who,origin) VALUES(2,NULL,'约定','我答应别人','下周给小王合同','Pending',2,999,'小王','digest')")
            old.execSQL("INSERT INTO memory_items(id,eventId,category,kind,content,status,createdAt,origin) VALUES(3,NULL,'待办','我要做','手打任务','Accepted',3,'typed')")
            old.version = 39
        }
        val db = Room.databaseBuilder(context, CaptureDatabase::class.java, name)
            .setDriver(androidx.sqlite.driver.AndroidSQLiteDriver()).allowMainThreadQueries()
            .addMigrations(*CaptureDatabase.MIGRATIONS).build()
        try {
            assertEquals(CaptureDatabase.VERSION, db.testSql.version)
            for (id in 1L..3L) {
                val item = db.memory().itemIncludingDeleted(id)!!
                assertEquals(MemoryValueRules.itemScore(item.category, item.kind, item.dueAt, item.who, item.origin), item.valueScore)
            }
            assertEquals(85, db.memory().itemIncludingDeleted(2)!!.valueScore)
            assertEquals(50, db.memory().itemIncludingDeleted(3)!!.valueScore)
            // 回顾排序：有效分高在前（约定85 > 手打50 > 备忘25）
            val ordered = db.review().items(0, 10)
            assertEquals(listOf(2L, 3L, 1L), ordered.map { it.id })
            // 新建星标事件，挂 25 分条目：父事件实时加成 +20 把它顶过裸分 25 的 id=1
            db.memory().insertEvent(MemoryEventEntity(9, 5, 6, "工作", "t", "s", "[]", "[]", null, null, null, false,
                createdAt = 1, model = "t", starred = true, starredAt = 5))
            db.memory().insertItems(listOf(MemoryItemEntity(4, 9, "备忘", null, "星标事件里的备忘", null, null, null, null, "Pending", 4,
                valueScore = 25)))
            val boosted = db.review().items(0, 10).map { it.id }
            assertEquals(listOf(2L, 3L, 4L, 1L), boosted)
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
