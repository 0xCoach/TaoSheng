package app.resound.capture

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.util.AtomicFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** 沿用 PipelineTimes 的最近 N 行滚动；文件与转写省电诊断一样放 noBackupFilesDir。 */
internal class ThreadCpuDiagnostics {
    private val mutex = Mutex()
    private var previous: List<ThreadCpuSample>? = null
    private var previousAt: Long? = null

    suspend fun record(context: Context, charging: Boolean?, state: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                val at = SystemClock.elapsedRealtime()
                val wall = System.currentTimeMillis()
                val interactive = runCatching { context.getSystemService(PowerManager::class.java).isInteractive }.getOrNull()
                val current = ProcThreadCpu.read()
                val elapsed = previousAt?.let { at - it }?.takeIf { it > 0 }
                val delta = if (elapsed != null && current != null) previous?.let { ThreadCpuRules.delta(it, current) } else null
                // 失败后下一次重新建基线，避免将线程历史累计值误记为一个采样区间。
                previous = current
                previousAt = at
                val status = when { current == null -> "unavailable"; delta == null -> "baseline"; else -> "ok" }
                val line = listOf(wall, charging ?: "", interactive ?: "", state, elapsed ?: "",
                    delta?.entries?.joinToString(";") { "${it.key}=${it.value}" }.orEmpty(), status).joinToString("\t")
                append(context, line)
            }.onFailure {
                previous = null
                previousAt = null
                if (it is CancellationException) throw it
            }
        }
        Unit
    }

    companion object {
        private val fileMutex = Mutex()
        private const val MAX_LINES = 1008 // 10 分钟一次约 7 天；启停额外采样也占行。
        private fun file(context: Context) = AtomicFile(File(context.noBackupFilesDir, "thread-cpu.tsv"))
        private fun rows(file: AtomicFile): List<String> =
            if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) emptyList()
            else file.openRead().bufferedReader().use { it.readLines() }

        private suspend fun append(context: Context, line: String) = fileMutex.withLock {
            val file = file(context)
            val content = (rows(file).filter { it.isNotBlank() } + line).takeLast(MAX_LINES).joinToString("\n", postfix = "\n")
            val output = file.startWrite()
            try {
                output.write(content.toByteArray(Charsets.UTF_8))
                file.finishWrite(output)
            } catch (e: Throwable) { file.failWrite(output); throw e }
        }

        suspend fun dump(context: Context): String = withContext(Dispatchers.IO) { fileMutex.withLock { runCatching {
            "时间戳ms\t充电\t亮屏\t录音状态\t距上次ms\t各组CPU增量ms\t采样状态\n" +
                rows(file(context)).takeLast(MAX_LINES).joinToString("\n")
        }.getOrDefault("线程CPU诊断读取失败") } }
    }
}
