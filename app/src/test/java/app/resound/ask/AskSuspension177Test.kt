package app.resound.ask

import android.app.Application
import androidx.room.Room
import app.resound.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class AskSuspension177Test {
    @Test fun toolsAndContextReuseWriterTransactionInsteadOfBlockingForAnotherConnection() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), CaptureDatabase::class.java)
            .setDriver(androidx.sqlite.driver.AndroidSQLiteDriver()).build()
        try {
            val at = AskTools.timestamp("2026-11-13 12:00", false, ZoneId.of("Asia/Shanghai"))
            val tools = AskTools(db, JSONObject(File("src/main/assets/prompts/ask-sql.json").readText()), ZoneId.of("Asia/Shanghai"))
            withTimeout(5000) {
                db.writeTransaction {
                    val id = db.memory().insertEvent(MemoryEventEntity(startAt = at, endAt = at + 1000, scene = "其他",
                        title = "事务内可见", summary = "合成资料", pointsJson = "[]", peopleJson = "[]", place = null,
                        emotion = null, intensity = null, important = false, createdAt = at, model = "test"))
                    val sources = mutableListOf<AskSource>()
                    assertEquals(1, tools.execute("get_day", JSONObject().put("date", "2026-11-13"), sources).getJSONArray("results").length())
                    assertEquals("事务内可见", AskContextReader(db).record(sources.single().copy(kind = "event", id = id)).title)
                }
            }
        } finally { db.close() }
    }
}
