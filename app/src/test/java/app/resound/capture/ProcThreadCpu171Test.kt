package app.resound.capture

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class ProcThreadCpu171Test {
    private fun stat(name: String): String {
        val fields = MutableList(20) { "0" }
        fields[0] = "S"
        fields[11] = "123"
        fields[12] = "45"
        fields[19] = "98765"
        return "321 ($name) ${fields.joinToString(" ")}"
    }

    @Test fun parsesCpuAndStartTicksAfterNamesWithSpacesAndParentheses() {
        for (name in listOf("Jit thread pool", "Jit (pool) worker)")) {
            assertEquals(ProcThreadCpu.Stat(98765, 1680), ProcThreadCpu.parseStat(stat(name), 100))
            assertEquals(ProcThreadCpu.Stat(98765, 656), ProcThreadCpu.parseStat(stat(name), 256))
        }
        assertNull(ProcThreadCpu.parseStat("321 (broken) S", 100))
        assertNull(ProcThreadCpu.parseStat(stat("main"), 0))
    }

    @Test fun readsMainByTidAndReturnsNullForUnreadableOrPartialSnapshotWithoutThrowing() {
        val root = Files.createTempDirectory("thread-cpu-171").toFile()
        try {
            val task = root.resolve("321").apply { mkdirs() }
            task.resolve("stat").writeText(stat("app.resound"))
            task.resolve("comm").writeText("app.resound\n")
            assertEquals(listOf(ThreadCpuSample("321:98765", "main", 1680)), ProcThreadCpu.read(root, "321") { 100 })
            assertNull(ProcThreadCpu.read(root, "321") { error("sysconf unavailable") })
            task.resolve("comm").delete()
            assertNull(ProcThreadCpu.read(root, "321") { 100 })
            assertNull(ProcThreadCpu.read(root.resolve("missing"), "321") { 100 })
        } finally { root.deleteRecursively() }
    }
}
