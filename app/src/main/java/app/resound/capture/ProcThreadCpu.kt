package app.resound.capture

import android.system.Os
import android.system.OsConstants
import java.io.File

/** 安卓适配层；只有本进程，不含 :local_asr 子进程。调用方须在 IO 调度器执行。 */
internal object ProcThreadCpu {
    internal data class Stat(val startTicks: Long, val cpuMs: Long)

    internal fun parseStat(text: String, ticksPerSecond: Long): Stat? = runCatching {
        require(ticksPerSecond > 0)
        // comm 可含空格和括号，最后一个右括号之后才是从字段 3 开始的数字/状态字段。
        val end = text.lastIndexOf(')')
        require(text.indexOf('(') >= 0 && end > text.indexOf('('))
        val fields = text.substring(end + 1).trim().split(Regex("\\s+"))
        val user = fields[11].toLong() // stat 字段 14 utime
        val system = fields[12].toLong() // 字段 15 stime
        val start = fields[19].toLong() // 字段 22 starttime，防止 tid 复用
        require(user >= 0 && system >= 0 && start >= 0 && user <= Long.MAX_VALUE - system)
        val ticks = user + system
        val seconds = ticks / ticksPerSecond
        require(seconds <= Long.MAX_VALUE / 1000 && ticksPerSecond <= Long.MAX_VALUE / 1000)
        val remainderMs = ticks % ticksPerSecond * 1000 / ticksPerSecond
        require(seconds * 1000 <= Long.MAX_VALUE - remainderMs)
        Stat(start, seconds * 1000 + remainderMs)
    }.getOrNull()

    internal fun read(
        tasks: File,
        mainTid: String,
        ticksPerSecond: () -> Long,
    ): List<ThreadCpuSample>? = runCatching {
        val ticks = ticksPerSecond()
        require(ticks > 0)
        val directories = requireNotNull(tasks.listFiles()).filter { it.name.toLongOrNull() != null }
        require(directories.isNotEmpty())
        // 列目录和读文件之间线程可能刚好退出：只跳过那一条，不为它丢掉整次采样。
        directories.mapNotNull { task -> runCatching {
            val stat = requireNotNull(parseStat(File(task, "stat").readText(), ticks))
            val name = File(task, "comm").readText().trimEnd('\n', '\r')
            require(name.isNotEmpty())
            ThreadCpuSample("${task.name}:${stat.startTicks}", if (task.name == mainTid) "main" else name, stat.cpuMs)
        }.getOrNull() }.also { require(it.isNotEmpty()) }
    }.getOrNull()

    fun read(): List<ThreadCpuSample>? = read(File("/proc/self/task"), android.os.Process.myPid().toString()) {
        Os.sysconf(OsConstants._SC_CLK_TCK)
    }
}
