package app.resound.screentime

import android.content.Context
import android.content.pm.PackageManager

/** 按包名取系统应用标签；未安装的包名不出现在结果里，查询侧回退到包名。 */
fun appLabels(context: Context, pkgs: Set<String>): Map<String, String> {
    val pm = context.packageManager
    return buildMap {
        for (pkg in pkgs) {
            val name = try {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
            if (name != null) put(pkg, name)
        }
    }
}
