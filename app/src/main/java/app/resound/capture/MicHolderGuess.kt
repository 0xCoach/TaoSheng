package app.resound.capture

import android.Manifest
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.provider.Settings

/**
 * 系统不告诉普通 App 是谁在用麦克风，只能猜：当前前台应用、当前输入法里，有麦克风权限的那几个。
 * 前台应用要用户打开“使用情况访问”才看得到；没打开时只猜输入法。结果一律写“可能是”。
 */
class MicHolderGuess(private val context: Context) {
    private val pm = context.packageManager

    fun permitted(): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java)
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
    }

    fun openSettings() {
        val direct = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${context.packageName}"))
        try { context.startActivity(direct.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (_: RuntimeException) { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** 例如“可能是 小红书 或 讯飞输入法（语音输入）”；猜不出返回 null */
    fun describe(): String? = runCatching {
        val names = listOfNotNull(foreground()?.let(::label), inputMethod()?.let { label(it)?.let { name -> "$name（语音输入）" } }).distinct()
        MicHolderText.describe(names)
    }.getOrNull()

    private fun usable(pkg: String) = pkg != context.packageName &&
        pm.checkPermission(Manifest.permission.RECORD_AUDIO, pkg) == PackageManager.PERMISSION_GRANTED

    private fun foreground(): String? {
        if (!permitted()) return null
        val now = System.currentTimeMillis()
        val events = context.getSystemService(UsageStatsManager::class.java).queryEvents(now - 10 * 60_000, now)
        val event = UsageEvents.Event()
        var last: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) last = event.packageName
        }
        return last?.takeIf(::usable)
    }

    private fun inputMethod(): String? =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.substringBefore('/')?.takeIf { it.isNotBlank() && usable(it) }

    private fun label(pkg: String): String? = runCatching {
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrNull()
}

internal object MicHolderText {
    fun describe(names: List<String>): String? = if (names.isEmpty()) null else "可能是 " + names.joinToString(" 或 ")
}
