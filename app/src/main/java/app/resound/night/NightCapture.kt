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
    private var processor: NightSoundProcessor?=null
    private val candidates=NightCandidates()
    private var frames=NightFrames { dropped++ }
    @Volatile private var dropped=0
    @Volatile private var screenOff: Long?=if(power.isInteractive)null else System.currentTimeMillis()
    @Volatile private var screenOn: Long?=if(power.isInteractive)System.currentTimeMillis() else null
    @Volatile private var motion="still"
    @Volatile private var motionAt=0L
    private var stillSince: Long?=System.currentTimeMillis()
    @Volatile private var lastVoice=System.currentTimeMillis()
    private var lastWindow=0L
    private var modelHeld=false
    private var sensorRegistered=false
    private var quietAt: Long?=null
    private val envelope=mutableListOf<Byte>()
    private val levels=mutableListOf<Int>()
    private var envelopeStart=0L
    private var envelopeAt=0L
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
        }
    }
    private val receiver=object: BroadcastReceiver() {
        override fun onReceive(c: Context?,intent: Intent?) {
            val at=System.currentTimeMillis()
            when(intent?.action) {
                Intent.ACTION_SCREEN_OFF->{screenOff=at;screenOn=null}
                Intent.ACTION_SCREEN_ON,Intent.ACTION_USER_PRESENT->{screenOn=screenOn ?: at;screenOff=null}
            }
        }
    }
    init {
        context.registerReceiver(receiver,IntentFilter().apply {addAction(Intent.ACTION_SCREEN_OFF);addAction(Intent.ACTION_SCREEN_ON);addAction(Intent.ACTION_USER_PRESENT)})
        scope.launch {
            // 上次进程中断：保留已落盘数据，补未知缺口，交给整理；不伪造连续录音。
            for(r in owner.store.dao.all().filter { it.state in setOf("Active","Provisional") }) {
                if(r.state=="Provisional")owner.store.deleteNight(r.id)
                else owner.store.persistSession(r.id,owner.store.session(r).let { it.copy(signals=it.signals+NightSignal(it.lastAt,System.currentTimeMillis(),"gap",text="没有记录")) },System.currentTimeMillis(),NightState.Interrupted)
            }
            for(w in frames.windows)try { gate.withLock { consume(w) } } catch(e: CancellationException) { throw e } catch(e: Exception) { dropped++;app.resound.diagnostics.FailureNotes.record("night/window",e) }
        }
        scope.launch {while(isActive) {try { gate.withLock { pulse(System.currentTimeMillis()) } } catch(e: CancellationException) { throw e } catch(e: Exception) { app.resound.diagnostics.FailureNotes.record("night/signals",e) };delay(10_000)}}
    }
    fun captureState(reason: String?) {gapReason=reason}
    fun accept(pcm: ShortArray,speech: Boolean,at: Long) {
        if(!owner.settings.value.enabled)return
        if(speech && !AppGraph.playback.state.value.speakerPlaying)lastVoice=at+pcm.size/16
        if(lowPower)return
        frames.accept(pcm,speech,at)
    }
    private suspend fun pulse(at: Long) {
        val s=owner.settings.value
        if(s.enabled && !previouslyEnabled)lastVoice=at
        previouslyEnabled=s.enabled
        lowPower=battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) in 0..9
        val zones=HistoricalZones.history();val minute=NightRules.minute(at,zones)
        val shouldSensor=s.enabled && screenOff!=null && NightRules.inWindow(minute,s.windowStartMinute,s.windowEndMinute)
        if(shouldSensor!=sensorRegistered) {
            if(shouldSensor)sensor.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sensor.registerListener(listener,it,200_000,30_000_000) } else sensor.unregisterListener(listener)
            sensorRegistered=shouldSensor
        }
        val motionEvents=buildList {while(true) {val event=motions.tryReceive().getOrNull() ?: break;add(event)}}
        val picked=motionEvents.any { it.kind=="pickup" }
        if(motion!="still")stillSince=null else if(stillSince==null)stillSince=at
        val old=session
        if(old!=null) {
            val signals=old.signals.toMutableList()
            context.getSystemService(android.app.AlarmManager::class.java).nextAlarmClock?.triggerTime?.let { alarm ->
                if(signals.lastOrNull { it.kind=="alarm" }?.value!=alarm.toDouble())signals+=NightSignal(at,at,"alarm",alarm.toDouble())
            }
            fun span(kind: String,start: Long,end: Long,text: String?=null) {
                val last=signals.lastOrNull { it.kind==kind }
                if(last!=null && start<=last.end+10_000) {signals.remove(last);signals+=last.copy(end=end)} else signals+=NightSignal(start,end,kind,text=text)
            }
            screenOn?.let {span("screen",it,at)}
            signals+=motionEvents.filter { it.kind in setOf("pickup","turn") }
            if(AppGraph.playback.state.value.playing)span("playback",old.lastAt,at)
            if(gapReason!=null)span("gap",old.lastAt,at,gapReason)
            else if(lastWindow>0 && at-lastWindow>=300_000 && mode!=NightMode.Off)span("gap",lastWindow,at,"没有记录")
            session=old.copy(lastAt=at,signals=signals,endBattery=battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),dropped=dropped)
        }
        val floor=owner.store.dao.notice("privacy-floor")?.at ?: Long.MIN_VALUE
        lastVoice=maxOf(lastVoice,floor)
        val input=NightInput(at,minute,screenOff,screenOn,stillSince,lastVoice,picked,battery.isCharging,battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it>=0 },power.currentThermalStatus>=PowerManager.THERMAL_STATUS_MODERATE,AppGraph.playback.state.value.playing)
        when(NightRules.transition(s,session,input)) {
            NightTransition.Enter->{
                val date=NightRules.date(at,zones).let { if(minute>=s.windowStartMinute && s.windowEndMinute<=s.windowStartMinute)it.plus(1,DateTimeUnit.DAY) else it }
                val existing=owner.store.dao.onDate(date.toString())
                session=existing?.let(owner.store::session) ?: NightSession(maxOf(screenOff ?: at,floor),at,lastAt=at,startBattery=input.battery)
                id=existing?.id ?: owner.store.create(session!!,date)
                processor=NightSoundProcessor(owner,id!!) { w,scores ->
                    if(w.features.aboveBackground>=15 && (scores["Snoring"] ?: 0f)<NightTuning.SNORE && !AppGraph.playback.state.value.playing)session=session?.let {it.copy(signals=it.signals+NightSignal(w.start,w.start+2_000,"loud"))}
                };envelope.clear();envelopeStart=at;envelopeAt=at
                owner.model.beginNight();modelHeld=true
            }
            NightTransition.Confirm->{session=session!!.copy(confirmedAt=at);if(s.lowBatteryReminder && !input.charging && (input.battery ?: 100)<30)NightNotifications.show(context,"night-battery-${id}","电量 ${input.battery}%，建议充电，夜里记录更完整")}
            NightTransition.Cancel->{id?.let {owner.store.deleteNight(it)};reset()}
            NightTransition.Finish->{finish(at)}
            else->Unit
        }
        if(at-lastModeAt>=300_000 || mode==NightMode.Off) {mode=NightRules.mode(s,input.battery,input.charging,input.hot);lastModeAt=at}
        session?.let { current->
            val spans=current.modes.toMutableList();val last=spans.lastOrNull()
            if(last?.mode==mode)spans[spans.lastIndex]=last.copy(end=at) else spans+=NightModeSpan(at,at,mode)
            session=current.copy(modes=spans)
            owner.store.persistSession(id!!,session!!,at,if(current.confirmedAt==null)NightState.Provisional else NightState.Active)
            owner.store.setLive(NightLive(if(current.confirmedAt==null)NightState.Provisional else NightState.Active,mode,current.screenOff))
        }
    }
    private suspend fun consume(w: NightWindow) {
        lastWindow=w.start+2_000
        if(w.speech && !AppGraph.playback.state.value.speakerPlaying)lastVoice=w.start+2_000
        if(session==null)return
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
                envelope+=point.max().toByte();envelope+=NightRules.median(point.map(Int::toLong)).toByte();envelope+=w.background.toByte();envelopeAt+=5_000
                owner.store.dao.envelope(NightEnvelopeRow(id!!,envelopeStart,NightFiles.envelope(envelope.toByteArray())))
                signals+=NightSignal(w.start,w.start+2_000,"background",w.background.toDouble())
            }
        }
        session=session!!.copy(signals=signals)
        when(mode) {
            NightMode.Full->{val start=SystemClock.elapsedRealtime();processor?.accept(w);session=session!!.copy(calls=session!!.calls+1,inferenceMillis=session!!.inferenceMillis+SystemClock.elapsedRealtime()-start)}
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
        saveCandidate(candidates.finish());processor?.finish()
        id?.let {owner.store.persistSession(it,session!!,at,NightState.Processing)}
        reset();NightWork.run(context)
    }
    private fun reset() {id=null;session=null;processor=null;quietAt=null;if(modelHeld) {owner.model.endNight();modelHeld=false};owner.store.setLive(NightLive(NightState.Waiting,null,null))}
    override fun close() {sensor.unregisterListener(listener);context.unregisterReceiver(receiver);frames.close();scope.cancel();if(modelHeld) {owner.model.endNight();modelHeld=false}}
}
