package app.resound.health

import app.resound.scheduling.refreshWhileNeeded
import android.Manifest
import android.app.Activity
import android.app.AppOpsManager
import android.app.Application
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import app.resound.backup.BackupRepository
import app.resound.capture.CaptureController
import app.resound.capture.CaptureNotifications
import app.resound.capture.CaptureState
import app.resound.capture.CaptureTilePolicy
import app.resound.capture.QuietRules
import app.resound.capture.QuietSchedule
import app.resound.data.CaptureDao
import app.resound.digest.DigestSettings
import app.resound.digest.LlmProviders
import app.resound.transcribe.TranscribeSettings
import app.resound.transcribe.TranscriptRepository
import app.resound.usage.LlmBalanceChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** 输入采集器：不查网络、不触发转写/备份；所有问题排序和文案交给 HealthRules。 */
class AndroidHealthCheck(
    private val app: Application,
    private val capture: CaptureController,
    private val quiet: QuietSchedule,
    private val transcribe: TranscribeSettings,
    private val transcripts: TranscriptRepository,
    private val digest: DigestSettings,
    private val providers: LlmProviders,
    private val balance: LlmBalanceChecker,
    private val backup: BackupRepository,
    dao: CaptureDao,
    scope: CoroutineScope,
) : HealthCheck {
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val capturePrefs = app.getSharedPreferences("capture", Context.MODE_PRIVATE)
    private val worker = Job(scope.coroutineContext[Job])
    private val owner = CoroutineScope(scope.coroutineContext + worker + Dispatchers.Main.immediate)
    private var events: List<HealthWatchdogEvent>? = null
    private val current = MutableStateFlow(collectReport())
    override val report = current.asStateFlow()
    private val appOps = app.getSystemService(AppOpsManager::class.java)
    private val permissions = AppOpsManager.OnOpChangedListener { _, _ -> refresh() }
    private val preferenceChanges = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key in setOf("manualPausedUntil", "quietSkipUntil")) refresh()
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refresh()
    }
    private val lifecycle = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) = refresh()
        override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    init {
        owner.launch { for (request in requests) current.value = collectReport() }
        watch(capture.status.map { it.state }.distinctUntilChanged())
        watch(quiet.rules); watch(quiet.active)
        watch(transcribe.ready); watch(transcripts.queue.map { it.failed }.distinctUntilChanged())
        watch(digest.hasKey); watch(providers.saved); watch(providers.activeId)
        watch(balance.state); watch(balance.threshold); watch(backup.state); watch(backup.local)
        // 只有设置页展示报告时才读取日志；历史用于异常去重，不能随意截断。
        owner.launch {
            current.subscriptionCount.map { it > 0 }.distinctUntilChanged().collectLatest { visible ->
                if (visible) dao.events(0, Long.MAX_VALUE).distinctUntilChanged().collect { rows ->
                    events = rows.map { HealthWatchdogEvent(it.at, it.type, it.detail) }
                    refresh()
                }
                else events = null
            }
        }
        owner.launch { current.subscriptionCount.map { it > 0 }.refreshWhileNeeded(60_000) { refresh() } }
        appOps.startWatchingMode(AppOpsManager.OPSTR_RECORD_AUDIO, app.packageName, permissions)
        capturePrefs.registerOnSharedPreferenceChangeListener(preferenceChanges)
        app.registerActivityLifecycleCallbacks(lifecycle)
        ContextCompat.registerReceiver(app, receiver, IntentFilter().apply {
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
            addAction(NotificationManager.ACTION_APP_BLOCK_STATE_CHANGED)
            addAction("android.os.action.POWER_SAVE_WHITELIST_CHANGED")
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        refresh()
    }

    private fun watch(flow: Flow<*>) { owner.launch { flow.collect { refresh() } } }
    override fun refresh() { requests.trySend(Unit) }

    private fun collectReport(): HealthReport {
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val date = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val status = capture.status.value
        val interruptions = events?.let { HealthWatchdogRules.interruptions(it, now, status.state == CaptureState.MicBusy) }
        val days = interruptions?.map { Instant.ofEpochMilli(it).atZone(zone).toLocalDate().toEpochDay() }
        val cloud = backup.state.value
        val local = backup.local.value
        val b = balance.state.value.balance
        // 既有备份调度是每天 03:10，WorkManager 可能延后；这里给规则估计值。
        val next = HealthBackupRules.nextAt(now,
            date.atTime(LocalTime.of(3, 10)).atZone(zone).toInstant().toEpochMilli(),
            date.plusDays(1).atTime(LocalTime.of(3, 10)).atZone(zone).toInstant().toEpochMilli())
        return HealthRules.report(HealthInputs(
            now = now, today = date.toEpochDay(),
            microphoneGranted = ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
            notificationsGranted = CaptureNotifications.permitted(app),
            batteryUnrestricted = app.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(app.packageName),
            listen = CaptureTilePolicy.listenState(status.state),
            inQuietPeriod = now >= capturePrefs.getLong("quietSkipUntil", 0) && QuietRules.windowAt(quiet.rules.value, now, zone) != null,
            transcriptReady = transcribe.ready.value, transcriptFailures = transcripts.queue.value.failed,
            modelReady = digest.hasKey.value,
            providerName = providers.saved.value.firstOrNull { it.id == providers.activeId.value }?.name.orEmpty(),
            balance = b?.total, balanceAvailable = b?.available, balanceCurrency = b?.currency.orEmpty(), balanceThreshold = balance.threshold.value,
            cloudBackupEnabled = cloud.enabled, cloudBackupFailed = cloud.lastError != null,
            localBackupEnabled = local.auto, localBackupFailed = local.lastError != null,
            nextBackupAt = next,
            interruptionsToday = days?.count { it == date.toEpochDay() }, lastInterruptionDay = days?.maxOrNull(),
            manualPausedUntil = capturePrefs.getLong("manualPausedUntil", 0),
        ))
    }

    /** 当前为 Application 生命周期对象；提供释放入口供测试/以后重新装配使用。 */
    fun close() {
        appOps.stopWatchingMode(permissions)
        capturePrefs.unregisterOnSharedPreferenceChangeListener(preferenceChanges)
        app.unregisterActivityLifecycleCallbacks(lifecycle)
        app.unregisterReceiver(receiver)
        worker.cancel()
        requests.close()
    }
}

fun batteryOptimizationSettingsIntent(): Intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
