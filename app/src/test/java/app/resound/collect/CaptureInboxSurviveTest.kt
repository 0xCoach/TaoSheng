package app.resound.collect

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.room.Room
import app.resound.data.CaptureDatabase
import app.resound.data.testSql
import app.resound.platform.NowMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** 断网收单回归：分享方/界面被回收不能导致「文件复制了、库行丢了」。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class CaptureInboxSurviveTest {
    private lateinit var db: CaptureDatabase
    private val now = 2_000_000_000_000L
    private val ctx get() = RuntimeEnvironment.getApplication()

    @Before fun setup() {
        File(ctx.filesDir, "inbox").deleteRecursively()
        db = Room.inMemoryDatabaseBuilder(ctx, CaptureDatabase::class.java)
            .setDriver(androidx.sqlite.driver.AndroidSQLiteDriver())
            .allowMainThreadQueries().build()
    }
    @After fun close() = db.close()

    private fun imageIntent(src: File) = Intent(Intent.ACTION_SEND)
        .setType("image/png").putExtra(Intent.EXTRA_STREAM, Uri.fromFile(src))
    private fun rowCount(): Int = db.testSql.query("SELECT COUNT(*) FROM capture_items").use {
        it.moveToFirst(); it.getInt(0) }

    /** 调用方（Activity lifecycleScope）在收单排队期间死掉：库行仍必须落库。 */
    @Test fun surviveCallerCancellation() = runTest {
        val d = StandardTestDispatcher(testScheduler) // 收单编排调度器：body 排队可控
        val inbox = CaptureInbox(ctx, db.captureItems(), d,
            CoroutineScope(SupervisorJob() + d), NowMillis { now }) // 应用存活期 scope（ResoundApp 注入的同款）
        val src = File(ctx.filesDir, "cancel_src.png").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val caller = CoroutineScope(Job() + Dispatchers.Unconfined)
        caller.launch { inbox.receive(imageIntent(src), "com.xhs") } // Unconfined 走到首个挂起点：body 已排队未执行
        caller.cancel()              // 断网实测的死亡点：界面协程被回收
        // Room 落库在真线程上：交替泵送调度器与真实等待，直至落库或超时
        var rows = 0
        repeat(200) {
            advanceUntilIdle()
            rows = rowCount()
            if (rows > 0) return@repeat
            Thread.sleep(25)
        }
        assertEquals(1, rows)
    }

    /** 同图再分享不重复入库（内容指纹去重）。 */
    @Test fun sameImageDedups() = runTest {
        val d = StandardTestDispatcher(testScheduler)
        val inbox = CaptureInbox(ctx, db.captureItems(), d,
            CoroutineScope(SupervisorJob() + d), NowMillis { now })
        val src = File(ctx.filesDir, "dup_src.png").apply { writeBytes(byteArrayOf(9, 8, 7)) }
        inbox.receive(imageIntent(src))
        inbox.receive(imageIntent(src)) // 每次分享都是新 Intent
        assertEquals(1, rowCount())
    }

    /** 极端中断留下孤儿文件：重发同图应自愈——跳过复制直接补库行。 */
    @Test fun orphanFileSelfHeals() = runTest {
        val d = StandardTestDispatcher(testScheduler)
        val inbox = CaptureInbox(ctx, db.captureItems(), d,
            CoroutineScope(SupervisorJob() + d), NowMillis { now })
        val src = File(ctx.filesDir, "orphan_src.png").apply { writeBytes(byteArrayOf(5, 6)) }
        inbox.receive(imageIntent(src))
        db.testSql.execSQL("DELETE FROM capture_items") // 模拟：文件在、行没提交
        assertEquals(1, File(ctx.filesDir, "inbox").listFiles()!!.size)
        inbox.receive(imageIntent(src))
        assertEquals(1, rowCount())
        assertTrue(File(ctx.filesDir, "inbox").listFiles()!!.single().name.startsWith("cap_"))
    }

    /** spool 对账：insert 被外层事务吞掉（独立连接读不到）留下的票，对账时必须重插补行。 */
    @Test fun spoolReconcileReinserts() = runTest {
        val d = StandardTestDispatcher(testScheduler)
        val inbox = CaptureInbox(ctx, db.captureItems(), d,
            CoroutineScope(SupervisorJob() + d), NowMillis { now })
        val spool = File(ctx.filesDir, "capture-spool").apply { mkdirs() }
        File(spool, "lost.json").writeText(org.json.JSONObject()
            .put("kind", "Url").put("body", "https://lost.example/a").put("title", "lost.example")
            .put("extracted", "Saved").put("createdAt", now).put("updatedAt", now).toString())
        inbox.reconcile()
        assertEquals(1, rowCount())
        assertEquals(0, spool.listFiles()!!.size) // 票结清
        // 已有行时再对账：识别为已入库，不产生重复
        File(spool, "lost2.json").writeText(org.json.JSONObject()
            .put("kind", "Url").put("body", "https://lost.example/a").put("title", "lost.example")
            .put("extracted", "Saved").put("createdAt", now).put("updatedAt", now).toString())
        inbox.reconcile()
        assertEquals(1, rowCount())
        assertEquals(0, spool.listFiles()!!.size)
    }
}
