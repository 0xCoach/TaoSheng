package app.resound.capture

/** identity 由采样端提供（安卓 tid + 启动节拍），不能只用线程名或可复用的 tid。 */
data class ThreadCpuSample(val identity: String, val name: String, val cpuMs: Long)

object ThreadCpuRules {
    /** /proc comm 通常只有 15 字节；Default 与 IO 共用协程调度器，不能从名称拆开。 */
    fun group(name: String): String = when {
        name.startsWith("capture-state") -> "capture-state"
        name == "main" -> "主线程"
        name.startsWith("DefaultDispatch") -> "DefaultDispatcher/IO"
        name.startsWith("Dispatchers.IO") || name.startsWith("IO-pool") -> "IO线程池"
        name.startsWith("Audio", true) || name.startsWith("AAudio") || name.startsWith("Oboe") -> "音频"
        name.startsWith("OkHttp", true) || name.startsWith("Okio", true) -> "OkHttp"
        name.startsWith("WM.") || name.startsWith("WorkManager") || name.startsWith("androidx.work") -> "WorkManager"
        name.startsWith("arch_disk_io") || name.startsWith("Room", true) || name.startsWith("SQLite", true) -> "Room/数据库"
        name.startsWith("binder", true) || name.startsWith("HwBinder", true) -> "binder"
        listOf("HeapTaskDaemon", "Jit thread pool", "Jit worker", "FinalizerDaemon", "FinalizerWatchd",
            "ReferenceQueueD", "GC", "Signal Catcher", "Profile Saver", "Runtime worker").any(name::startsWith) -> "运行时"
        else -> "其他"
    }

    fun totals(samples: List<ThreadCpuSample>): Map<String, Long> = samples
        .groupBy { group(it.name) }.mapValues { (_, rows) -> rows.sumOf { it.cpuMs.coerceAtLeast(0) } }

    /** 先按身份相减再归组；新线程/计数重置从零起算，消失的线程没有可读尾值，不能推算。 */
    fun delta(previous: List<ThreadCpuSample>, current: List<ThreadCpuSample>): Map<String, Long> {
        val before = previous.associateBy { it.identity }
        return totals(current.map { row ->
            val old = before[row.identity]?.cpuMs
            row.copy(cpuMs = if (old != null && row.cpuMs >= old) row.cpuMs - old else row.cpuMs)
        })
    }
}
