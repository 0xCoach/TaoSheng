package app.resound.push

import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings

/**
 * 强提醒要靠系统放行几样东西。能查到的直接查（打勾）；查不到的（各家手机自己的开关）标“请确认”，给一个跳转。
 * 跳转都有兜底：专门的设置页打不开，就打开涛声的“应用信息”页。
 */
internal object StrongSetup {
    /** [ok] 为 null 表示这台手机查不到，只能请用户自己确认 */
    data class Item(val title: String, val why: String, val ok: Boolean?, val open: (Context) -> Unit)

    fun items(context: Context): List<Item> {
        val nm = context.getSystemService(NotificationManager::class.java)
        val pkg = context.packageName
        val channel = nm.getNotificationChannel(LocalNotifier.TODO_CHANNEL)
        val list = mutableListOf<Item>()
        list += Item("允许通知", "关了就收不到任何提醒", nm.areNotificationsEnabled()) { open(it, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg)) }
        list += Item("待办提醒：悬浮、锁屏、声音、振动", "有的手机会把新通知默认静音、不弹出，请逐项打开",
            if (channel == null || channel.importance < NotificationManager.IMPORTANCE_HIGH) false else if (vendor() != null) null else true) {
            open(it, Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
                .putExtra(Settings.EXTRA_CHANNEL_ID, LocalNotifier.TODO_CHANNEL))
        }
        if (Build.VERSION.SDK_INT >= 34) list += Item("全屏提醒", "锁屏时像闹钟一样亮屏显示", nm.canUseFullScreenIntent()) {
            open(it, Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$pkg")))
        }
        if (Build.VERSION.SDK_INT >= 31) list += Item("闹钟与提醒", "准点响，不晚几分钟",
            context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()) {
            open(it, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$pkg")))
        }
        list += Item("不限制后台耗电", "省电模式下也能按时响",
            context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(pkg)) {
            open(it, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$pkg")))
        }
        vendor()?.let { brand ->
            list += Item("锁屏显示、后台弹出界面", "${brand}手机需要这两项，整屏提醒才能盖在锁屏上", miuiLockScreenOk(context)) { openVendorPermissions(it) }
        }
        return list
    }

    /** 还差几项（查得到又没开的） */
    fun missing(context: Context) = items(context).count { it.ok == false }

    /** 需要额外权限的几家；其余按原生安卓处理 */
    private fun vendor(): String? = when (Build.MANUFACTURER.lowercase()) {
        "xiaomi", "redmi", "poco" -> "小米"
        "oppo", "realme", "oneplus" -> "OPPO"
        "vivo", "iqoo" -> "vivo"
        "huawei", "honor" -> "华为"
        else -> null
    }

    /** 小米的“锁屏显示”(10020)、“后台弹出界面”(10021)；读不到返回 null */
    private fun miuiLockScreenOk(context: Context): Boolean? {
        if (vendor() != "小米") return null
        return runCatching {
            val ops = context.getSystemService(AppOpsManager::class.java)
            val check = AppOpsManager::class.java.getMethod("checkOpNoThrow", Int::class.java, Int::class.java, String::class.java)
            listOf(10020, 10021).all { check.invoke(ops, it, Process.myUid(), context.packageName) as Int == AppOpsManager.MODE_ALLOWED }
        }.getOrNull()
    }

    private fun openVendorPermissions(context: Context) {
        val pkg = context.packageName
        val tries = listOf(
            Intent("miui.intent.action.APP_PERM_EDITOR").putExtra("extra_pkgname", pkg),
            Intent().setClassName("com.coloros.safecenter", "com.coloros.safecenter.permission.PermissionManagerActivity"),
            Intent().setClassName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.SoftPermissionDetailActivity").putExtra("packagename", pkg),
            Intent().setClassName("com.huawei.systemmanager", "com.huawei.permissionmanager.ui.MainActivity"),
        )
        if (tries.none { runCatching { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess }) appDetails(context)
    }

    private fun open(context: Context, intent: Intent) {
        if (runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isFailure) appDetails(context)
    }

    private fun appDetails(context: Context) = runCatching {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
