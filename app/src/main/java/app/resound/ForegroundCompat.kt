package app.resound

import android.app.Notification
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.ForegroundInfo

/** 前台服务类型重载从 API 29 才有；Android 9 走双参，行为与 29+ 一致（类型由 Manifest 声明）。 */
internal fun dataSyncForegroundInfo(id: Int, notification: Notification): ForegroundInfo =
    if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    else ForegroundInfo(id, notification)
