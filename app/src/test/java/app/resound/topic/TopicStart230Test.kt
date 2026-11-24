package app.resound.topic

import app.resound.ui.topic.TopicStartLauncher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

/** 卡 230：开始专题后面板换成“进行中”、面板协程被取消，不能再提示“没开始成功”。 */
class TopicStart230Test {
    @Test fun `面板在开始途中被换掉，结果仍按成功回报`() = runTest {
        val panel = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val app = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val reported = CompletableDeferred<StartResult?>()
        // 旧写法：用面板自己的作用域；专题写库后面板被换掉，取消被当成失败
        val legacy = CompletableDeferred<StartResult?>()
        panel.launch {
            val r = runCatching { startThatReplacesPanel(panel) }.getOrNull()
            legacy.complete(r)
        }
        advanceUntilIdle()
        assertNull("复现旧问题：已开成功却回报失败", legacy.await())

        val panel2 = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        TopicStartLauncher.launch({ startThatReplacesPanel(panel2) }, { reported.complete(it) }, app)
        advanceUntilIdle()
        assertEquals(7L, reported.await()?.topicId)
        app.cancel()
    }

    @Test fun `真正出错仍回报失败，取消不回报`() = runTest {
        val app = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        var failed: StartResult? = StartResult(1)
        TopicStartLauncher.launch({ error("db") }, { failed = it }, app)
        advanceUntilIdle()
        assertNull(failed)

        var called = false
        val job: Job = TopicStartLauncher.launch({ kotlinx.coroutines.awaitCancellation() }, { called = true }, app)
        advanceUntilIdle(); job.cancel(); advanceUntilIdle()
        assertFalse(called)
        app.cancel()
    }

    /** 模拟仓库：写库后先发布“当前专题”（界面随即换掉面板），再做排钟等后续挂起操作 */
    private suspend fun startThatReplacesPanel(panel: CoroutineScope): StartResult {
        panel.cancel()
        yield()
        return StartResult(topicId = 7)
    }
}
