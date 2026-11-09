/*
 * [INPUT]: Android 版本、实时权限授予与蓝牙开关。
 * [OUTPUT]: 提供 BLE 的按需权限集合和检查。
 * [POS]: 界面申请与服务执行共用，避免缓存撤销后的权限。
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 */
package app.resound.sonicloud

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object RecorderPermissions {
    fun scanning(): Array<String> = if (Build.VERSION.SDK_INT >= 31)
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    fun connected(context: Context): Boolean = Build.VERSION.SDK_INT < 31 ||
        granted(context, Manifest.permission.BLUETOOTH_CONNECT)
    fun canScan(context: Context): Boolean = scanning().all { granted(context, it) }
    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
