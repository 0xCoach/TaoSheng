package app.resound.update

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import app.resound.AppGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object UpdateInstallActions {
    private val engine get() = AppGraph.updater as UpdateEngine
    val systemInstallerAvailable get() = engine.systemInstallerAvailable

    suspend fun installWithSystem(activity: Activity): Boolean = engine.handToSystem { apk ->
        withContext(Dispatchers.Main) {
            check(InstallRelay.foreground === activity && !activity.isFinishing)
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", apk)
            activity.startActivity(Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }
    }

    val showXiaomiInstallHint: Boolean by lazy {
        fun property(key: String) = runCatching {
            Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
                .invoke(null, key) as String
        }.getOrNull()
        !property("ro.mi.os.version.name").isNullOrBlank() || !property("ro.miui.ui.version.name").isNullOrBlank() ||
            Build.MANUFACTURER.lowercase() in setOf("xiaomi", "redmi", "poco")
    }

    fun openXiaomiPermissions(activity: Activity) = openSettings(activity,
        Intent("miui.intent.action.APP_PERM_EDITOR").setPackage("com.miui.securitycenter")
            .putExtra("extra_pkgname", activity.packageName))

    fun openUnknownApps(activity: Activity) = openSettings(activity,
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))

    private fun openSettings(activity: Activity, intent: Intent): Boolean =
        runCatching { activity.startActivity(intent) }.isSuccess || runCatching {
            activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}")))
        }.isSuccess
}
