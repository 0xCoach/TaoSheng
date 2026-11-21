package app.resound.analysis

import android.app.Application
import androidx.room.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import app.resound.data.CaptureDatabase
import app.resound.platform.NowMillis
import app.resound.platform.ZoneProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 报告幂等：在途运行不重复发起、失败/卡死不伪装成功、重开报告不产生新调用。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class AnalysisRunDedupTest {
    private lateinit var db: CaptureDatabase
    private lateinit var repo: AnalysisRepository
    private val now = 2_000_000_000_000L

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), CaptureDatabase::class.java)
            .setDriver(AndroidSQLiteDriver()).allowMainThreadQueries().build()
        // 凭据为空、chat 永不被调：生成的运行会落 Failed
        repo = AnalysisRepository(db, CoroutineScope(Dispatchers.Default), NowMillis { now },
            ZoneProvider { "UTC" }, Dispatchers.Unconfined,
            { app.resound.digest.LlmPreset("", "", "", "") to "" }, { false }, { false },
            { _, _, _, _ -> error("不应被调用") })
    }
    @After fun close() = db.close()

    private suspend fun project() =
        db.projects().insert(app.resound.projects.ProjectRow(name = "声声不息", createdAt = now, updatedAt = now))

    @Test fun startReturnsExistingActiveRunInsteadOfNewCall() = runBlocking {
        val pid = project()
        // 已有一条在途 Pending：再点生成应直接返回它，不新增运行
        val pending = db.analysis().insertRun(AnalysisRunRow(targetKind = "Project", targetId = pid,
            type = "PhaseReport", status = "Pending", createdAt = now, projectId = pid))
        val got = repo.start(AnalysisTarget.Project, pid, AnalysisType.PhaseReport, projectId = pid)
        assertEquals(pending, got)
    }

    @Test fun staleRunningMarkedFailedOnRecover() = runBlocking {
        val pid = project()
        val rid = db.analysis().insertRun(AnalysisRunRow(targetKind = "Project", targetId = pid,
            type = "PhaseReport", status = "Running", createdAt = now, projectId = pid))
        repo.recoverStaleRunning()
        val r = db.analysis().run(rid)!!
        assertEquals("Failed", r.status)
        assertNotNull(r.finishedAt)
    }

    @Test fun failedRunKeepsMaterialAndDoesNotLookSuccessful() = runBlocking {
        val pid = project()
        // 无凭据直接 start：运行落 Failed、材料仍在、finishedAt 已写
        val rid = repo.start(AnalysisTarget.Project, pid, AnalysisType.PhaseReport, projectId = pid)
        // execute 在 Default scope 上异步跑，等它落终态
        var r = db.analysis().run(rid)
        val deadline = System.currentTimeMillis() + 5_000
        while (r != null && r.status in setOf("Pending", "Running") && System.currentTimeMillis() < deadline) {
            Thread.sleep(20); r = db.analysis().run(rid)
        }
        assertEquals("Failed", r!!.status)
        assertTrue(r.promptText.contains("声声不息"))
        assertNotNull(r.error)
        // 历史保留：再来一次 rerun 是新行，不覆盖旧记录
        val rid2 = repo.rerun(rid)
        assertTrue(rid2 != rid && rid2 != 0L)
        assertEquals("Failed", db.analysis().run(rid)!!.status)
    }
}
