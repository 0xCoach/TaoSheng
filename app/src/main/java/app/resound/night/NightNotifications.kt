package app.resound.night
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
object NightNotifications {
    fun show(context: Context,key: String,text: String) {
        val manager=context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("night","夜里",NotificationManager.IMPORTANCE_DEFAULT))
        if(!manager.areNotificationsEnabled())return
        manager.notify(key,202,NotificationCompat.Builder(context,"night").setSmallIcon(android.R.drawable.ic_menu_recent_history).setContentTitle("昨晚").setContentText(text).setAutoCancel(true).build())
    }
}
