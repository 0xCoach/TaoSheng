package app.resound.ui.screentime

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.resound.AppGraph
import app.resound.capture.MicHolderGuess
import app.resound.diagnostics.FailureNotes
import app.resound.screentime.NamedAppSession
import app.resound.screentime.ScreenTimeReport
import app.resound.screentime.ScreenTimeReports
import app.resound.screentime.ScreenTimeResult
import app.resound.screentime.ScreenTimeView
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toKotlinLocalDate

/** 某一天的屏幕时间：总时长、每小时分钟数、使用段，以及日报（App 排行、播放、缺口）。 */
internal data class ScreenDay(
    val date: LocalDate,
    val report: ScreenTimeReport,
    val byHour: List<Int>,
) {
    val totalMinutes: Int get() = report.totalMinutes
    val sessions: List<NamedAppSession> get() = report.result.sessions
}

/** 首页这一天的屏幕时间；没开屏幕时间、首页不显示或还没有数据时为 null。 */
internal val LocalScreenDay = compositionLocalOf<ScreenDay?> { null }

internal fun dayBounds(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): List<Long> =
    (0..24).map { h -> date.atStartOfDay(zone).plusHours(h.toLong()).toInstant().toEpochMilli() }

/** 读 [from, until) 的屏幕时间；没装配、屏幕时间关闭或区间为空时为 null。 */
internal suspend fun readScreenTime(from: Long, until: Long): ScreenTimeResult? {
    val reader = AppGraph.screenTimeReader ?: return null
    if (until <= from) return null
    return withContext(Dispatchers.IO) { reader.sessions(from, until) }.takeIf { it.enabled }
}

internal suspend fun loadScreenDay(date: LocalDate): ScreenDay? {
    val zone = ZoneId.systemDefault()
    val bounds = dayBounds(date, zone)
    val until = minOf(bounds.last(), System.currentTimeMillis())
    val result = readScreenTime(bounds.first(), until) ?: return null
    val report = ScreenTimeReports.build(date.toKotlinLocalDate(), TimeZone.of(zone.id), result) ?: return null
    return ScreenDay(date, report, ScreenTimeView.minutesByHour(result.sessions, bounds))
}

/**
 * 跟着数据库变化和回到前台刷新的一天屏幕时间。[active] 为 false 时不读、返回 null。
 * 拉取写库会再触发一次刷新，拉取器自带 30 秒节流，不会来回空转。
 */
@Composable
internal fun rememberScreenDay(date: LocalDate, active: Boolean = true): ScreenDay? {
    if (!AppGraph.isScreenTimeReady) return null
    val enabled by AppGraph.screenTimeSettings.enabled.collectAsStateWithLifecycle()
    var day by remember(date) { mutableStateOf<ScreenDay?>(null) }
    var resumeTick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumeTick++ }
    val app = LocalContext.current.applicationContext as? app.resound.ResoundApp
    LaunchedEffect(date, enabled, active, resumeTick) {
        if (!enabled || !active) { day = null; return@LaunchedEffect }
        val load: suspend () -> Unit = {
            day = try { loadScreenDay(date) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                FailureNotes.record("ui/screen-day", e); null
            }
        }
        val tracker = app?.database?.invalidationTracker
        if (tracker == null) { load(); return@LaunchedEffect }
        tracker.createFlow("screen_events", "screen_pull_state", "screen_coverage_gaps", "playback_rows", emitInitialState = true)
            .collect { load() }
    }
    return if (enabled && active) day else null
}

/** 屏幕时间要的两项系统权限：检测与直达设置页，跳不过去时退到涛声的应用详情页。 */
internal object ScreenPermissions {
    fun usageAccess(context: Context): Boolean = MicHolderGuess(context.applicationContext).permitted()

    fun notificationAccess(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    fun openUsageAccess(context: Context) = open(context,
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${context.packageName}")),
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))

    fun openNotificationAccess(context: Context) = open(context,
        if (Build.VERSION.SDK_INT >= 30) Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
            Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
            ComponentName(context.packageName, "app.resound.screentime.PlaybackListenerService").flattenToString())
        else Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))

    /** 依次试；都跳不过去就打开涛声的应用详情页。 */
    private fun open(context: Context, vararg intents: Intent) {
        for (intent in intents) {
            try { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return }
            catch (e: RuntimeException) { FailureNotes.record("ui/screen-permission-open", e) }
        }
        try {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: RuntimeException) { FailureNotes.record("ui/screen-permission-details", e) }
    }
}

/** 两项权限的当前状态，回到前台时重新检测。 */
@Composable
internal fun rememberScreenPermissions(): Pair<Boolean, Boolean> {
    val context = LocalContext.current
    var usage by remember { mutableStateOf(ScreenPermissions.usageAccess(context)) }
    var notice by remember { mutableStateOf(ScreenPermissions.notificationAccess(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        usage = ScreenPermissions.usageAccess(context)
        notice = ScreenPermissions.notificationAccess(context)
    }
    return usage to notice
}

internal fun screenDuration(minutes: Int): String =
    if (minutes < 60) "$minutes 分" else "${minutes / 60} 小时 ${minutes % 60} 分"
