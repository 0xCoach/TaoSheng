@file:OptIn(kotlin.time.ExperimentalTime::class)
package app.resound.night

import android.content.Context
import android.os.BatteryManager
import app.resound.AppGraph
import app.resound.digest.AndroidDigestSettings
import app.resound.digest.LlmClient
import app.resound.platform.HistoricalZones
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Instant

class NightInsightsAndroid(private val context: Context,private val owner: AndroidNight) {
    private val share=NightShare(context,owner)
    val repository=NightInsightsStore(owner.store,System::currentTimeMillis,HistoricalZones.history,share::export)
    private val dao get()=owner.store.dao
    private val json get()=owner.store.json
    private val training = PersonalNightTraining(owner.store,NightFiles::scores) { signature ->
        java.security.MessageDigest.getInstance("SHA-256").digest(signature.encodeToByteArray())
            .joinToString("") { "%02x".format(it) }
    }
    private val gate=Mutex()
    suspend fun maintain() = gate.withLock {
        repository.initialize();share.cleanup()
        val at=System.currentTimeMillis();val zones=HistoricalZones.history()
        for(row in dao.nightHistory()) {
            repository.refreshDreams(row.id)
            val summary=owner.store.summary(row)
            if(summary.state!=NightState.Ready)continue
            if(at-row.end<3*86_400_000L) {
                val start=summary.date.minus(1,DateTimeUnit.DAY).atTime(17,0).toInstant(zones.zoneAt(row.start)).toEpochMilliseconds()
                val events=owner.store.db.memory().events(start,row.start).first()
                val text=buildString {events.forEach {appendLine(it.title);appendLine(it.summary)};owner.store.db.capture().segments(start,row.start).first().forEach {s->owner.store.db.voiceprint().lines(s.id).forEach {appendLine(it.text)}}}
                repository.suggest(row.id,text)
            }
            val wake=summary.times?.wake
            if(owner.settings.value.dreamPrompt && wake!=null && at>=wake && at-wake<=1_800_000 && dao.notice("dream-${row.id}")==null) {
                dao.notice(NightNoticeRow("dream-${row.id}",at,false));NightNotifications.dream(context,row.id)
            }
        }
        val rows=dao.nightHistory().map(owner.store::summary)
        val previous=dao.notice("break")
        if(owner.settings.value.breakReminder && SnoreBreaks.shouldRemind(rows,at,previous?.at,previous?.muted==true)) {
            rows.firstOrNull()?.let {dao.notice(NightNoticeRow("notice-night-${it.id}",at,false));dao.notice(NightNoticeRow("break",at,false))}
        }
        training.train(at,context.getSystemService(BatteryManager::class.java).isCharging)
        repository.refreshWeekPrivacy()
        if(owner.settings.value.weeklyReport)for(report in repository.weeks(at)) {
            // 先持久化唯一周请求。进程中断后不重发润色请求，保留规则稿，避免重复付费。
            val state=NightWeekState("night-week:${report.weekStart}","polishing",report.markdown)
            dao.week(NightWeekRow(report.weekStart.toString(),json.encodeToString(report),json.encodeToString(state)))
            val polished=polish(report)
            repository.finishWeek(polished,state)
            if(owner.settings.value.includeInPush)NightNotifications.show(context,"night-week-${report.weekStart}","上周的夜里周报已整理好")
            owner.store.notifyChanged()
        }
    }
    private suspend fun polish(report: WeeklyReport): WeeklyReport {
        if(!AppGraph.isDigestSettingsReady || !AppGraph.isPromptsReady)return report
        val settings=AppGraph.digestSettings as? AndroidDigestSettings ?: return report
        if(!settings.hasKey.value)return report // 此入口已经包含 canUseWithoutKey / 提供方鉴权判断。
        return try {
            val client=LlmClient(requestHeaders=settings.providers::requestHeaders)
            val prompt=AppGraph.prompts.text("night_week")
            val response=settings.failover.chat("夜里周报",settings.preset,settings.readKey(),prompt,report.markdown,settings.digestRequest(client))
            owner.store.db.memory().addUsage(java.time.LocalDate.now().toString(),response.promptTokens,response.completionTokens,app.resound.usage.UsagePurpose.Review)
            val text=Json.parseToJsonElement(response.content).let {it as? kotlinx.serialization.json.JsonObject}?.get("text")?.let {it as? kotlinx.serialization.json.JsonPrimitive}?.content?.takeIf {it.isNotBlank()} ?: return report
            report.copy(markdown=report.markdown+"\n\n## 本周小结\n"+text,polished=true)
        } catch(e: CancellationException) {throw e} catch(e: Exception) {app.resound.diagnostics.FailureNotes.record("night/weekly-polish",e);report}
    }
}
