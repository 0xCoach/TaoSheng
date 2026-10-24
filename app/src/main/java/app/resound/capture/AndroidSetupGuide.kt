package app.resound.capture

import app.resound.appName
import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

class AndroidSetupGuide(private val context: Context) : SetupGuide {
    private val prefs = context.getSharedPreferences("setup", Context.MODE_PRIVATE)
    override fun items(): List<SetupItem> {
        fun manual(key: String): Boolean? = if (prefs.getBoolean(key, false)) true else null
        return listOf(
            SetupItem("mic", "麦克风权限", "在应用权限里允许使用麦克风。", ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED),
            SetupItem("notify", "通知权限", "允许通知，并允许录音中断提醒显示。", NotificationManagerCompat.from(context).areNotificationsEnabled() && (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)),
            SetupItem("battery", "忽略电池优化", "允许${context.appName}不受电池优化限制。", context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)),
            SetupItem("autostart", "小米自启动", "在安全中心为${context.appName}开启自启动；系统不提供可靠查询，设置后请手动确认。", manual("autostart")),
            SetupItem("powerkeeper", "省电策略：无限制", "将${context.appName}的省电策略设为无限制，设置后请手动确认。", manual("powerkeeper")),
            SetupItem("lock", "最近任务锁定", "打开最近任务，长按${context.appName}卡片并点锁定；设置后请手动确认。", manual("lock")))
    }
    override fun open(key: String) {
        if (key == "lock") return // 最近任务锁定只有操作说明，没有可靠直达页面。
        val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        val target = when (key) {
            "autostart" -> Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"))
            "powerkeeper" -> Intent().setComponent(ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"))
                .putExtra("package_name", context.packageName).putExtra("package_label", context.appName)
            "battery" -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
            "notify" -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            else -> details
        }
        try { context.startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (_: RuntimeException) { context.startActivity(details.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
    override fun markDone(key: String) {
        if (key in setOf("autostart", "powerkeeper", "lock")) prefs.edit().putBoolean(key, true).apply()
    }
}
