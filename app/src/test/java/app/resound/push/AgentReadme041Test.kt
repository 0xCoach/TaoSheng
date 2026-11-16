package app.resound.push

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.app.Application
import java.time.LocalDate
import java.time.ZoneId

/** 0.4.1“复制给智能体”：README 要讲清怎么按日期找、只读、不外传，并且不能因缩进变成代码块 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class AgentReadme041Test {
    private val date = LocalDate.of(2026, 11, 16)
    private val zone = ZoneId.of("Asia/Shanghai")

    private fun readme(s: PushSubscription) = runBlocking { MemoryFolder(date, zone).generate(PushSnapshot(), s).getValue("README.md") }

    @Test fun readmeTellsAgentHowToReadAndNotToWrite() {
        listOf(PushSubscription.ForAgents, PushSubscription.ForAgents.copy(layout = FolderLayout.ByDay)).forEach { s ->
            val text = readme(s)
            assertTrue(text.contains("## 智能体读取须知"))
            assertTrue(text.contains(MemoryPaths.daily(kotlinx.datetime.LocalDate(2026, 11, 16))))
            assertTrue(text.contains("北京时间") && text.contains("只读") && text.contains("不要转发"))
            assertFalse("README 里不能有缩进成代码块的行", text.lines().any { it.startsWith("    ") })
        }
    }
}
