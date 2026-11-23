@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class,kotlin.time.ExperimentalTime::class)
package app.resound.night

import android.content.*
import android.hardware.*
import android.os.*
import app.resound.AppGraph
import app.resound.platform.HistoricalZones
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.*
import kotlinx.serialization.encodeToString
import kotlin.time.Instant

/** 随 CaptureService 生命周期注册信号；不创建第二个麦克风。 */
class NightCapture(private val owner: AndroidNight,parent: CoroutineScope): AutoCloseable {
    private val scope=CoroutineScope(parent.coroutineContext+SupervisorJob(parent.coroutineContext[Job])+Dispatchers.Default.limitedParallelism(1))
    private val context=owner.context
    private val power=context.getSystemService(PowerManager::class.java)
    private val battery=context.getSystemService(BatteryManager::class.java)
    private val sensor=context.getSystemService(SensorManager::class.java)
    private val gate=Mutex()
    private var id: Long?=null;private var session: NightSession?=null
    private var writes = NightSessionWrites()
    private var processor: NightSoundProcessor?=null
    private var inference = newInferenceGate()
    private fun newInferenceGate() = NightInferenceGate { window, infer ->
        val started = SystemClock.elapsedRealtime()
        val protect = processor?.accept(window, infer) == true
        if (infer) session = session?.let { it.copy(calls=it.calls+1, inferenceMillis=it.inferenceMillis+SystemClock.elapsedRealtime()-started) }
        protect
    }
    private val candidates=NightCandidates()
    private var frames=NightFrames { dropped++ }
    @Volatile private var dropped=0
    @Volatile private var screenOff: Long?=if(power.isInteractive)null else System.currentTimeMillis()
    @Volatile private var screenOn: Long?=if(power.isInteractive)System.currentTimeMillis() else null
    /** 解锁、拔充电线：广播里记下，pulse 时写进这一夜 */
    private val points=kotlinx.coroutines.channels.Channel<NightSignal>(16)
    /** 结束过的那一夜（醒来那天）：同一个早上不再重开 */
    private var completed: Pair<String, String>? = null
    @Volatile private var activeWindow: SleepWindow? = null
    @Volatile private var motion="still"
    @Volatile private var motionAt=0L
    private var stillSince: Long?=System.currentTimeMillis()
    @Volatile private var lastVoice=System.currentTimeMillis()
    private var lastWindow=0L
    /** 卡 225：最近几晚的起床时刻，开始一夜时算一次 */
    private var usualWake: Int?=null
    private var modelHeld=false
    private var sensorRegistered=false
    /** 夜里时间段内已关屏、或这一夜还在进行时才处理音频；白天不做任何窗口计算。由 pulse 每 10 秒更新。 */
    @Volatile private var armed=false
    private var quietAt: Long?=null
    private var envelope: NightEnvelopeWriter? = null
    private val levels=mutableListOf<Int>()
    private var lastModeAt=0L
    @Volatile private var mode=NightMode.Off
    @Volatile private var lowPower=false
    private var previouslyEnabled=false
    @Volatile private var gapReason: String?=null
    private val motions=kotlinx.coroutines.channels.Channel<NightSignal>(64)
    private var previousGravity: DoubleArray?=null
    private val acceleration=mutableListOf<DoubleArray>()
    private var motionWindow=0L;private var highSince: Long?=null
    private val listener=object: SensorEventListener {
        override fun onAccuracyChanged(sensor: Sensor?,accuracy: Int) {}
        override fun onSensorChanged(event: SensorEvent) {
            val at=event.timestamp/1_000_000;val v=DoubleArray(3) { event.values[it].toDouble() };acceleration+=v
            if(motionWindow==0L)motionWindow=at
            if(at-motionWindow<10_000)return
            val mean=DoubleArray(3) { axis->acceleration.map { it[axis] }.average() }
            val variance=acceleration.sumOf { a->a.indices.sumOf { (a[it]-mean[it])*(a[it]-mean[it]) } }/acceleration.size
            var run=0;var longest=0
            acceleration.chunked(5).forEach { block ->
                val meanBlock=DoubleArray(3) { axis->block.map { it[axis] }.average() }
                val vBlock=block.sumOf { a->a.indices.sumOf { (a[it]-meanBlock[it])*(a[it]-meanBlock[it]) } }/block.size
                run=if(vBlock>NightTuning.PICKUP_VARIANCE)run+1 else 0;longest=maxOf(longest,run)
            }
            motion=NightMotion.classify(previousGravity?.let { NightMotion.angle(it,mean) } ?: 0.0,variance,longest*1000L)
            motionAt=System.currentTimeMillis()-(SystemClock.elapsedRealtime()-at);motions.trySend(NightSignal(motionAt,motionAt,motion));previousGravity=mean;acceleration.clear();motionWindow=at
            if (motion == "pickup") pulseForNap()
        }
    }
    private val receiver=object: BroadcastReceiver() {
        override fun onReceive(c: Context?,intent: Intent?) {
            val at=System.currentTimeMillis()
            when(intent?.action) {
                Intent.ACTION_SCREEN_OFF->{
                    screenOn?.let { points.trySend(NightSignal(it, at, "screen")) }
                    screenOff=at;screenOn=null
                }
                Intent.ACTION_SCREEN_ON,Intent.ACTION_USER_PRESENT->{screenOn=screenOn ?: at;screenOff=null}
            }
            when(intent?.action) {
                Intent.ACTION_USER_PRESENT->points.trySend(NightSignal(at,at,"unlock"))
                Intent.ACTION_POWER_DISCONNECTED->points.trySend(NightSignal(at,at,"unplug"))
            }
            if (intent?.action in setOf(Intent.ACTION_USER_PRESENT, Intent.ACTION_SCREEN_ON, Intent.ACTION_SCREEN_OFF)) pulseForNap()
        }
    }
    private fun pulseForNap() {
        if (activeWindow?.kind == SleepWindowKind.Nap) scope.launch { runPulse() }
    }
    private suspend fun runPulse() {
        try { gate.withLock { pulse(System.currentTimeMillis()) } }
        catch (e: CancellationException) { throw e }
        // #59：Error 也只记下、不让主循环静默死掉
        catch (e: Throwable) { app.resound.diagnostics.FailureNotes.record("night/signals",e); NightCaptureState.update { it.copy(lastError = e.javaClass.simpleName) } }
    }
    init {
        context.registerReceiver(receiver,IntentFilter().apply {addAction(Intent.ACTION_SCREEN_OFF);addAction(Intent.ACTION_SCREEN_ON);addAction(Intent.ACTION_USER_PRESENT);addAction(Intent.ACTION_POWER_DISCONNECTED)})
        scope.launch {
            // 上次进程中断：保留已落盘数据，补未知缺口，交给整理；不伪造连续录音。
            // #59：恢复出错只记下，不能拖死后面的主循环
            try { for(r in owner.store.dao.all().filter { it.state in setOf("Active","Provisional") }) {
                if(r.state=="Provisional")owner.store.deleteNight(r.id,"重启时清掉未确认的暂定记录")
                else if (r.kind == "nap") {
                    // 重启期间的解锁无法回放，只保留上一个检查点前确认的午睡。
                    val saved = owner.store.session(r)
                    owner.store.finishSession(r.id, saved, saved.lastAt)
                } else {
                    val at = minOf(System.currentTimeMillis(), owner.store.session(r).deadline ?: Long.MAX_VALUE)
                    owner.store.persistSession(r.id,owner.store.session(r).interruptedUntil(at),at,NightState.Interrupted)
                }
            } } catch (e: CancellationException) { throw e } catch (e: Throwable) { app.resound.diagnostics.FailureNotes.record("night/recover", e) }
            // 恢复完成后才允许首个 pulse 重开这一夜，避免旧检查点覆盖新会话。
            launch {
                while (isActive) {
                    runPulse()
                    delay(10_000)
                }
            }
            for(w in frames.windows)try { gate.withLock { consume(w) } } catch(e: CancellationException) { throw e } catch(e: Exception) { dropped++;app.resound.diagnostics.FailureNotes.record("night/window",e) }
        }
    }
    fun captureState(reason: String?) {gapReason=reason}
    fun accept(pcm: ShortArray,speech: Boolean,at: Long) {
        if(!owner.settings.value.enabled)return
        if(speech && !AppGraph.playback.state.value.speakerPlaying)lastVoice=at+pcm.size/16
        if(lowPower || !armed)return
        frames.accept(pcm,speech,at)
    }
    private suspend fun pulse(at: Long) {
        // #59：部分系统（如魅族）不把亮屏/熄屏广播送到，涛声就一直以为屏幕亮着、夜里不开始。每次直接问一下系统兜底。
        val interactive=power.isInteractive
        if(!interactive && screenOff==null) { screenOn?.let { points.trySend(NightSignal(it, at, "screen")) }; screenOff=at; screenOn=null; NightCaptureState.update { it.copy(polledScreenOff = it.polledScreenOff + 1) } }
        else if(interactive && screenOff!=null) { screenOn=at; screenOff=null }
        val s=owner.settings.value
        if(s.enabled && !previouslyEnabled)lastVoice=at
        previouslyEnabled=s.enabled
        lowPower=battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) in 0..9
        val zones=HistoricalZones.history();val minute=NightRules.minute(at,zones)
        val selected = SleepWindows.current(s, owner.automaticWindow.value, minute)
        val window = activeWindow ?: selected
        val date = selected?.wakeDate(NightRules.date(at, zones), minute)
        val key = selected?.let { date.toString() to it.recordKind }
        val shouldSensor = screenOff != null && selected != null && (session != null || key != completed)
        if(shouldSensor!=sensorRegistered) {
            if (shouldSensor) { stillSince = at; motion = "still" }
            sensorRegistered = if (shouldSensor) {
                val accelerometer = sensor.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
                // #59：没有加速度计或注册被拒时不当成已注册，下次再试；只按“静止”处理，夜里照常记录
                val ok = accelerometer != null && sensor.registerListener(listener,accelerometer,200_000,if (selected?.kind == SleepWindowKind.Nap) 0 else 30_000_000)
                if (!ok) app.resound.diagnostics.FailureNotes.record("night/sensor", IllegalStateException(if (accelerometer == null) "no accelerometer" else "register refused"))
                ok
            } else { sensor.unregisterListener(listener); false }
        }
        val motionEvents=buildList {while(true) {val event=motions.tryReceive().getOrNull() ?: break;add(event)}}
        val picked=motionEvents.any { it.kind=="pickup" }
        if(picked || motion!="still")stillSince=null else if(stillSince==null)stillSince=at
        val old=session
        if(old!=null) {
            val signals=old.signals.toMutableList()
            val alarm=context.getSystemService(android.app.AlarmManager::class.java).nextAlarmClock?.triggerTime
            val previous=signals.lastOrNull { it.kind=="alarm" }?.value?.toLong()
            // 响过的闹钟换成了下一个（或没了）＝闹钟被关掉或贪睡
            if(previous!=null && previous<=at && alarm!=previous && signals.none { it.kind=="alarm-off" && it.value==previous.toDouble() })signals+=NightSignal(at,at,"alarm-off",previous.toDouble())
            if(alarm!=null && previous!=alarm)signals+=NightSignal(at,at,"alarm",alarm.toDouble())
            while (true) {
                val point = points.tryReceive().getOrNull() ?: break
                if (point.end < old.provisionalAt) continue
                if (point.kind == "screen") signals.removeAll { it.kind == "screen" && it.start == point.start }
                signals += point
            }
            fun span(kind: String,start: Long,end: Long,text: String?=null) {
                val last=signals.lastOrNull { it.kind==kind }
                if(last!=null && start<=last.end+10_000) {signals.remove(last);signals+=last.copy(end=end)} else signals+=NightSignal(start,end,kind,text=text)
            }
            screenOn?.let { start ->
                signals.removeAll { it.kind == "screen" && it.start == start }
                signals += NightSignal(start, at, "screen")
            }
            signals+=motionEvents.filter { it.kind in setOf("pickup","turn") }
            if(AppGraph.playback.state.value.playing)span("playback",old.lastAt,at)
            if(gapReason!=null)span("gap",old.lastAt,at,gapReason)
            else if(lastWindow>0 && at-lastWindow>=300_000 && mode!=NightMode.Off)span("gap",lastWindow,at,"没有记录")
            session=old.copy(lastAt=at,signals=signals,endBattery=battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),dropped=dropped)
        }
        val floor=if (shouldSensor || session != null) owner.store.dao.notice("privacy-floor")?.at ?: Long.MIN_VALUE else Long.MIN_VALUE
        lastVoice=maxOf(lastVoice,floor)
        val input=NightInput(at,minute,screenOff,screenOn,stillSince,lastVoice,picked,battery.isCharging,battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it>=0 },power.currentThermalStatus>=PowerManager.THERMAL_STATUS_MODERATE,AppGraph.playback.state.value.playing,usualWake)
        if(session==null)while(points.tryReceive().isSuccess) Unit
        val inside = selected != null && window?.id == selected.id && at < (session?.deadline ?: Long.MAX_VALUE)
        val nap = window?.kind == SleepWindowKind.Nap
        when(NightRules.transition(inside, nap, session, input)) {
            NightTransition.Enter->run enter@{
                if (selected == null || date == null || key == completed) return@enter
                val existing = owner.store.dao.onDate(date.toString(), selected.recordKind)
                if (!NightRules.canReopen(existing?.state?.let(NightState::valueOf)) ||
                    existing != null && existing.windowId != selected.id) {
                    completed = key
                    return@enter
                }
                val bounds = selected.bounds(date, zones.zoneAt(at))
                val current = existing?.let { row ->
                    val saved = owner.store.session(row)
                    if (row.state == NightState.Interrupted.name) saved.interruptedUntil(at) else saved
                } ?: NightSession(maxOf(screenOff ?: at, floor, bounds.start.coerceAtMost(at)), at, lastAt=at, startBattery=input.battery,
                    nap=nap, deadline=bounds.end)
                usualWake = owner.store.usualWakeMinute(selected.recordKind, zones)
                id = existing?.id ?: owner.store.create(current, date, selected)
                session = current
                activeWindow = selected
                processor=NightSoundProcessor(owner,id!!) { w,scores ->
                    if(w.features.aboveBackground>=15 && (scores["Snoring"] ?: 0f)<NightTuning.SNORE && !AppGraph.playback.state.value.playing)session=session?.let {it.copy(signals=it.signals+NightSignal(w.start,w.start+2_000,"loud"))}
                }
                envelope=NightEnvelopeWriter(owner.store.db,id!!,at)
                owner.model.beginNight();modelHeld=true
            }
            NightTransition.Confirm->{session=session!!.copy(confirmedAt=at);if(s.lowBatteryReminder && !input.charging && (input.battery ?: 100)<30)NightNotifications.show(context,"night-battery-${id}","电量 ${input.battery}%，建议充电，夜里记录更完整")}
            NightTransition.Cancel->{id?.let {owner.store.deleteNight(it,"暂定后又用了手机，取消")};reset()}
            NightTransition.Finish->{
                val current = session!!
                val end = minOf(at, current.deadline ?: at)
                val wake = if (nap) NapRules.wake(current) ?: end
                    else WakeRules.decide(current,at,minute) ?: WakeRules.atWindowEnd(current,at,minute)
                if (wake != null) session=current.copy(signals=current.signals+NightSignal(end,end,"wake",wake.coerceAtMost(end).toDouble()))
                owner.store.dao.get(id!!)?.let { completed = it.date to it.kind }
                finish(if (nap && wake != null) minOf(wake, end) else end)
            }
            else->Unit
        }
        if(at-lastModeAt>=300_000 || mode==NightMode.Off) {
            val modelReady = owner.model.model.value == app.resound.envsound.EnvModelState.Ready
            if (!modelReady && session != null) owner.model.downloadForNight()
            // 识别模型没下好时整晚识别会每个窗口都失败：改成先存后认，模型好了再补做
            val next = NightRules.mode(s,input.battery,input.charging,input.hot).let { if (it == NightMode.Full && !modelReady) NightMode.Deferred else it }
            if (mode == NightMode.Full && next != mode && id?.let { owner.store.dao.get(it) } != null) inference.flush()
            mode=next;lastModeAt=at
        }
        session?.let { current->
            val spans=current.modes.toMutableList();val last=spans.lastOrNull()
            if(last?.mode==mode)spans[spans.lastIndex]=last.copy(end=at) else spans+=NightModeSpan(at,at,mode)
            session=current.copy(modes=spans)
            val state = if (current.confirmedAt == null) NightState.Provisional else NightState.Active
            writes.checkpoint(state, SystemClock.elapsedRealtime()) { owner.store.persistSession(id!!,session!!,at,state) }
            owner.store.setLive(NightLive(if(current.confirmedAt==null)NightState.Provisional else NightState.Active,mode,if (nap) current.provisionalAt else current.screenOff,if (nap) NapRules.wake(current) else WakeRules.pending(current),activeWindow))
        }
        armed=shouldSensor || session!=null
        NightCaptureState.update { it.copy(lastPulseAt = at, screenOff = screenOff != null, window = selected?.name, shouldSensor = shouldSensor,
            sensorRegistered = sensorRegistered, session = session?.let { if (it.confirmedAt == null) "暂定" else "进行中" }, mode = mode.name) }
    }
    private suspend fun consume(w: NightWindow) {
        lastWindow=w.start+2_000
        if(w.speech && !AppGraph.playback.state.value.speakerPlaying)lastVoice=w.start+2_000
        if(session==null || w.start < session!!.provisionalAt || w.start >= (session!!.deadline ?: Long.MAX_VALUE))return
        if(id?.let { owner.store.dao.get(it) }==null) { reset();return }
        val signals=session!!.signals.toMutableList()
        if(w.speech && !AppGraph.playback.state.value.speakerPlaying)signals+=NightSignal(w.start,w.start+2_000,"voice")
        if(screenOff!=null && motion=="still" && (!w.speech || AppGraph.playback.state.value.speakerPlaying) && w.features.aboveBackground<15) {
            if(quietAt==null)quietAt=w.start
            signals.removeAll { it.kind=="quiet" && it.start==quietAt };signals+=NightSignal(quietAt!!,w.start+2_000,"quiet")
        } else quietAt=null
        if(mode!=NightMode.Off) {
            repeat(4) { index ->
                val rms=kotlin.math.sqrt((index*8000 until (index+1)*8000).sumOf { val v=w.pcm[it]/32768.0;v*v }/8000)
                levels+=NightFrames.db(rms)
            }
            if(levels.size>=10) {
                val point=levels.take(10);repeat(10) { levels.removeAt(0) }
                envelope?.append(point.max().toByte(),NightRules.median(point.map(Int::toLong)).toByte(),w.background.toByte())
                signals+=NightSignal(w.start,w.start+2_000,"background",w.background.toDouble())
            }
        }
        session=session!!.copy(signals=signals)
        when(mode) {
            NightMode.Full->inference.offer(w)
            NightMode.Deferred->candidates.accept(w).forEach {saveCandidate(it)}
            else->Unit
        }
    }
    private suspend fun saveCandidate(windows: List<NightWindow>) {
        if(windows.isEmpty() || session==null)return
        if(session!!.candidateBytes>=NightTuning.CANDIDATE_LIMIT)return
        val pcm=ShortArray(windows.sumOf { it.pcm.size });var at=0;windows.forEach {it.pcm.copyInto(pcm,at);at+=it.pcm.size}
        val path=owner.files.encode(id!!,windows.first().start,pcm)
        val size=owner.files.resolve(path).length()
        if(session!!.candidateBytes+size>NightTuning.CANDIDATE_LIMIT) {owner.files.resolve(path).delete();session=session!!.copy(candidateBytes=NightTuning.CANDIDATE_LIMIT);return}
        owner.store.dao.insertClip(NightClipRow(nightId=id!!,start=windows.first().start,end=windows.last().start+2_000,path=path,scores=byteArrayOf(),features=owner.store.json.encodeToString(CandidateFeatures(windows.map {CandidateWindow(it.start,it.features)})),confirmed=false,expiresAt=windows.last().start+7*86_400_000L,candidate=true))
        session=session!!.copy(candidateBytes=session!!.candidateBytes+size)
    }
    private suspend fun finish(at: Long) {
        inference.flush()
        saveCandidate(candidates.finish());processor?.finish()
        envelope?.finish()
        id?.let {owner.store.finishSession(it,session!!,at)}
        reset();NightWork.run(context)
    }
    private fun reset() {activeWindow=null;candidates.finish();levels.clear();lastWindow=0L;envelope=null;writes=NightSessionWrites();inference=newInferenceGate();id=null;session=null;processor=null;quietAt=null;if(modelHeld) {owner.model.endNight();modelHeld=false};owner.store.setLive(NightLive(NightState.Waiting,null,null))}
    override fun close() {sensor.unregisterListener(listener);context.unregisterReceiver(receiver);frames.close();scope.cancel();if(modelHeld) {owner.model.endNight();modelHeld=false}}
}

/** #59：夜里主循环的实时状态，写进“问题记录”，下次一眼看出卡在哪一步 */
internal object NightCaptureState {
    data class Snapshot(val startedAt: Long = System.currentTimeMillis(), val lastPulseAt: Long? = null, val screenOff: Boolean? = null,
        val window: String? = null, val shouldSensor: Boolean = false, val sensorRegistered: Boolean = false, val session: String? = null,
        val mode: String? = null, val polledScreenOff: Int = 0, val lastError: String? = null)
    @Volatile var value = Snapshot(); private set
    @Synchronized fun update(change: (Snapshot) -> Snapshot) { value = change(value) }
    fun describe(): String = value.let { v ->
        "夜里实时状态：启动=${v.startedAt} 上次检查=${v.lastPulseAt ?: "从未"} 屏幕=${when (v.screenOff) { true -> "关"; false -> "开"; null -> "未知" }} " +
            "时段=${v.window ?: "不在时段内"} 应开传感器=${v.shouldSensor} 已注册=${v.sensorRegistered} 这一夜=${v.session ?: "无"} 档位=${v.mode} " +
            "靠查询补到熄屏=${v.polledScreenOff}次 最近错误=${v.lastError ?: "无"}"
    }
}
