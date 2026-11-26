package app.resound.push

import android.content.Context
import androidx.work.*
import app.resound.AppGraph
import app.resound.screentime.ScreenTimeReader
import app.resound.screentime.ScreenTimeReport
import app.resound.screentime.ScreenTimeReports
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toKotlinLocalDate
import org.json.JSONArray
import org.json.JSONObject
import java.time.format.DateTimeFormatter

/** 每日屏幕时间：到点推送，再排下一次。 */
class ScreenTimePushWork(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val channels = AppGraph.push as? AndroidPushChannels ?: return Result.retry()
        return try {
            ScreenTimePushScheduler.plan(applicationContext, channels.dispatchScreenTime())
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.retry()
        }
    }
}

internal object ScreenTimePushScheduler {
    private const val NAME = "push-screen-time"
    fun plan(context: Context, at: Long?) = scheduleDailyPush<ScreenTimePushWork>(context, NAME, at)
}

internal inline fun <reified W : ListenableWorker> scheduleDailyPush(context: Context, name: String, at: Long?) {
    val work = WorkManager.getInstance(context)
    if (at == null) {
        work.cancelUniqueWork(name)
        return
    }
    val after = (at - System.currentTimeMillis()).coerceAtLeast(0L)
    val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    val request = OneTimeWorkRequestBuilder<W>().setInitialDelay(after, TimeUnit.MILLISECONDS).setConstraints(network).build()
    work.enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, request)
}

private val isoOffset: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")

internal fun screenTimePayload(report: ScreenTimeReport, zone: java.time.ZoneId): JSONObject {
    val clock = isoOffset.withZone(zone)
    fun iso(ms: Long): String = clock.format(java.time.Instant.ofEpochMilli(ms))
    return JSONObject()
        .put("schemaVersion", 1)
        .put("date", report.date.toString())
        .put("coverageUntil", report.result.coverageUntil?.let(::iso) ?: JSONObject.NULL)
        .put("totalMinutes", report.totalMinutes)
        .put("apps", JSONArray(report.apps.map { app ->
            JSONObject().put("package", app.pkg).put("name", app.name).put("minutes", app.minutes)
        }))
        .put("timeline", JSONArray(report.result.sessions.map { session ->
            JSONObject().put("package", session.pkg).put("name", session.name)
                .put("start", iso(session.from)).put("end", iso(session.until))
        }))
        .put("devicePlayback", JSONArray(report.result.devicePlayback.map { item ->
            JSONObject().put("package", item.pkg).put("name", item.name)
                .put("title", item.title ?: JSONObject.NULL).put("subtitle", item.subtitle ?: JSONObject.NULL)
                .put("start", iso(item.from)).put("end", iso(item.until))
        }))
        .put("gaps", JSONArray(report.result.gaps.map { gap ->
            JSONObject().put("code", gap.code).put("source", gap.source)
                .put("from", iso(gap.from)).put("until", iso(gap.until)).put("fix", gap.fix)
        }))
}

internal suspend fun buildScreenTimeMessage(
    date: java.time.LocalDate,
    now: Long,
    zone: java.time.ZoneId,
    reader: ScreenTimeReader?,
): PushMessage? {
    val report = loadScreenTimeReport(date, now, zone, reader) ?: return null
    return PushMessage(report.title, report.body, PushTopic.ScreenTime, null, now, screenTime = screenTimePayload(report, zone))
}

internal suspend fun loadScreenTimeReport(
    date: java.time.LocalDate,
    now: Long,
    zone: java.time.ZoneId,
    reader: ScreenTimeReader?,
): app.resound.screentime.ScreenTimeReport? {
    if (reader == null) return null
    val from = date.atStartOfDay(zone).toInstant().toEpochMilli()
    val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val until = minOf(now, dayEnd).coerceAtLeast(from)
    // 推送时刻不晚于当天 0 点：当天还没有数据，不查询（reader 对空区间会直接抛参数异常）。
    if (until <= from) return null
    val result = reader.sessions(from, until)
    return ScreenTimeReports.build(date.toKotlinLocalDate(), TimeZone.of(zone.id), result)
}

/** 通道或订阅变化时重排屏幕时间推送；内容由 ScreenTimeReader 生成。 */
internal fun AndroidPushChannels.attachScreenTime(context: Context, scope: CoroutineScope) {
    screenTimeMessage = { date ->
        buildScreenTimeMessage(date, System.currentTimeMillis(), java.time.ZoneId.systemDefault(), AppGraph.screenTimeReader)
    }
    subscriptions.screenTimeMarkdown = { date ->
        loadScreenTimeReport(date, System.currentTimeMillis(), java.time.ZoneId.systemDefault(), AppGraph.screenTimeReader)?.markdown
    }
    scope.launch(Dispatchers.IO) {
        channels.map { list -> list.filter { it.enabled && it.subscription.screenTime }.map { it.id to it.subscription.screenTimeAt } }
            .distinctUntilChanged().collect { ScreenTimePushScheduler.plan(context, nextScreenTimeAt()) }
    }
}
