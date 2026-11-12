package app.resound.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class ThreadCpuRules171Test {
    @Test fun groupsCaptureMainAudioNetworkDatabaseAndRuntime() {
        val names = mapOf("capture-state" to "capture-state", "main" to "主线程", "AudioRecord" to "音频",
            "OkHttp Dispatch" to "OkHttp", "WM.task-1" to "WorkManager", "arch_disk_io_2" to "Room/数据库",
            "binder:123_1" to "binder", "HeapTaskDaemon" to "运行时", "Jit thread pool" to "运行时",
            "FinalizerDaemon" to "运行时", "FinalizerWatchd" to "运行时", "ReferenceQueueD" to "运行时",
            "IO-pool-2" to "IO线程池", "custom private name" to "其他")
        names.forEach { (name, expected) -> assertEquals(name, expected, ThreadCpuRules.group(name)) }
    }

    @Test fun poolsAndKernelTruncatedNamesMergeWithoutRetainingPrivateNames() {
        val rows = listOf("DefaultDispatcher-worker-1", "DefaultDispatcher-worker-28", "DefaultDispatch",
            "OkHttp api.example.com", "OkHttp TaskRunner", "pool-2-thread-1", "pool-3-thread-1")
            .mapIndexed { i, name -> ThreadCpuSample("$i", name, 10) }
        assertEquals(mapOf("DefaultDispatcher/IO" to 30L, "OkHttp" to 20L, "其他" to 20L), ThreadCpuRules.totals(rows))
    }

    @Test fun deltaHandlesSurvivorExitNewThreadResetAndReusedTidBeforeGrouping() {
        val previous = listOf(ThreadCpuSample("1:10", "capture-state", 100),
            ThreadCpuSample("2:10", "DefaultDispatch", 900), ThreadCpuSample("3:10", "DefaultDispatch", 500),
            ThreadCpuSample("4:10", "OkHttp", 600))
        val current = listOf(ThreadCpuSample("1:10", "capture-state", 140),
            ThreadCpuSample("3:10", "DefaultDispatch", 20), ThreadCpuSample("5:20", "DefaultDispatch", 30),
            ThreadCpuSample("4:20", "OkHttp", 700))
        assertEquals(mapOf("capture-state" to 40L, "DefaultDispatcher/IO" to 50L, "OkHttp" to 700L),
            ThreadCpuRules.delta(previous, current))
        assertEquals(emptyMap<String, Long>(), ThreadCpuRules.delta(previous, emptyList()))
    }

    @Test fun renameDoesNotCountLifetimeAgainAndNegativeInputCannotProduceNegativeDelta() {
        val previous = listOf(ThreadCpuSample("1:10", "其他", 100))
        assertEquals(mapOf("OkHttp" to 20L, "其他" to 0L), ThreadCpuRules.delta(previous,
            listOf(ThreadCpuSample("1:10", "OkHttp", 120), ThreadCpuSample("2:10", "private", -5))))
    }

    @Test fun topOthersMergesNumberedThreadsAndRanksByDelta() {
        val previous = listOf(ThreadCpuSample("1", "pool-2-thread-1", 100), ThreadCpuSample("2", "onnx-worker", 0))
        val current = listOf(ThreadCpuSample("1", "pool-2-thread-1", 130), ThreadCpuSample("3", "pool-3-thread-1", 20),
            ThreadCpuSample("2", "onnx-worker", 400), ThreadCpuSample("4", "OkHttp api.example.com", 999))
        assertEquals(listOf("onnx-worker" to 400L, "pool--thread" to 50L), ThreadCpuRules.topOthers(previous, current))
    }
}
