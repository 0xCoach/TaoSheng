package app.resound.update

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 卡 210：例行检查不能把下载/安装步骤从更新诊断里挤掉 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class UpdateDiagnostics210Test {
    @Test fun routineChecksDoNotEvictInstallSteps() {
        val context = RuntimeEnvironment.getApplication()
        UpdateDiagnostics.record(context, "install session=7 result=pending_user_action")
        repeat(200) { UpdateDiagnostics.record(context, "check source=modelscope code=86 ms=200 result=ok") }
        UpdateDiagnostics.record(context, "check source=modelscope code=86 ms=200 failure=timeout")
        val lines = UpdateDiagnostics.dump(context).lines()
        assertTrue(lines.any { it.contains("install session=7") })
        assertTrue(lines.any { it.contains("failure=timeout") })
        assertEquals(6, lines.count { it.endsWith("result=ok") })
    }
}
