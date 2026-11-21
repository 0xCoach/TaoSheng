package app.resound.topic

import android.app.Application
import androidx.room.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import app.resound.data.CaptureDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** R5.1 防回归：同名会议不等于同系列——排期链按 seriesId 隔离，散列同名才按名归组。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class TopicSeriesChainTest {
    private lateinit var db: CaptureDatabase

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), CaptureDatabase::class.java)
            .setDriver(AndroidSQLiteDriver()).allowMainThreadQueries().build()
    }
    @After fun close() = db.close()

    private fun meeting(id: Long, key: String?, chain: Long?, status: String = "Done") =
        TopicRow(id = id, kind = "Meeting", name = "周例会", status = status, createdAt = id,
            seriesKey = key, seriesId = chain)

    @Test fun sameNameChainsDoNotCross() = runBlocking {
        val dao = db.topics()
        // 项目A 周例会链（首场自锚=1，续场=2）；项目B 周例会链（首场=3，续场=4）；散列同名=5。
        listOf(meeting(1, "周例会", 1), meeting(2, "周例会", 1),
            meeting(3, "周例会", 3), meeting(4, "周例会", 3),
            meeting(5, "周例会", null)).forEach { dao.insert(it) }
        // B 链新场回链：只看得见 B 链，A 链和散列同名都不会被注入成"上次承诺"。
        assertEquals(4L, dao.previousDone("Meeting", "周例会", 9, 3)!!.id)
        // A 链同理：跳过更近的 B 链成员。
        assertEquals(2L, dao.previousDone("Meeting", "周例会", 9, 1)!!.id)
        // 散列专题按名归组，但只看散列成员（链成员不外泄）。
        assertEquals(5L, dao.previousDone("Meeting", "周例会", 9, null)!!.id)
    }

    @Test fun adhocSameNameGroupsByKey() = runBlocking {
        val dao = db.topics()
        listOf(meeting(1, "周例会", null, status = "Done"),
            meeting(2, "周例会", null, status = "Done"),
            meeting(3, "复盘", null, status = "Done")).forEach { dao.insert(it) }
        // 两条散列同名仍是同系列——非周期的一再重开也该跟进。
        assertEquals(2L, dao.previousDone("Meeting", "周例会", 9, null)!!.id)
        // 首场没有更早的同系列会议：不注入、不编造"上次承诺"。
        assertNull(dao.previousDone("Meeting", "周例会", 1, null))
    }
}
