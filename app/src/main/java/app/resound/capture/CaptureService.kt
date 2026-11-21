package app.resound.capture

import app.resound.transcribe.PlaybackOverride
import app.resound.digest.DigestMode
import app.resound.AppGraph
import app.resound.auth.AuthGate
import app.resound.auth.Capability

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import app.resound.ResoundApp
import app.resound.importer.AndroidFolderImports
import app.resound.audio.*
import app.resound.data.HeartbeatEntity
import app.resound.data.AppPowerEntity
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.map
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
    private val holders by lazy { MicHolderGuess(this) }
    private val controller get() = app.controller
    private var capture: Job? = null
    private var resumeTimer: Job? = null
    private var heartbeat: Job? = null
    /** 试用到期精确任务：心跳只是兜底，到点用单调延迟的 RECHECK 触发收尾（delay 用单调时钟，改系统时间不影响）。 */
    private var trialExpiryJob: Job? = null
    /** 试用计量的单调时钟锚点——改系统时间不 inflate/deflate 已用量。 */
    private var intervalStartElapsed = 0L
    private var initialized = false
    private var normalStop = false
    private val beatMutex = Mutex()
    private var intervalStart = 0L
    private var state = CaptureState.Stopped
    private val session = UUID.randomUUID().toString()
    private val micClock = MicOpenClock(SystemClock::elapsedRealtime)
    private val chargeEpoch = java.util.concurrent.atomic.AtomicLong(0)
    private val powerReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_SCREEN_ON) chargeEpoch.incrementAndGet()
            if (intent?.action == Intent.ACTION_SCREEN_ON || intent?.action == Intent.ACTION_POWER_CONNECTED) {
                // 补跑失败不能影响录音服务；下一段录音或周期转写会兜底
                scope.launch(Dispatchers.IO) { runCatching { app.resumeWaitingTranscriptionBatch(intent.action) } }
            }
        }
    }
    private val threadCpuDiagnostics = ThreadCpuDiagnostics()
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
        threadCpuDiagnostics.record(this, charging.takeIf { epoch == chargeEpoch.get() }, state.name)
    }
    private var nightCapture: app.resound.night.NightCapture? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        nightCapture = (AppGraph.night as? app.resound.night.AndroidNight)?.capture(scope)
        alive = true
        instance = this
        folderImports()?.recordingChanged()
        // 播放状态取 AppGraph 应用级单例；服务存活期间保持订阅，让回调与轮询保持活跃，
        // 判定直接读 state.value；销毁时订阅随 scope.cancel 一起取消。
        scope.launch {
            val current = app.database.invalidationTracker.createFlow("playback_rows").map {
                app.database.screenTime().openPlaybacks().maxByOrNull { it.lastSeenAt }
            }
            kotlinx.coroutines.flow.combine(AppGraph.playback.state, current,
                AppGraph.playbackSettings.preferences) { state, row, prefs -> Triple(state, row, prefs) }
                .collect { (state, row, prefs) ->
                    val before = app.playbackSession.canRemember
                    app.playbackSession.update(state.playing, state.speakerPlaying, row?.sessionKey,
                        prefs.rule(row?.pkg), System.currentTimeMillis())
                    if (initialized && before != app.playbackSession.canRemember) refreshOngoing()
                }
        }
        registerReceiver(powerReceiver, android.content.IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_SCREEN_ON)
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
        // AUTH：所有进入录音动作的入口（界面/磁贴/通知/Widget/场景/快捷指令）统一在这里拦。
        // 已初始化（正在录）时走正常 STOP 通道安全收尾——当前片段写完、心跳归档；
        // 未初始化时绝不为此新建录音会话。
        if (AppGraph.isAuthReady && !AppGraph.auth.allowed(Capability.RecordAudio)) {
            if (initialized) commands.trySend(Command(Intent().setAction(STOP).putExtra("route", "auth-expired")))
            else {
                controller.update(CaptureState.Stopped)
                store.prefs.edit().putBoolean("enabled", false).commit()
                // startForegroundService 契约：必须尽快 startForeground，否则系统判超时崩溃。
                runCatching { startForegroundWithLocation() }
                stopSelf()
            }
            return START_NOT_STICKY
        }
        if (!CaptureNotifications.permitted(this)) {
            controller.update(CaptureState.NoPermission)
            runCatching { startForegroundWithLocation() }
            stopSelf(); return START_NOT_STICKY
        }
        try {
            startForegroundWithLocation()
        } catch (_: RuntimeException) {
            controller.update(CaptureState.NoPermission)
            CaptureNotifications.interrupted(this)
            stopSelf(); return START_NOT_STICKY
        }
        commands.trySend(Command(command))
        return START_STICKY
    }
    private suspend fun handle(intent: Intent) {
        if (intent.action == RECHECK && (!initialized || !store.prefs.getBoolean("enabled", false))) return
        if (intent.action == REMEMBER_PLAYBACK) {
            if (initialized && AppGraph.playback.state.value.speakerPlaying) app.playbackSession.remember(System.currentTimeMillis())
            refreshOngoing()
            return
        }
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
            CaptureWatchdog.cancel(this)
            normalStop = true
            store.prefs.edit().putBoolean("enabled", false).putBoolean("unclean", false).putLong("pausedUntil", 0).putLong("manualPausedUntil", 0).remove("quietName").commit()
            transition(CaptureState.Stopped)
            heartbeat?.cancel(); trialExpiryJob?.cancel()
            releaseWakeLock()
            stopLocation()
            stopForeground(STOP_FOREGROUND_REMOVE)
            NotificationManagerCompat.from(this).cancel(CaptureNotifications.INTERRUPTED)
            NotificationManagerCompat.from(this).cancel(CaptureNotifications.SILENT_MIC)
            stopSelf(); return
        }
        if (!initialized) {
            val now = System.currentTimeMillis()
            if (store.prefs.getBoolean("unclean", false)) {
                val lastHeartbeat = store.prefs.getLong("heartbeat", now).coerceAtMost(now)
                val gaps = QuietRules.recordingGaps(app.quietSchedule.rules.value, lastHeartbeat, now,
                    java.time.ZoneId.systemDefault(), store.prefs.getLong("manualPausedUntil", 0), store.prefs.getLong("quietSkipUntil", 0))
                for ((from, to) in gaps) store.event(WatchdogEventType.Killed, "上次未正常停止；排除暂停后缺口约 ${to - from}ms（上界估计）", from, to)
                store.event(WatchdogEventType.Recovered, "恢复途径=${intent.getStringExtra("route") ?: "unknown"}")
            }
            store.prefs.edit().putBoolean("enabled", true).putBoolean("unclean", true).putLong("heartbeat", now).commit()
            initialized = true; intervalStart = now; intervalStartElapsed = SystemClock.elapsedRealtime()
            store.event(WatchdogEventType.ServiceStarted, "启动途径=${intent.getStringExtra("route")}")
            store.sampleBattery(); samplePower()
            CaptureWatchdog.schedule(this)
            heartbeat = scope.launch {
                var batteryAt = SystemClock.elapsedRealtime()
                while (isActive) {
                    delay(CaptureTuning.HEARTBEAT_MS); beat()
                    commands.send(Command(Intent().setAction(RECHECK)))
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
                store.prefs.edit().putLong("manualPausedUntil", until).commit()
                reconcilePause()
            }
            RESUME -> {
                // 隐私暂停是硬锁：RESUME（背敲/磁贴/通知/Widget/快捷指令）只解手动暂停，
                // 绝不顺带清 KEY_PRIVACY——解除隐私暂停只能走 PRIVACY(on=false) 的明确开关。
                store.prefs.edit().putLong("manualPausedUntil", 0).commit()
                app.quietSchedule.resume()
                reconcilePause()
            }
            PRIVACY -> {
                val on = intent.getBooleanExtra("on", false)
                store.prefs.edit().putBoolean(AndroidScene.KEY_PRIVACY, on)
                    .putLong("manualPausedUntil", if (on) Long.MAX_VALUE else 0L).commit()
                reconcilePause()
            }
            else -> reconcilePause()
        }
    }
    private suspend fun reconcilePause() {
        // 授权失效兜底：心跳 RECHECK 周期内复查——试用耗尽/凭证过期时正常收尾停录（保留已录片段），
        // 修掉"授权只在入口拦、已录会话永不复核"的缺口。
        if (AppGraph.isAuthReady && !AppGraph.auth.allowed(Capability.RecordAudio)) {
            handle(Intent().setAction(STOP).putExtra("route", "auth-expired")); return
        }
        val at = System.currentTimeMillis()
        val decision = app.scene.decide(at)
        app.scene.track(decision, at)
        val plan = decision.directive
        if (plan is CaptureDirective.Pause) pauseUntil(plan.until, plan.label)
        else if (playbackHeld) {
            capture?.cancelAndJoin(); capture = null
            releaseWakeLock()
            // 临时让麦仍属于服务正常运行：不记手动暂停、MicBusy 或漏录。
            transition(CaptureState.Listening)
            refreshNotification(false)
        } else resumeCapture()
    }
    private suspend fun pauseUntil(until: Long, quietName: String? = null) {
        val old = controller.status.value
        if (state == CaptureState.Paused && old.pausedUntil == until && old.quietName == quietName) { refreshNotification(true); return }
        capture?.cancelAndJoin(); capture = null
        resumeTimer?.cancel()
        transition(CaptureState.Paused, until, quietName = quietName)
        releaseWakeLock()
        store.prefs.edit().putLong("pausedUntil", until).putString("quietName", quietName).commit()
        store.event(WatchdogEventType.Paused, when {
            until == Long.MAX_VALUE && quietName != null -> "专题暂停（恢复专题后继续）"
            until == Long.MAX_VALUE -> "隐私暂停（需手动恢复）"
            quietName == null -> "暂停至 $until"
            else -> "停录时段：$quietName，至 ${java.time.Instant.ofEpochMilli(until).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))}"
        })
        val manager = getSystemService(AlarmManager::class.java)
        if (until != Long.MAX_VALUE) {
            try {
                if (android.os.Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms()) manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, until, alarmIntent())
                else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, until, alarmIntent())
            } catch (_: SecurityException) { manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, until, alarmIntent()) }
        }
        resumeTimer = scope.launch { delay((until - System.currentTimeMillis()).coerceAtLeast(0)); commands.send(Command(Intent().setAction(RECHECK).putExtra("route", "pause-timer"))) }
        refreshNotification(true)
    }
    private suspend fun resumeCapture() {
        if (playbackHeld) return
        val wasPaused = state == CaptureState.Paused || store.prefs.getLong("pausedUntil", 0) > 0
        resumeTimer?.cancel(); resumeTimer = null; cancelAlarm()
        store.prefs.edit().putLong("pausedUntil", 0).putLong("manualPausedUntil", 0).remove("quietName").commit()
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
        app.cardDevices.recording(true)
        try {
            InputSwitchLoop(playbackYieldSnapshots(app.inputs.snapshots, BluetoothYieldSettings(this).changes,
                AppGraph.playback.state.map { it.playing }), SystemClock::elapsedRealtime).run(onUnchanged = { route, note ->
                if (app.inputs.status.value.using.key == route.device.key) {
                    app.inputs.report(route.device, note)
                    refreshNotification(false)
                }
            }) { route, note ->
                if (note?.startsWith(MicYield.PLAYBACK) == true) {
                    store.event(WatchdogEventType.MicBusy, note)
                    app.inputs.report(route.device, note)
                    refreshNotification(false)
                    if (route.pausedForPlayback) {
                        controller.level(0f)
                        transition(CaptureState.MicBusy, detail = note)
                        try { awaitCancellation() } finally {
                            finishPlaybackYield(route)
                        }
                    }
                }
                try { listenTo(route, note) } finally {
                    if (note?.startsWith(MicYield.PLAYBACK) == true)
                        finishPlaybackYield(route)
                }
            }
        } finally { app.cardDevices.recording(false) }
    }
    private suspend fun finishPlaybackYield(route: InputRoute) = withContext(NonCancellable) {
        store.event(WatchdogEventType.MicReleased, "播放让麦等待结束，重新选择录音输入")
        app.inputs.report(route.device, null)
        refreshNotification(false)
    }
    private suspend fun listenTo(route: InputRoute, note: String?) {
        var busy = false
        var busyDetail: String? = null
        var reported = false
        // 卡 112：因“全是静音”重开的次数；中间收到过真声音就清零
        var zeroFails = 0
        var zeroFailAt = 0L
        var zeroAlerted = false
        while (currentCoroutineContext().isActive) {
            var segmenter: SpeechSegmenter? = null
            var timeline: AudioTimeline? = null
            var retryMs = CaptureTuning.MIC_RETRY_MS
            try {
                // 卡 173：没人看声波时每 200 毫秒读一批，有人看时保持原节奏
                val readMillis = { CaptureReadPolicy.durationMillis(controller.levelObserved) }
                val source: AudioSource = if (route.device.kind == InputKind.Phone) MicAudioSource(this, micClock::setOpen, readMillis)
                else if (route.device.kind == InputKind.CardDevice) app.cardDevices.audioSource(route.device.key)
                else ExternalAudioSource(this, route.device,
                    app.inputs.port(requireNotNull(route.portId))
                        ?: throw AudioSourceException(AudioSourceException.Reason.Disconnected), micClock::setOpen, readMillis)
                // 卡 143：设备音源可带设备时间和存档来源，并在人声检测后回报“已处理”
                timeline = source as? AudioTimeline
                val sensitivity = VadSensitivity.forInput(route.device.kind)
                SileroVad(this, sensitivity).use { vad ->
                    var levelAt = 0L
                    var levelPeak = 0f
                    var diagnosticAt = SystemClock.elapsedRealtime()
                    var diagnosticPeak = 0f
                    var speechSamples = 0L
                    source.events().collect { event ->
                        when (event) {
                            is AudioEvent.Busy -> {
                                // 让出麦克风期间：正在录的片段按正常结束收尾，恢复后从新片段开始计时
                                segmenter?.finish(); segmenter = null
                                if (!busy) {
                                    // 被占的那一刻猜一次；之后前台换了也不重猜，免得猜成后来打开的应用
                                    busyDetail = if (event.reason == MicYield.OTHER_APP) holders.describe() ?: event.reason else event.reason
                                    store.event(WatchdogEventType.MicBusy, if (busyDetail == event.reason) event.reason else "${event.reason}；$busyDetail")
                                    busy = true
                                }
                                controller.level(0f)
                                transition(CaptureState.MicBusy, detail = busyDetail)
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
                            refreshNotification(false)
                            if (previous.key != route.device.key || note != null)
                                store.inputSwitched("${previous.name} → ${route.device.name}；${note ?: "录音输入切换"}")
                            reported = true
                        }
                        val frame = (event as AudioEvent.Frame).samples
                        if (busy) { store.event(WatchdogEventType.MicReleased); busy = false }
                        if (zeroAlerted && CaptureHealth.lastSoundAt > zeroFailAt) {
                            zeroAlerted = false; zeroFails = 0
                            CaptureNotifications.cancelSilentMic(this@CaptureService)
                        }
                        val cutter = segmenter ?: SpeechSegmenter(app.segmentFiles.sink(
                            timeline?.frameEpochMillis ?: (System.currentTimeMillis() - frame.size * 1000L / AudioSource.SAMPLE_RATE),
                            timeline?.frameArchiveId ?: source.id, sensitivity.minSpeechSamples),
                            sensitivity.minSpeechSamples).also { segmenter = it }
                        vad.accept(frame) { pcm, speech, level ->
                            if (route.device.kind == InputKind.Phone) nightCapture?.accept(pcm, speech, System.currentTimeMillis() - pcm.size / 16)
                            cutter.accept(pcm, speech, AppGraph.playback.state.value.speakerPlaying)
                            val next = if (cutter.active) CaptureState.Recording else CaptureState.Listening
                            // 状态和采样在此线程串行；人声状态切换不改变 running 区间。
                            if (state != next) { state = next; controller.update(next) }
                            val elapsed = SystemClock.elapsedRealtime()
                            if (sensitivity.boostDetection) {
                                diagnosticPeak = maxOf(diagnosticPeak, level)
                                if (speech) speechSamples += pcm.size
                                if (elapsed - diagnosticAt >= 10_000) {
                                    android.util.Log.i("MicSensitivity", "kind=${route.device.kind} peakRms=${String.format(java.util.Locale.US, "%.6f", diagnosticPeak)} speechMs=${speechSamples * 1000 / AudioSource.SAMPLE_RATE}")
                                    diagnosticAt = elapsed; diagnosticPeak = 0f; speechSamples = 0
                                }
                            }
                            levelPeak = maxOf(levelPeak, level)
                            // 每 50 毫秒报一次这段时间里的最大音量，界面据此画真实波形
                            if (elapsed - levelAt >= 50) { controller.level(levelPeak); levelPeak = 0f; levelAt = elapsed }
                        }
                        timeline?.processed(frame.size)
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
                if (e.message == CaptureHealth.ZERO_PCM) {
                    if (CaptureHealth.lastSoundAt > zeroFailAt) zeroFails = 0
                    zeroFails++; zeroFailAt = SystemClock.elapsedRealtime()
                    store.event(WatchdogEventType.MicBusy, "${CaptureHealth.ZERO_PCM}，第 $zeroFails 次重开")
                    if (zeroFails >= CaptureHealth.ZERO_ALERT_AFTER) {
                        // 重开几次都没用（比如系统麦克风开关关着）：提醒一次，之后放慢重试，不反复折腾
                        if (!zeroAlerted) { CaptureNotifications.silentMic(this@CaptureService); zeroAlerted = true }
                        retryMs = CaptureHealth.ZERO_BACKOFF_MS
                    }
                } else if (!busy) { store.event(WatchdogEventType.MicBusy, "${e.reason}: ${e.message}"); busy = true }
                transition(CaptureState.MicBusy)
            } catch (e: Exception) {
                // 外接设备的意外错误也只换回手机，不停整个录音
                if (e is CancellationException || route.device.kind == InputKind.Phone) throw e
                throw AudioSourceException(AudioSourceException.Reason.Unknown, "${e.javaClass.simpleName}: ${e.message}")
            } finally {
                if (discardOnFinish) segmenter?.discard() else segmenter?.finish()
                timeline?.finished(discardOnFinish)
                controller.level(0f)
            }
            delay(retryMs)
        }
    }
    private fun running(s: CaptureState) = s == CaptureState.Listening || s == CaptureState.Recording || s == CaptureState.MicBusy
    /** 试用累计口径：只算真正收音的 Listening/Recording（MicBusy 让麦不计）。 */
    private fun accrues(s: CaptureState) = s == CaptureState.Listening || s == CaptureState.Recording
    private suspend fun transition(next: CaptureState, until: Long? = null, detail: String? = null, quietName: String? = null) {
        // MicBusy 边界也要补拍：让麦期间不累计试用，到期任务须挂起重排，不能照跑。
        if (initialized && (running(state) != running(next) || accrues(state) != accrues(next))) beat()
        nightCapture?.captureState(when(next) { CaptureState.Paused -> if(quietName != null) "停录时段内没有记录" else "已暂停录音"; CaptureState.MicBusy -> "麦克风被占用"; else -> null })
        state = next; controller.update(next, until, busyDetail = detail, quietName = quietName)
    }
    private fun folderImports() = if (AppGraph.isFolderImportReady)
        AppGraph.folderImports as? AndroidFolderImports else null
    private suspend fun beat() = beatMutex.withLock {
        val now = System.currentTimeMillis()
        store.dao.heartbeat(HeartbeatEntity(at = now, intervalStart = minOf(intervalStart, now), running = running(state), session = session))
        // 试用计量：心跳间隔即真实在录时长（仅真正收音的 Listening/Recording 累计，MicBusy 不计）；
        // 状态切换也会触发 beat，误差不超过一个心跳周期。
        // 增量用 elapsedRealtime 单调时钟：改系统时间不能让已用量涨水或清零。
        if (accrues(state) && AppGraph.isAuthReady) {
            val s = AppGraph.auth.store
            if (s.boundAccountId == null && s.sessionAccountId == null)
                s.guestTrialUsedMs = s.guestTrialUsedMs + (SystemClock.elapsedRealtime() - intervalStartElapsed).coerceAtLeast(0)
        }
        intervalStart = now; intervalStartElapsed = SystemClock.elapsedRealtime()
        store.prefs.edit().putLong("heartbeat", now).commit()
        scheduleTrialExpiry()
        folderImports()?.heartbeat(now)
        (applicationContext as app.resound.ResoundApp).let { application ->
            application.pipelineDiagnostics.heartbeat(AppGraph.digestTiming.mode.value == DigestMode.Interval)
            application.pipelineScheduler.trigger("心跳")
        }
    }
    /** 精确试用到期：心跳只是兜底，按真实剩余量排一个单调延迟 RECHECK——到点走授权复核正常收尾（保留片段）。 */
    private fun scheduleTrialExpiry() {
        trialExpiryJob?.cancel(); trialExpiryJob = null
        if (!AppGraph.isAuthReady || !accrues(state)) return
        val s = AppGraph.auth.store
        if (s.boundAccountId != null || s.sessionAccountId != null) return // 只给游客排期
        val remaining = (AuthGate.GUEST_TRIAL_MS - s.guestTrialUsedMs).coerceAtLeast(0)
        trialExpiryJob = scope.launch {
            delay(remaining)
            commands.send(Command(Intent().setAction(RECHECK).putExtra("route", "trial-expiry")))
        }
    }
    private suspend fun fail(detail: String) {
        if (initialized) { beat(); store.event(WatchdogEventType.ServiceStopped, "$detail；等待手动恢复，保留未正常停止标记") }
        if (state != CaptureState.NoPermission) { state = CaptureState.Stopped; controller.update(state) }
        releaseWakeLock(); heartbeat?.cancel(); trialExpiryJob?.cancel()
        CaptureNotifications.interrupted(this)
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    private fun plannedPause() = app.scene.decide(System.currentTimeMillis()).pausing
    private fun alarmIntent() = PendingIntent.getBroadcast(this, 10, Intent(this, CaptureResumeReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun cancelAlarm() { getSystemService(AlarmManager::class.java).cancel(alarmIntent()) }
    private fun refreshNotification(paused: Boolean) {
        if (!CaptureNotifications.permitted(this)) return
        try { NotificationManagerCompat.from(this).notify(CaptureNotifications.ONGOING, CaptureNotifications.ongoing(this, paused)) }
        catch (_: SecurityException) { /* 检查后权限被撤回；不能让通知刷新中断录音收尾。 */ }
    }
    /**
     * 足迹开着且有定位权限时带上定位类型；后台被系统拉起又没有“始终允许”时，
     * 带定位会被拒，这时退回只录音，不影响收音。
     */
    private fun startForegroundWithLocation() {
        val notification = CaptureNotifications.ongoing(this, store.prefs.getLong("pausedUntil", 0) > System.currentTimeMillis())
        val location = if (AppGraph.isLocationReady) AppGraph.location else null
        var tracking = false
        // 卡 143：有蓝牙连接权限时加“连接设备”类型，让外设连接在后台不被系统限制；被拒就退回原来的类型
        val connected = if (android.os.Build.VERSION.SDK_INT < 31 || androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        fun start(types: Int) {
            if (connected != 0) try { startForeground(CaptureNotifications.ONGOING, notification, types or connected); return }
                catch (_: RuntimeException) { }
            startForeground(CaptureNotifications.ONGOING, notification, types)
        }
        if (location?.settings?.wanted() == true) {
            try {
                start(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
                tracking = true
            } catch (_: RuntimeException) { }
        }
        if (!tracking) start(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        if (tracking) location?.tracker?.start() else location?.tracker?.stop()
    }
    private fun stopLocation() { if (AppGraph.isLocationReady) AppGraph.location.tracker.stop() }
    private fun releaseWakeLock() { wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null }
    override fun onDestroy() {
        (applicationContext as app.resound.ResoundApp).pipelineDiagnostics.serviceStopped()
        alive = false
        instance = null
        folderImports()?.recordingChanged()
        commands.close()
        while (true) {
            val pending = commands.tryReceive().getOrNull() ?: break
            pending.completion?.completeExceptionally(CancellationException("录音服务已停止"))
        }
        stopLocation()
        nightCapture?.close(); nightCapture = null
        unregisterReceiver(powerReceiver)
        if (!normalStop && controller.status.value.state != CaptureState.NoPermission) controller.update(CaptureState.Stopped)
        app.playbackSession.update(false, false, null, PlaybackOverride.Default, System.currentTimeMillis())
        scope.cancel(); dispatcher.close(); releaseWakeLock()
        if (!normalStop && initialized && !plannedPause()) CaptureNotifications.interrupted(this)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        @Volatile private var playbackHeld = false
        internal fun playbackHold(held: Boolean) {
            playbackHeld = held
            recheck()
        }
        /** 播放器等待录音协程的 finally 完成，包含 SCO 路由和通信模式释放。 */
        internal suspend fun awaitPlaybackRelease() {
            val service = instance ?: return
            if (!playbackHeld) return
            val done = CompletableDeferred<Unit>()
            service.commands.send(Command(Intent().setAction(RECHECK), done))
            done.await()
        }
        private const val DISCARD_CURRENT = "app.resound.capture.DISCARD_CURRENT"
        @Volatile private var instance: CaptureService? = null
        /** 进程内动作，未运行时直接返回，不启动服务。 */
        internal suspend fun discardCurrent() {
            val service = instance ?: return
            val done = CompletableDeferred<Unit>()
            service.commands.send(Command(Intent().setAction(DISCARD_CURRENT), done))
            done.await()
        }
        const val REMEMBER_PLAYBACK = "app.resound.capture.REMEMBER_PLAYBACK"
        const val RECHECK = "app.resound.capture.RECHECK"
        /** R4 隐私暂停：extra "on"=true 进入（不自动恢复），false 恢复 */
        const val PRIVACY = "app.resound.capture.PRIVACY"
        /** 规则改动、心跳和系统广播只通知已有服务，绝不在后台新建麦克风前台服务。 */
        /** 课堂模式开关或到点后刷新常驻通知的文字与按钮 */
        internal fun refreshOngoing() { instance?.let { it.refreshNotification(it.store.prefs.getLong("pausedUntil", 0) > System.currentTimeMillis()) } }
        /** 足迹开关或定位权限变化后调用（主线程）：重设前台类型并开关定位。服务没在跑就什么也不做。 */
        fun refreshLocation() {
            val service = instance ?: return
            try { service.startForegroundWithLocation() } catch (_: RuntimeException) { service.stopLocation() }
        }
        internal fun recheck() { instance?.commands?.trySend(Command(Intent().setAction(RECHECK))) }
        const val START = "app.resound.capture.START"
        const val STOP = "app.resound.capture.STOP"
        const val PAUSE = "app.resound.capture.PAUSE"
        const val RESUME = "app.resound.capture.RESUME"
        @Volatile var alive = false
            private set
    }
}
