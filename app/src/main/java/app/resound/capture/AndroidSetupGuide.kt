package app.resound.capture

import app.resound.diagnostics.FailureNotes

import app.resound.scheduling.BackgroundSystem
import app.resound.transcribe.BackgroundSystemInfo
import app.resound.transcribe.PipelineTimes

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
    private val signals = BackgroundSystemInfo.read(context)
    private val system = BackgroundSystem.identify(signals)
    private val prefs = context.getSharedPreferences("setup", Context.MODE_PRIVATE)
    override fun items(): List<SetupItem> {
        fun manual(key: String): Boolean? = if (prefs.getBoolean(key, false)) true else null
        // 自启动、省电策略只有小米系（MIUI / 澎湃）有直达页面，别的品牌不显示，免得点进去打不开
        val xiaomi = Build.MANUFACTURER.lowercase() in setOf("xiaomi", "redmi", "poco")
        val huawei = system != BackgroundSystem.Type.Android
        val container = system == BackgroundSystem.Type.Container
        return listOfNotNull(
            SetupItem("mic", "麦克风权限", "在应用权限里允许使用麦克风。", ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED),
            SetupItem("notify", "通知权限", "允许通知，并允许录音中断提醒显示。", NotificationManagerCompat.from(context).areNotificationsEnabled() && (Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)),
            SetupItem("battery", "忽略电池优化", "允许${context.appName}不受电池优化限制。", context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)),
            if (!xiaomi) null else SetupItem("autostart", "小米自启动", "在安全中心为${context.appName}开启自启动；系统不提供可靠查询，设置后请手动确认。", manual("autostart")),
            if (!xiaomi) null else SetupItem("powerkeeper", "省电策略：无限制", "将${context.appName}的省电策略设为无限制，设置后请手动确认。", manual("powerkeeper")),
            if (!huawei) null else SetupItem("huawei_startup", "应用启动管理：改成手动", "允许自启动、允许关联启动、允许后台活动三项全开；不同版本叫法略有不同，设置后请手动确认。", manual("huawei_startup")),
            if (!huawei) null else SetupItem("huawei_sleep_network", "休眠时保持网络连接", "设置 → 电池 → 更多电池设置；部分系统版本已取消此开关，设置后请手动确认。", manual("huawei_sleep_network")),
            if (!container) null else SetupItem("anco_host", "给卓易通本身也改成手动管理", "请在鸿蒙系统设置里检查卓易通，而不是涛声；HarmonyOS 5及以上部分版本已取消启动管理，找不到时不要照搬旧版路径。设置后请手动确认。", manual("anco_host")),
            SetupItem("lock", "最近任务锁定", "打开最近任务，长按${context.appName}卡片并点锁定（不同手机叫法略有不同）；设置后请手动确认。", manual("lock")))
    }
    override fun open(key: String) {
        if (key == "lock") return // 最近任务锁定只有操作说明，没有可靠直达页面。
        if (key in setOf("huawei_startup", "huawei_sleep_network", "anco_host")) {
            if (system == BackgroundSystem.Type.Container) {
                PipelineTimes.record(context, "后台设置 $key：容器仅显示宿主操作说明")
                return
            }
            val vendor = if (system == BackgroundSystem.Type.Honor) "com.hihonor.systemmanager" else "com.huawei.systemmanager"
            val targets = if (key == "huawei_sleep_network") listOf("battery-settings", "details") else if (system == BackgroundSystem.Type.Honor) listOf(
                "$vendor/.startupmgr.ui.StartupNormalAppListActivity", "$vendor/.startupmgr.ui.StartupAppListActivity", "details") else listOf(
                "$vendor/.startupmgr.ui.StartupNormalAppListActivity",
                "$vendor/.appcontrol.activity.StartupAppControlActivity",
                "$vendor/.optimize.process.ProtectActivity", "details")
            BackgroundSystem.openFirst(targets) { target ->
                val intent = when (target) {
                    "details" -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                    "battery-settings" -> Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
                    else -> Intent().setComponent(ComponentName.unflattenFromString(target))
                }
                val result = runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure {
                    FailureNotes.record("capture/AndroidSetupGuide.kt:61", it)
                }
                PipelineTimes.record(context, "后台设置 $key 跳转=$target 结果=${if (result.isSuccess) "已打开" else "失败"}")
                result.isSuccess
            }
            return
        }
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
        catch (failure190: RuntimeException) {
            FailureNotes.record("capture/AndroidSetupGuide.kt:77", failure190)
            runCatching { context.startActivity(details.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure {
            FailureNotes.record("capture/AndroidSetupGuide.kt:77", it)
        } }
    }
    override fun markDone(key: String) {
        if (key in setOf("autostart", "powerkeeper", "lock", "huawei_startup", "huawei_sleep_network", "anco_host")) prefs.edit().putBoolean(key, true).apply()
    }
}
