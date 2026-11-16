package app.resound.notice

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** 内容被改过（签名对不上）的缓存一律不用 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
class RemoteNoticesTamperTest {
    @Test fun tamperedCacheIsRejected() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dir = File(ctx.noBackupFilesDir, "notices").apply { deleteRecursively(); mkdirs() }
        for (name in listOf("notices.json", "notices.json.sig")) {
            val bytes = javaClass.classLoader!!.getResourceAsStream("notices/$name")!!.readBytes()
            File(dir, name).writeBytes(if (name == "notices.json") bytes.decodeToString().replace("交流", "聊聊").encodeToByteArray() else bytes)
        }
        assertNull(RemoteNotices.get(ctx).file.value)
    }
}
