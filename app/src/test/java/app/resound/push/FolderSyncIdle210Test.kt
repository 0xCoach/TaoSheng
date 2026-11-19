package app.resound.push

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 卡 210：没有变化时同步文件夹不连云端；出错后下次重新建目录 */
class FolderSyncIdle210Test {
    @get:Rule val temporary = TemporaryFolder()
    private class Remote : FolderStorage {
        val calls = mutableListOf<String>(); var fail = false
        override fun directories(path: String) { calls += "dir:$path" }
        override fun put(path: String, content: String, state: PushSyncState) { if (fail) error("限流"); calls += "put:$path" }
        override fun delete(path: String) { calls += "del:$path" }
    }
    @Test fun unchangedRunMakesNoRequestsAndFailureForcesDirectoryCheck() {
        val file = temporary.newFile("state.json").apply { delete() }
        val remote = Remote()
        val files = mapOf("README.md" to "一")
        FolderSync(remote, PushSyncState(file)).run(files, false, 1)
        assertEquals(listOf("dir:", "put:README.md"), remote.calls)
        remote.calls.clear()
        assertEquals(0, FolderSync(remote, PushSyncState(file)).run(files, false, 2).updated)
        assertTrue("没有变化不应连云端", remote.calls.isEmpty())
        remote.fail = true
        assertThrows(IllegalStateException::class.java) { FolderSync(remote, PushSyncState(file)).run(mapOf("README.md" to "二"), false, 3) }
        remote.fail = false; remote.calls.clear()
        FolderSync(remote, PushSyncState(file)).run(mapOf("README.md" to "二"), false, 4)
        assertEquals(listOf("dir:", "put:README.md"), remote.calls)
    }
}
