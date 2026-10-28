package app.resound.diagnostics

import android.content.Context
import android.os.Build
import app.resound.capture.CaptureStatsRepository
import app.resound.update.AppUpdater
import app.resound.update.UpdateDiagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal class LocalProblemReports(
    private val context: Context,
    private val crashes: CrashRecords,
    private val stats: CaptureStatsRepository,
    private val updater: AppUpdater,
) : ProblemReports {
    private val prefs = context.getSharedPreferences("problem-reports", Context.MODE_PRIVATE)
    private val unseen = MutableStateFlow(crashes.latestAt()?.takeIf { it > prefs.getLong("seenCrashAt", 0) })
    override val lastCrashAt = unseen.asStateFlow()
    override fun dismissCrashNotice() {
        unseen.value?.let { check(prefs.edit().putLong("seenCrashAt", it).commit()) }
        unseen.value = null
    }
    override suspend fun export(): File = withContext(Dispatchers.IO) {
        @Suppress("DEPRECATION") val installed = context.packageManager.getPackageInfo(context.packageName, 0)
        val at = Instant.now()
        val diagnostics = stats.exportDiagnostics()
        val content = try {
            buildString {
                appendLine("涛声问题记录")
                appendLine("版本名=${installed.versionName} versionCode=${installed.longVersionCode}")
                appendLine("是否预览版=${updater.isPreviewBuild} 预览通道=${updater.previewChannel.value}")
                appendLine("手机型号=${Build.MANUFACTURER} ${Build.MODEL} 安卓版本=${Build.VERSION.RELEASE} API=${Build.VERSION.SDK_INT}")
                appendLine("生成时间=$at")
                appendLine("\n最近闪退（最多 10 条）"); appendLine(crashes.dump())
                appendLine("\n运行诊断"); appendLine(diagnostics.readText())
                appendLine("\n更新诊断（最近 50 行）")
                appendLine(UpdateDiagnostics.dump(context).lines().takeLast(50).joinToString("\n"))
            }
        } finally { diagnostics.delete() }
        val directory = File(context.cacheDir, "problem-reports").apply { check(isDirectory || mkdirs()) }
        val stamp = DateTimeFormatter.ofPattern("MMdd-HHmmss-SSS").withZone(ZoneId.systemDefault()).format(at)
        val file = File(directory, "涛声问题记录-${updater.currentVersion}-$stamp.txt")
        file.writeText(scrubProblemReport(content))
        file
    }
}

/** 宁可遮掉看似凭证的长标识，也不把密钥交给分享目标。 */
internal fun scrubProblemReport(text: String): String = text
    .replace(Regex("sk-[A-Za-z0-9_-]+"), "[密钥已隐藏]")
    .replace(Regex("[A-Za-z0-9+/_-]{32,}={0,2}"), "[长串已隐藏]")
