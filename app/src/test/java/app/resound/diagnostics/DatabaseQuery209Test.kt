package app.resound.diagnostics

import android.app.Application
import android.content.Context
import androidx.room.InvalidationTracker
import androidx.room.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import app.resound.R
import app.resound.data.CaptureDatabase
import app.resound.data.SegmentEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class DatabaseQuery209Test {
    @Test fun sixWindowsAndTopTen() {
        var now = 0L
        val stats = DatabaseQueryStats { now }
        stats.query("过期", 1, 1)
        now += 3_600_000
        repeat(11) { stats.query("来源-$it ", it * 1_000_000L, it.toLong()) }
        val report = stats.dump()
        assertFalse("过期" in report)
        assertFalse("来源-0 " in report)
        assertTrue("10 毫秒  10 行" in report)
    }

    // 单线程对照排除 Room 调度和写入噪声；使用真实驱动，走通用 DAO 归因路径。
    private object ProbeDao_Impl {
        fun read(db: androidx.sqlite.SQLiteConnection) {
            db.prepare("SELECT 1").use { check(it.step()); check(it.getLong(0) == 1L) }
        }
    }

    @Test fun costPerReadOnCallingThread() {
        val mx = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
        val time = Class.forName("java.lang.management.ThreadMXBean").getMethod("getCurrentThreadCpuTime")
        fun batch(db: androidx.sqlite.SQLiteConnection): Long {
            val start = time.invoke(mx) as Long
            repeat(10_000) { ProbeDao_Impl.read(db) }
            return (time.invoke(mx) as Long) - start
        }
        val driver = AndroidSQLiteDriver()
        val stats = DatabaseQueryStats { 0 }
        driver.open(":memory:").use { plain ->
            QueryStatsDriver(driver, stats).open(":memory:").use { measured ->
                batch(plain); batch(measured)
                val a = batch(plain)
                val b = batch(measured) + batch(measured)
                val c = batch(plain)
                println("209MICRO baselineNs=${a+c} instrumentedNs=$b queries=20000")
                assertEquals(30_000L, stats.current().queries.values.single().count)
            }
        }
    }

    @Test fun realRoomReadsInvalidationsAndCpu() = runBlocking {
        val context: Context = RuntimeEnvironment.getApplication()
        val bean = Class.forName("java.lang.management.ManagementFactory")
            .getMethod("getOperatingSystemMXBean").invoke(null)
        val cpu = Class.forName("com.sun.management.OperatingSystemMXBean").getMethod("getProcessCpuTime")
        fun cpuNanos() = cpu.invoke(bean) as Long
        val tables = context.getString(R.string.capture_diagnostic_tables).split(',').toTypedArray()
        suspend fun run(measure: Boolean): Long {
            val stats = DatabaseQueryStats { 0 }
            val plain = AndroidSQLiteDriver()
            val db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java)
                .setDriver(if (measure) QueryStatsDriver(plain, stats) else plain).build()
            try {
                if (measure) db.invalidationTracker.addObserver(object : InvalidationTracker.Observer(tables) {
                    override fun onInvalidated(tables: Set<String>) = stats.invalidated(tables)
                })
                suspend fun batch(start: Int, count: Int) {
                    repeat(count) {
                        val id = (start + it).toLong()
                        db.capture().segment(SegmentEntity(startAt=id, endAt=id+1, sourceId="mic", path="隐私/$id", sizeBytes=1))
                        assertEquals(1, db.capture().segments(id, id+1).first().size)
                        assertNull(db.folderRecordings().get(id))
                    }
                }
                batch(0, 30)
                val begin = cpuNanos()
                batch(30, 300)
                val elapsed = cpuNanos() - begin
                if (measure) {
                    val b = stats.current()
                    assertEquals(330L, b.queries.getValue("CaptureDao.segments").rows)
                    assertEquals(330L, b.queries.getValue("FolderRecordingDao.get").count)
                    assertTrue(b.tables.getValue("segments") > 0)
                    assertFalse(stats.dump().contains("隐私"))
                }
                return elapsed
            } finally { db.close() }
        }
        // 先预热两种路径，再交错测量，降低首次打开 Room/JIT 对对比的影响。
        run(false); run(true)
        val plain1 = run(false)
        val measured1 = run(true)
        val measured2 = run(true)
        val plain2 = run(false)
        println("209CPU baselineMs=${(plain1+plain2)/1_000_000} instrumentedMs=${(measured1+measured2)/1_000_000} batches=600")
    }
}
