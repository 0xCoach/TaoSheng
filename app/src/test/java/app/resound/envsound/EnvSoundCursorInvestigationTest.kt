package app.resound.envsound

import android.app.Application
import android.database.AbstractWindowedCursor
import android.database.CursorWindow
import android.database.sqlite.SQLiteBlobTooBigException
import androidx.room.Room
import androidx.room.withTransaction
import app.resound.data.CaptureDatabase
import app.resound.data.SegmentEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 缩小 CursorWindow 确定性重现换页时删行；只使用独立测试库。 */
@RunWith(RobolectricTestRunner::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
class EnvSoundCursorInvestigationTest {
    @Test fun deletingSixRowsDuringWindowRefillInvalidatesCursorPosition() = probe(false)
    @Test fun transactionKeepsWindowReadsConsistentDuringSameDeletion() = probe(true)

    private fun probe(transaction: Boolean) = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "env-cursor-${System.nanoTime()}.db"
        val db = Room.databaseBuilder(context, CaptureDatabase::class.java, name)
            .allowMainThreadQueries().setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING).build()
        val writer = Executors.newSingleThreadExecutor()
        try {
            db.withTransaction {
                for (id in 1L..32L) {
                    db.capture().segment(SegmentEntity(id, id, id + 1, "mic", "/test/$id.ogg", 0, "Done"))
                    db.envSound().insert(EnvSoundRow(id, id, id + 1, "/test/$id.ogg", "Keep", null,
                        "[]", "[]", "x".repeat(600), false, false))
                }
            }
            val sqlDb = db.openHelper.writableDatabase
            if (transaction) sqlDb.beginTransactionNonExclusive()
            val cursor = sqlDb.query("SELECT * FROM env_sounds ORDER BY startAt") as AbstractWindowedCursor
            try {
                cursor.window = CursorWindow("env-delete-probe", 1024)
                assertEquals(32, cursor.count)
                assertTrue(cursor.moveToPosition(30))
                assertEquals(31L, cursor.getLong(0))
                assertEquals(1, cursor.window.numRows)
                val entered = CountDownLatch(1)
                val deletion = writer.submit {
                    entered.countDown()
                    sqlDb.execSQL("DELETE FROM segments WHERE id > 26")
                }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                if (!transaction) deletion.get(5, TimeUnit.SECONDS)
                if (transaction) {
                    assertTrue(cursor.moveToNext())
                    assertEquals(32L, cursor.getLong(0))
                    cursor.close()
                    sqlDb.setTransactionSuccessful()
                    sqlDb.endTransaction()
                    deletion.get(5, TimeUnit.SECONDS)
                } else {
                    val failure = runCatching { assertTrue(cursor.moveToNext()); cursor.getLong(0) }.exceptionOrNull()
                    // Android 15 的实现提前在填充窗口时报错；手机 Android 16 日志则在 getLong 报错。
                    // 验证的是相同的换页期间结果集缩短，不能把实验异常类型当成真机完全复现。
                    assertTrue("Expected window refill failure, got $failure", failure is SQLiteBlobTooBigException)
                    assertTrue(failure!!.message.orEmpty().contains("requiredPos=31, totalRows=26"))
                    println("Reproduced: ${failure.javaClass.simpleName}: ${failure.message}")
                }
                sqlDb.query("SELECT COUNT(*) FROM env_sounds").use {
                    assertTrue(it.moveToFirst()); assertEquals(26, it.getInt(0))
                }
            } finally {
                cursor.close()
                if (sqlDb.inTransaction()) sqlDb.endTransaction()
            }
        } finally {
            writer.shutdownNow(); writer.awaitTermination(5, TimeUnit.SECONDS)
            db.close(); context.deleteDatabase(name)
        }
    }
}
