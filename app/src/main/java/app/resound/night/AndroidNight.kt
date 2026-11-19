package app.resound.night

import android.content.Context
import app.resound.AppGraph
import app.resound.ResoundApp
import app.resound.data.CaptureDatabase
import app.resound.envsound.LocalEnvSoundRepository
import app.resound.platform.FileRef
import app.resound.platform.HistoricalZones
import app.resound.push.DayThreadPushScheduler
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class AndroidNight(val context: Context,val store: NightStore,val files: NightFiles,val model: LocalEnvSoundRepository) : NightRepository by store {
    val insights by lazy { NightInsightsAndroid(context,this) }
    fun capture(scope: CoroutineScope)=NightCapture(this,scope)
    suspend fun cleanup() { (AppGraph.privacy as? app.resound.privacy.RoomPrivacyActions)?.retryFileCleanup() }
    suspend fun process() {
        store.expire(System.currentTimeMillis())
        val power=context.getSystemService(android.os.PowerManager::class.java)
        val battery=context.getSystemService(android.os.BatteryManager::class.java)
        for(row in store.dao.all().filter { it.state=="Ready" && System.currentTimeMillis()-it.end<3*86_400_000L }) {
            store.refreshSpeech(row.id);store.recompute(row.id)
        }
        // 卡 210：更早的夜晚只在有重复鼾声中断时重算一次（合并后就不再触发）
        for(row in store.dao.all().filter { it.state=="Ready" && System.currentTimeMillis()-it.end>=3*86_400_000L }) {
            val breaks=store.dao.events(row.id).filter { it.kind=="SnoreBreak" }.map { it.id to NightSpan(it.start,it.end) }
            if(SnoreBreaks.duplicates(breaks).isNotEmpty())store.recompute(row.id)
        }
        for(row in store.dao.all().filter { it.state==NightState.Processing.name || it.state==NightState.Interrupted.name }) {
            val candidates=store.dao.clips(row.id).filter { it.candidate && it.path!=null }
            if(candidates.isNotEmpty() && !(battery.isCharging || !power.isInteractive && battery.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)>=40))continue
            val processor=NightSoundProcessor(this,row.id)
            model.beginNight()
            try {
                val seen=mutableSetOf<Long>()
                for(c in candidates) {
                    val pcm=files.read(c.path!!)
                    val meta=store.json.decodeFromString<CandidateFeatures>(c.features)
                    for(w in meta.windows) {
                        val offset=((w.at-c.start)*16).toInt()
                        if(offset<0 || offset+NightTuning.WINDOW>pcm.size || !seen.add(w.at))continue
                        val samples=pcm.copyOfRange(offset,offset+NightTuning.WINDOW)
                        val rms=kotlin.math.sqrt(samples.sumOf { val x=it/32768.0;x*x }/samples.size)
                        processor.accept(NightWindow(w.at,samples,false,rms,NightFrames.db(rms)-w.features.aboveBackground,w.features))
                    }
                }
                processor.finish()
                // 结果成功落盘后才删除候选；中断时保留候选供重做。事件按起止去重。
                for(c in candidates) {
                    c.path?.let { store.db.privacy().queueCleanup(app.resound.data.PrivacyFileCleanup(files.cleanupPath(it))) }
                    store.dao.updateClip(c.copy(path=null,candidate=false))
                }
                store.refreshSpeech(row.id);store.recompute(row.id);store.notifyChanged();cleanup()
                // 夜里整理好：推一次当天文档，“昨晚”早上就能看到（只这一次，不随后续变化反复推）
                if (row.kind == "night") (app.resound.AppGraph.push as? app.resound.push.AndroidPushChannels)?.let { runCatching { it.recordsChanged() } }
            } finally {model.endNight()}
        }
    }
    suspend fun maintainInsights() = insights.maintain()
    companion object {
        fun create(context: Context,db: CaptureDatabase,scope: CoroutineScope,model: LocalEnvSoundRepository): AndroidNight {
            val prefs=context.getSharedPreferences("night",0);val json=Json { ignoreUnknownKeys=true;encodeDefaults=true };val files=NightFiles(context)
            val initial=runCatching { json.decodeFromString<NightSettings>(prefs.getString("settings",null) ?: "{}") }.getOrDefault(NightSettings())
            val app=context.applicationContext as ResoundApp
            val store=NightStore(db,scope,initial,{ prefs.edit().putString("settings",json.encodeToString(it)).commit() },{ model.downloadModel() },
                System::currentTimeMillis,HistoricalZones.history,files::exists,{ FileRef(files.cleanupPath(it)) },{ row->
                    val data=NightFiles.unpack(row.points);val n=data.size/3
                    NightEnvelope(row.start,5_000,ByteArray(n) { data[it*3] },ByteArray(n) { data[it*3+1] },ByteArray(n) { data[it*3+2] })
                },{ (AppGraph.privacy as? app.resound.privacy.RoomPrivacyActions)?.retryFileCleanup();Unit },{ DayThreadPushScheduler.refresh(context) },app.quietSchedule.rules,
                privacyChanged = { DayThreadPushScheduler.afterDeletion(context) },
                log = { NightLog.add(context, it) })
            return AndroidNight(context,store,files,model).also { AppGraph.nightInsights=it.insights.repository;NightWork.schedule(context) }
        }
    }
}
