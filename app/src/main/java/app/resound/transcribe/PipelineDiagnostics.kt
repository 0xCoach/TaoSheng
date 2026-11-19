package app.resound.transcribe

import android.content.Context
import android.app.usage.UsageStatsManager
import android.os.PowerManager
import app.resound.scheduling.BackgroundRecords
import app.resound.scheduling.BackgroundRun
import app.resound.scheduling.BackgroundSystem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneId

internal class PipelineDiagnostics(private val context: Context) {
    private val prefs = context.getSharedPreferences("pipeline-runs", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val signals = BackgroundSystemInfo.read(context)
    val system = BackgroundSystem.identify(signals)
    private val rows = MutableStateFlow(BackgroundRecords.retain(BackgroundRecords.decode(prefs.getString("runs", "[]")!!), System.currentTimeMillis()))
    private val mutableHealth = MutableStateFlow(BackgroundRecords.health(rows.value, day(System.currentTimeMillis()), system.label, false))
    val backgroundHealth = mutableHealth.asStateFlow()
    private val periodicAt = MutableStateFlow(prefs.getLong("periodicAt", 0))
    private val serviceSince = MutableStateFlow<Long?>(null)
    fun systemPeriodic() {
        val now = System.currentTimeMillis()
        periodicAt.value = now
        mutableHealth.value = mutableHealth.value.copy(suspectedSystemRestriction = false)
        prefs.edit().putLong("periodicAt", now).apply()
    }
    fun refresh() {
        mutableHealth.value = BackgroundRecords.health(rows.value, day(System.currentTimeMillis()), system.label, mutableHealth.value.suspectedSystemRestriction)
    }
    fun serviceStopped() {
        serviceSince.value = null
        mutableHealth.value = mutableHealth.value.copy(suspectedSystemRestriction = false)
    }
    fun heartbeat(intervalMode: Boolean) {
        val now = System.currentTimeMillis()
        if (serviceSince.value == null) serviceSince.value = now
        mutableHealth.value = BackgroundRecords.health(rows.value, day(now), system.label,
            BackgroundRecords.suspectedRestriction(intervalMode, now, serviceSince.value ?: now, periodicAt.value))
    }
    fun round(source: String, mode: String) = PipelineRound(System.currentTimeMillis(), source, mode)

    suspend fun record(round: PipelineRound) = record(round.at, round.source, round.mode, round.dueAt,
        round.result, android.os.SystemClock.elapsedRealtime() - round.elapsedAt, round.lockWaitMillis,
        round.stages.toMap(), round.reason, round.id)

    suspend fun record(at: Long, source: String, mode: String, dueAt: Long, result: String, duration: Long,
                       lockWait: Long = 0, stages: Map<String, Long> = emptyMap(), reason: String = "",
                       runId: String = "$at:$source:$mode") = mutex.withLock {
        val row = BackgroundRun(at, day(at), source, mode, dueAt, result, duration, lockWait, stages, reason, runId)
        rows.value = BackgroundRecords.retain(rows.value.filterNot { it.runId == runId } + row, System.currentTimeMillis())
        prefs.edit().putString("runs", BackgroundRecords.encode(rows.value)).apply()
        mutableHealth.value = BackgroundRecords.health(rows.value, day(System.currentTimeMillis()), system.label, mutableHealth.value.suspectedSystemRestriction)
    }

    suspend fun failure(source: String, reason: String) {
        val round = round(source, "交接")
        round.result = "未开始"
        round.reason = reason
        record(round)
    }
    suspend fun dump(): String = mutex.withLock {
        val current = BackgroundRecords.retain(rows.value, System.currentTimeMillis())
        val bucket = runCatching { context.getSystemService(UsageStatsManager::class.java).appStandbyBucket }.getOrNull()
        val battery = runCatching { context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName) }.getOrNull()
        "系统=${system.label} 信号=$signals 待机分组=$bucket 忽略电池优化=$battery 最近系统周期派发=${periodicAt.value}\n" +
            BackgroundRecords.summary(current) + "\n" + current.filterIndexed { i, r -> r.result != "成功" || i >= current.size - 30 }.joinToString("\n") {
                "时间=${it.at} 来源=${it.source} 模式=${it.mode} 等锁=${it.lockWaitMillis}ms 应到=${it.dueAt} 结果=${it.result} 耗时=${it.durationMillis}ms " +
                    listOf("转写", "声纹", "整理").joinToString(" ") { stage -> "$stage=${it.stages[stage] ?: 0}ms" } +
                    " 原因=${it.reason}"
            }
    }
    private fun day(at: Long) = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate().toString()
}

/** 一次 offer 的生命周期；开始和结束持久化同一个 id，进程中断也保留一行。 */
internal class PipelineRound(val at: Long, val source: String, var mode: String) {
    val id: String = java.util.UUID.randomUUID().toString()
    val elapsedAt: Long = android.os.SystemClock.elapsedRealtime()
    var dueAt: Long = at
    var lockWaitMillis: Long = 0
    val stages = mutableMapOf<String, Long>()
    var result: String = "执行中"
    var reason: String = ""
    fun note(value: String) { reason = listOf(reason, value).filter { it.isNotBlank() }.joinToString("；") }
}
