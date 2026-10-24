package app.resound.capture

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import app.resound.ResoundApp
import app.resound.audio.*
import app.resound.data.HeartbeatEntity
import app.resound.data.AppPowerEntity
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.Executors

/** 一个串行调度器管理命令/状态/心跳，麦克风读取和文件编码各自使用 IO。 */
class CaptureService : Service() {
    private val dispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "capture-state") }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private data class Command(val intent: Intent, val completion: CompletableDeferred<Unit>? = null)
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private var discardOnFinish = false
    private val app get() = application as ResoundApp
    private val store get() = app.store
    private val controller get() = app.controller
    private var capture: Job? = null
    private var resumeTimer: Job? = null
    private var heartbeat: Job? = null
    private var initialized = false
    private var normalStop = false
    private val beatMutex = Mutex()
    private var intervalStart = 0L
    private var state = CaptureState.Stopped
    private val session = UUID.randomUUID().toString()
    private val micClock = MicOpenClock(SystemClock::elapsedRealtime)
    private val chargeEpoch = java.util.concurrent.atomic.AtomicLong(0)
    private val powerReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) { chargeEpoch.incrementAndGet() }
    }
    private suspend fun samplePower() {
        val epoch = chargeEpoch.get()
        val (percent, charging) = store.battery()
        val charge = getSystemService(android.os.BatteryManager::class.java)
            .getLongProperty(android.os.BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        store.dao.appPower(AppPowerEntity(
            at = System.currentTimeMillis(), elapsedMs = SystemClock.elapsedRealtime(), session = session,
            cpuMs = android.os.Process.getElapsedCpuTime(),
            txBytes = android.net.TrafficStats.getUidTxBytes(android.os.Process.myUid()),
            rxBytes = android.net.TrafficStats.getUidRxBytes(android.os.Process.myUid()),
            micMs = micClock.totalMillis(), charging = charging.takeIf { epoch == chargeEpoch.get() }, chargeEpoch = epoch,
            capacityMah = AppPowerModel.capacityMah(charge, percent)))
    }
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var playback: PlaybackMonitor

    override fun onCreate() {
        super.onCreate()
        alive = true
        instance = this
        playback = PlaybackMonitor(this)
        registerReceiver(powerReceiver, android.content.IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED)
        })
        scope.launch {
            try { for (command in commands) {
                try { handle(command.intent); command.completion?.complete(Unit) }
                catch (e: Exception) { command.completion?.completeExceptionally(e); throw e }
            } }
            catch (e: Exception) {
                if (e is CancellationException) throw e
                fail("服务异常：${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null && !store.prefs.getBoolean("enabled", false)) {
            normalStop = true
            stopSelf(); return START_NOT_STICKY
        }
        val command = intent ?: Intent().setAction(START).putExtra("route", "system-sticky")
        // 停止也不创建新的录音会话。
        if (command.action == STOP) { commands.trySend(Command(command)); return START_NOT_STICKY }
        if (!CaptureNotifications.permitted(this)) {
            controller.update(CaptureState.NoPermission)
            stopSelf(); return START_NOT_STICKY
        }
        try {
            startForeground(CaptureNotifications.ONGOING, CaptureNotifications.ongoing(this, store.prefs.getLong("pausedUntil", 0) > System.currentTimeMillis()), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (_: RuntimeException) {
            controller.update(CaptureState.NoPermission)
            CaptureNotifications.interrupted(this)
            stopSelf(); return START_NOT_STICKY
        }
        commands.trySend(Command(command))
        return START_STICKY
    }
    private suspend fun handle(intent: Intent) {
        if (intent.action == DISCARD_CURRENT) {
            val wasCapturing = capture?.isActive == true
            discardOnFinish = true
            try { capture?.cancelAndJoin(); capture = null }
            finally { discardOnFinish = false }
            // 只换录音协程，服务、心跳、暂停计时均不重启。
            if (wasCapturing) { releaseWakeLock(); resumeCapture() }
            return
        }
        if (intent.action == STOP) {
            capture?.cancelAndJoin(); capture = null
            resumeTimer?.cancel(); cancelAlarm()
            if (initialized) {
                beat()
                samplePower()
                store.event(WatchdogEventType.ServiceStopped, "用户正常停止")
            }
            normalStop = true
            store.prefs.edit().putBoolean("enabled", false).putBoolean("unclean", false).putLong("pausedUntil", 0).commit()
            transition(CaptureState.Stopped)
            heartbeat?.cancel()
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            NotificationManagerCompat.from(this).cancel(CaptureNotifications.INTERRUPTED)
            stopSelf(); return
        }
        if (!initialized) {
            val now = System.currentTimeMillis()
            if (store.prefs.getBoolean("unclean", false)) {
                val lastHeartbeat = store.prefs.getLong("heartbeat", now).coerceAtMost(now)
                val last = maxOf(lastHeartbeat, store.prefs.getLong("pausedUntil", 0).coerceAtMost(now))
                store.event(WatchdogEventType.Killed, "上次未正常停止；最后心跳至恢复约 ${now - last}ms（上界估计）", last, now)
                store.event(WatchdogEventType.Recovered, "恢复途径=${intent.getStringExtra("route") ?: "unknown"}")
            }
            store.prefs.edit().putBoolean("enabled", true).putBoolean("unclean", true).putLong("heartbeat", now).commit()
            initialized = true; intervalStart = now
            store.event(WatchdogEventType.ServiceStarted, "启动途径=${intent.getStringExtra("route")}")
            store.sampleBattery(); samplePower()
            CaptureWatchdog.schedule(this)
            heartbeat = scope.launch {
                var batteryAt = SystemClock.elapsedRealtime()
                while (isActive) {
                    delay(CaptureTuning.HEARTBEAT_MS); beat()
                    if (SystemClock.elapsedRealtime() - batteryAt >= CaptureTuning.BATTERY_MS) {
                        store.sampleBattery(); samplePower(); batteryAt = SystemClock.elapsedRealtime()
                    }
                }
            }
        }
        NotificationManagerCompat.from(this).cancel(CaptureNotifications.INTERRUPTED)
        when (intent.action) {
            PAUSE -> {
                val until = System.currentTimeMillis() + intent.getIntExtra("minutes", 60).coerceIn(1, 10080).toLong() * 60_000
                pauseUntil(until)
                store.event(WatchdogEventType.Paused, "暂停至 $until")
            }
            RESUME -> resumeCapture()
            else -> {
                val until = store.prefs.getLong("pausedUntil", 0)
                if (until > System.currentTimeMillis()) pauseUntil(until) else resumeCapture()
            }
        }
    }
    private suspend fun pauseUntil(until: Long) {
        capture?.cancelAndJoin(); capture = null
        resumeTimer?.cancel()
        transition(CaptureState.Paused, until)
        releaseWakeLock()
        store.prefs.edit().putLong("pausedUntil", until).commit()
        val manager = getSystemService(AlarmManager::class.java)
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, until, alarmIntent())
        resumeTimer = scope.launch { delay((until - System.currentTimeMillis()).coerceAtLeast(0)); commands.send(Command(Intent().setAction(RESUME).putExtra("route", "pause-timer"))) }
        refreshNotification(true)
    }
    private suspend fun resumeCapture() {
        val wasPaused = state == CaptureState.Paused || store.prefs.getLong("pausedUntil", 0) > 0
        resumeTimer?.cancel(); resumeTimer = null; cancelAlarm()
        store.prefs.edit().putLong("pausedUntil", 0).commit()
        if (wasPaused) store.event(WatchdogEventType.Resumed, "暂停结束或用户恢复")
        if (capture?.isActive == true) return
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "resound:capture").apply { acquire() }
        transition(CaptureState.Listening)
        refreshNotification(false)
        capture = scope.launch {
            try { listen() }
            catch (e: Exception) {
                if (e is CancellationException) throw e
                fail("录音收尾异常：${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }
    private suspend fun listen() {
        app.segmentFiles.awaitReady()
        InputSwitchLoop(app.inputs.snapshots, SystemClock::elapsedRealtime).run(onUnchanged = { route, note ->
            if (app.inputs.status.value.using.key == route.device.key) app.inputs.report(route.device, note)
        }) { route, note ->
            listenTo(route, note)
        }
    }
    private suspend fun listenTo(route: InputRoute, note: String?) {
        var busy = false
        var reported = false
        while (currentCoroutineContext().isActive) {
            var segmenter: SpeechSegmenter? = null
            try {
                val source: AudioSource = if (route.device.kind == InputKind.Phone) MicAudioSource(this, micClock::setOpen)
                else ExternalAudioSource(this, route.device,
                    app.inputs.port(requireNotNull(route.portId))
                        ?: throw AudioSourceException(AudioSourceException.Reason.Disconnected), micClock::setOpen)
                SileroVad(this).use { vad ->
                    var levelAt = 0L
                    var levelPeak = 0f
                    source.events().collect { event ->
                        when (event) {
                            is AudioEvent.Busy -> {
                                // 让出麦克风期间：正在录的片段按正常结束收尾，恢复后从新片段开始计时
                                segmenter?.finish(); segmenter = null
                                if (!busy) { store.event(WatchdogEventType.MicBusy, event.reason); busy = true }
                                controller.level(0f)
                                transition(CaptureState.MicBusy)
                                return@collect
                            }
                            AudioEvent.Available -> {
                                if (busy) { store.event(WatchdogEventType.MicReleased); busy = false }
                                transition(CaptureState.Listening)
                                return@collect
                            }
                            is AudioEvent.Frame -> Unit
                        }
                        if (!reported) {
                            val previous = app.inputs.status.value.using
                            app.inputs.report(route.device, note)
                            if (previous.key != route.device.key || note != null)
                                store.inputSwitched("${previous.name} → ${route.device.name}；${note ?: "录音输入切换"}")
                            reported = true
                        }
                        val frame = (event as AudioEvent.Frame).samples
                        if (busy) { store.event(WatchdogEventType.MicReleased); busy = false }
                        val cutter = segmenter ?: SpeechSegmenter(app.segmentFiles.sink(
                            System.currentTimeMillis() - frame.size * 1000L / AudioSource.SAMPLE_RATE, source.id)).also { segmenter = it }
                        vad.accept(frame) { pcm, speech, level ->
                            cutter.accept(pcm, speech, playback.speakerPlaying)
                            val next = if (cutter.active) CaptureState.Recording else CaptureState.Listening
                            // 状态和采样在此线程串行；人声状态切换不改变 running 区间。
                            if (state != next) { state = next; controller.update(next) }
                            val elapsed = SystemClock.elapsedRealtime()
                            levelPeak = maxOf(levelPeak, level)
                            // 每 50 毫秒报一次这段时间里的最大音量，界面据此画真实波形
                            if (elapsed - levelAt >= 50) { controller.level(levelPeak); levelPeak = 0f; levelAt = elapsed }
                        }
                    }
                }
            } catch (e: AudioSourceException) {
                // 外接失败立即交调度器切手机；蓝牙缺权限也不应停止手机录音。
                if (route.device.kind != InputKind.Phone) throw e
                if (e.reason == AudioSourceException.Reason.NoPermission) {
                    transition(CaptureState.NoPermission)
                    fail("麦克风权限被撤销")
                    awaitCancellation()
                }
                if (!busy) { store.event(WatchdogEventType.MicBusy, "${e.reason}: ${e.message}"); busy = true }
                transition(CaptureState.MicBusy)
            } catch (e: Exception) {
                // 外接设备的意外错误也只换回手机，不停整个录音
                if (e is CancellationException || route.device.kind == InputKind.Phone) throw e
                throw AudioSourceException(AudioSourceException.Reason.Unknown, "${e.javaClass.simpleName}: ${e.message}")
            } finally {
                if (discardOnFinish) segmenter?.discard() else segmenter?.finish()
                controller.level(0f)
            }
            delay(CaptureTuning.MIC_RETRY_MS)
        }
    }
    private fun running(s: CaptureState) = s == CaptureState.Listening || s == CaptureState.Recording || s == CaptureState.MicBusy
    private suspend fun transition(next: CaptureState, until: Long? = null) {
        if (initialized && running(state) != running(next)) beat()
        state = next; controller.update(next, until)
    }
    private suspend fun beat() = beatMutex.withLock {
        val now = System.currentTimeMillis()
        store.dao.heartbeat(HeartbeatEntity(at = now, intervalStart = minOf(intervalStart, now), running = running(state), session = session))
        intervalStart = now
        store.prefs.edit().putLong("heartbeat", now).commit()
    }
    private suspend fun fail(detail: String) {
        if (initialized) { beat(); store.event(WatchdogEventType.ServiceStopped, "$detail；等待手动恢复，保留未正常停止标记") }
        if (state != CaptureState.NoPermission) { state = CaptureState.Stopped; controller.update(state) }
        releaseWakeLock(); heartbeat?.cancel()
        CaptureNotifications.interrupted(this)
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    private fun alarmIntent() = PendingIntent.getBroadcast(this, 10, Intent(this, CaptureResumeReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun cancelAlarm() { getSystemService(AlarmManager::class.java).cancel(alarmIntent()) }
    private fun refreshNotification(paused: Boolean) {
        if (CaptureNotifications.permitted(this)) NotificationManagerCompat.from(this).notify(CaptureNotifications.ONGOING, CaptureNotifications.ongoing(this, paused))
    }
    private fun releaseWakeLock() { wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null }
    override fun onDestroy() {
        alive = false
        instance = null
        commands.close()
        while (true) {
            val pending = commands.tryReceive().getOrNull() ?: break
            pending.completion?.completeExceptionally(CancellationException("录音服务已停止"))
        }
        playback.close()
        unregisterReceiver(powerReceiver)
        if (!normalStop && controller.status.value.state != CaptureState.NoPermission) controller.update(CaptureState.Stopped)
        scope.cancel(); dispatcher.close(); releaseWakeLock()
        if (!normalStop && initialized) CaptureNotifications.interrupted(this)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        private const val DISCARD_CURRENT = "app.resound.capture.DISCARD_CURRENT"
        @Volatile private var instance: CaptureService? = null
        /** 进程内动作，未运行时直接返回，不启动服务。 */
        internal suspend fun discardCurrent() {
            val service = instance ?: return
            val done = CompletableDeferred<Unit>()
            service.commands.send(Command(Intent().setAction(DISCARD_CURRENT), done))
            done.await()
        }
        const val START = "app.resound.capture.START"
        const val STOP = "app.resound.capture.STOP"
        const val PAUSE = "app.resound.capture.PAUSE"
        const val RESUME = "app.resound.capture.RESUME"
        @Volatile var alive = false
            private set
    }
}
