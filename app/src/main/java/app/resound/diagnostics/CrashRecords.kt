package app.resound.diagnostics

import android.content.Context
import android.os.Process
import java.io.File
import java.util.UUID

/** 不调用 Throwable.toString/printStackTrace，避免 message、文件名或自定义线程名泄露内容。 */
internal class CrashRecords(context: Context) {
    private val directory = File(context.filesDir, "crash")
    @Suppress("DEPRECATION")
    private val version = context.packageManager.getPackageInfo(context.packageName, 0).let {
        "versionName=${it.versionName} versionCode=${it.longVersionCode}"
    }
    fun installHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(handler(previous))
    }
    internal fun handler(previous: Thread.UncaughtExceptionHandler?) = Thread.UncaughtExceptionHandler { thread, error ->
        try { runCatching { record(thread, error) } }
        finally {
            if (previous != null) previous.uncaughtException(thread, error)
            else { Process.killProcess(Process.myPid()); kotlin.system.exitProcess(10) }
        }
    }
    @Synchronized internal fun record(thread: Thread, error: Throwable, at: Long = System.currentTimeMillis()) {
        check(directory.isDirectory || directory.mkdirs())
        val text = buildString {
            appendLine("at=$at $version")
            val safeName = thread.name.takeIf { it.matches(Regex("main|Thread-[0-9]+|DefaultDispatcher-worker-[0-9]+|pool-[0-9]+-thread-[0-9]+|WM.task-[0-9]+")) }
            appendLine("thread=${safeName ?: "自定义线程"} id=${thread.id}")
            val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
            fun trace(value: Throwable, depth: Int) {
                if (depth >= 8 || !seen.add(value)) return
                appendLine("exception=${value.javaClass.name}")
                value.stackTrace.take(128).forEach { appendLine("  ${it.className}:${it.lineNumber}") }
                value.cause?.let { appendLine("cause:"); trace(it, depth + 1) }
                value.suppressed.take(4).forEach { appendLine("suppressed:"); trace(it, depth + 1) }
            }
            trace(error, 0)
        }
        val file = File(directory, "%019d-%s.txt".format(at, UUID.randomUUID()))
        val stage = File(directory, file.name + ".part")
        try {
            stage.outputStream().use { it.write(text.toByteArray(Charsets.UTF_8)); it.fd.sync() }
            check(stage.renameTo(file))
            files().dropLast(10).forEach { check(it.delete()) }
        } finally { stage.delete() }
    }
    @Synchronized fun files(): List<File> = directory.listFiles().orEmpty()
        .filter { it.isFile && it.extension == "txt" }.sortedBy { it.name }
    fun latestAt(): Long? = files().lastOrNull()?.name?.substringBefore('-')?.toLongOrNull()
    fun dump(): String = files().takeLast(10).joinToString("\n") { it.readText() }
}
