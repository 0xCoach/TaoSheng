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
    private val prefs = context.getSharedPreferences("pipeline-triggers-188", Context.MODE_PRIVATE)
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
    suspend fun record(at: Long, source: String, mode: String, dueAt: Long, result: String, duration: Long) = mutex.withLock {
        rows.value = BackgroundRecords.retain(rows.value.filterNot { it.at == at && it.source == source && it.mode == mode } + BackgroundRun(at, day(at), source, mode, dueAt, result, duration), System.currentTimeMillis())
        prefs.edit().putString("runs", BackgroundRecords.encode(rows.value)).apply()
        mutableHealth.value = BackgroundRecords.health(rows.value, day(System.currentTimeMillis()), system.label, mutableHealth.value.suspectedSystemRestriction)
    }
    suspend fun dump(): String = mutex.withLock {
        val current = BackgroundRecords.retain(rows.value, System.currentTimeMillis())
        val bucket = runCatching { context.getSystemService(UsageStatsManager::class.java).appStandbyBucket }.getOrNull()
        val battery = runCatching { context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName) }.getOrNull()
        "系统=${system.label} 信号=$signals 待机分组=$bucket 忽略电池优化=$battery 最近系统周期派发=${periodicAt.value}\n" +
            BackgroundRecords.summary(current) + "\n" + current.joinToString("\n") {
                "时间=${it.at} 来源=${it.source} 模式=${it.mode} 等锁=${it.lockWaitMillis}ms 应到=${it.dueAt} 结果=${it.result} 耗时=${it.durationMillis}ms"
            }
    }
    private fun day(at: Long) = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate().toString()
}
