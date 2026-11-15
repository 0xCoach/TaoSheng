package app.resound.transcribe

import android.content.Context
import android.os.Build
import app.resound.scheduling.BackgroundSystem

internal object BackgroundSystemInfo {
    fun read(context: Context): BackgroundSystem.Signals {
        fun property(key: String) = runCatching {
            Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, key) as String
        }.getOrDefault("")
        val brand = runCatching { Class.forName("com.huawei.system.BuildEx").getMethod("getOsBrand").invoke(null) as String }.getOrDefault("")
        val installer = runCatching {
            if (Build.VERSION.SDK_INT >= 30) context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
            else { @Suppress("DEPRECATION") context.packageManager.getInstallerPackageName(context.packageName) }
        }.getOrNull().orEmpty()
        return BackgroundSystem.Signals(Build.MANUFACTURER, Build.BRAND, property("hw_sc.build.platform.version"),
            property("ro.build.version.emui"), property("ro.build.version.magic"), brand, installer)
    }
}
