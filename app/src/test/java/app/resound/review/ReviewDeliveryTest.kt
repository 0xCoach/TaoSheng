package app.resound.review

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.work.*
import androidx.work.testing.WorkManagerTestInitHelper
import app.resound.push.LocalNotifier
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.ZonedDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class ReviewDeliveryTest {
    @Test fun dailyScheduleHasNetworkConstraintAndChangingTimeReplacesIt() {
        val context = RuntimeEnvironment.getApplication() as Context
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
        val scheduler = ReviewScheduler(context)
        val manager = WorkManager.getInstance(context)
        scheduler.schedule(22 * 60); scheduler.schedule(22 * 60)
        val old = manager.getWorkInfosForUniqueWork(ReviewScheduler.NAME).get().filter { !it.state.isFinished }.single()
        assertEquals(NetworkType.CONNECTED, old.constraints.requiredNetworkType)
        assertEquals(3600000L, ReviewScheduler.delayMinutes(22 * 60, ZonedDateTime.parse("2026-10-23T21:00:00+08:00[Asia/Shanghai]")))
        scheduler.schedule(21 * 60, changed = true)
        val current = manager.getWorkInfosForUniqueWork(ReviewScheduler.NAME).get().filter { !it.state.isFinished }.single()
        assertNotEquals(old.id, current.id)
        manager.cancelUniqueWork(ReviewScheduler.NAME).result.get()
    }

    @Test fun notificationUsesOwnChannelAndReviewExtras() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        LocalNotifier(context).send("回响 · 今日回顾", "简洁版", "resound://review?open=review&date=2026-10-23")
        val manager = context.getSystemService(NotificationManager::class.java)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, manager.getNotificationChannel(LocalNotifier.CHANNEL).importance)
        val notification = shadowOf(manager).allNotifications.single()
        assertEquals(LocalNotifier.CHANNEL, notification.channelId)
        val intent = shadowOf(notification.contentIntent).savedIntent
        assertEquals("review", intent.getStringExtra("open"))
        assertEquals("2026-10-23", intent.getStringExtra("date"))
        assertEquals("app.resound.MainActivity", intent.component!!.className)
    }
}
