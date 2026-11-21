package app.resound.night
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
object NightNotifications {
    fun dream(context: Context,nightId: Long) {
        val manager=context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("night","夜里",NotificationManager.IMPORTANCE_DEFAULT))
        if(!manager.areNotificationsEnabled())return
        val intent=context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            action="app.resound.action.RECORD_DREAM";putExtra("nightId",nightId)
        } ?: return
        val pending=android.app.PendingIntent.getActivity(context,nightId.toInt(),intent,android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        manager.notify("dream-$nightId",203,NotificationCompat.Builder(context,"night").setSmallIcon(app.resound.R.drawable.ic_notify).setContentTitle("要记一下梦吗？").setContentText("记录刚醒来时记得的梦").setContentIntent(pending).addAction(android.R.drawable.ic_menu_edit,"记梦",pending).setAutoCancel(true).build())
    }
    fun show(context: Context,key: String,text: String) {
        val manager=context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("night","夜里",NotificationManager.IMPORTANCE_DEFAULT))
        if(!manager.areNotificationsEnabled())return
        manager.notify(key,202,NotificationCompat.Builder(context,"night").setSmallIcon(app.resound.R.drawable.ic_notify).setContentTitle("昨晚").setContentText(text).setAutoCancel(true).build())
    }
}
