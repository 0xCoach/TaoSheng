package app.resound.audio

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File

/**
 * 录音编码 Opus/OGG 为主、AAC/M4A 为备。
 * 小米系统的 OggWriter 曾在写入中原生崩溃（10-25），整个进程直接没了，编码器里的异常回退来不及执行。
 * 所以写 OGG 前留一个标记、写完清掉；下次启动发现标记还在、且上次是原生崩溃，就改用 AAC [PAUSE_MS]，到期再试回 Opus。
 */
object OggGuard {
    const val PAUSE_MS = 86_400_000L
    private var dir: File? = null
    private var prefs: android.content.SharedPreferences? = null
    @Volatile private var pausedUntil = 0L

    fun init(context: Context) {
        val markers = File(context.noBackupFilesDir, "ogg-writing").apply { mkdirs() }
        val store = context.getSharedPreferences("segment-codec", Context.MODE_PRIVATE)
        val leftover = markers.listFiles().orEmpty().isNotEmpty()
        // 安卓 11 起能查上次进程怎么没的；安卓 10 查不到，只凭标记判断
        val nativeCrash = if (Build.VERSION.SDK_INT < 30) null else runCatching {
            context.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(context.packageName, 0, 10)
                .firstOrNull { it.processName == context.packageName }?.reason == ApplicationExitInfo.REASON_CRASH_NATIVE
        }.getOrNull()
        val now = System.currentTimeMillis()
        val old = store.getLong("oggPausedUntil", 0)
        val next = decide(leftover, nativeCrash, now, old)
        if (next != old) store.edit().putLong("oggPausedUntil", next).putLong("oggCrashAt", now).apply()
        markers.listFiles()?.forEach { it.delete() }
        dir = markers; prefs = store; pausedUntil = next
    }

    /** 留有标记且上次确为原生崩溃（或查不到原因）才切到备用；其余情况保持原状 */
    internal fun decide(leftover: Boolean, nativeCrash: Boolean?, now: Long, current: Long): Long =
        if (leftover && nativeCrash != false) now + PAUSE_MS else current

    fun oggAllowed(now: Long = System.currentTimeMillis()) = now >= pausedUntil

    /** 包住一次 OGG 写入：进程在里面崩掉时标记会留下 */
    fun <T> writing(block: () -> T): T {
        val marker = dir?.let { File(it, "${Thread.currentThread().id}-${System.nanoTime()}") }?.also { runCatching { it.createNewFile() } }
        try { return block() } finally { marker?.delete() }
    }

    fun describe(now: Long = System.currentTimeMillis()): String {
        val crashAt = prefs?.getLong("oggCrashAt", 0) ?: 0
        return if (oggAllowed(now)) "录音编码：Opus（主）" + (if (crashAt > 0) "；上次 OGG 写入崩溃 $crashAt，已恢复" else "")
        else "录音编码：AAC（备用），因 $crashAt OGG 写入崩溃，暂用到 $pausedUntil"
    }
}
