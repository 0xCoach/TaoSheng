package app.resound.transcribe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow

/** 自然日/系统时间变化由系统通知，不为常驻队列每分钟唤醒。 */
internal fun calendarChanges(context: Context) = callbackFlow {
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { trySend(System.currentTimeMillis()) }
    }
    val filter = IntentFilter().apply {
        addAction(Intent.ACTION_DATE_CHANGED)
        addAction(Intent.ACTION_TIME_CHANGED)
        addAction(Intent.ACTION_TIMEZONE_CHANGED)
    }
    // 都是系统保护广播；旧 API 无需引入额外的应用级兼容权限。
    if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
    else {
        @Suppress("DEPRECATION")
        context.registerReceiver(receiver, filter)
    }
    trySend(System.currentTimeMillis())
    awaitClose { context.unregisterReceiver(receiver) }
}
