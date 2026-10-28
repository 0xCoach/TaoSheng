package app.resound.capture

import app.resound.appName
import android.content.Context
import app.resound.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.Instant

class RoomCaptureStatsRepository(private val context: Context, private val dao: CaptureDao,
    private val transcribeDiagnostics: suspend () -> String = { "" }) : CaptureStatsRepository {
    private fun bounds(date: LocalDate): Pair<Long, Long> {
        val zone = ZoneId.systemDefault()
        return date.atStartOfDay(zone).toInstant().toEpochMilli() to date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    }
    override fun segments(date: LocalDate): Flow<List<SegmentInfo>> {
        val (a, b) = bounds(date)
        return dao.segments(a, b).map { rows -> rows.map { SegmentInfo(it.id, it.startAt, it.endAt, it.sourceId, File(it.path), it.sizeBytes) } }
    }
    override fun watchdogEvents(date: LocalDate): Flow<List<WatchdogEvent>> {
        val (a, b) = bounds(date)
        return dao.events(a, b).map { rows -> rows.filter { it.at in a until b }.mapNotNull { row ->
            // 新后端日志可先落库，待约定/UI 扩展后自动展示；未知类型不使统计页崩溃。
            WatchdogEventType.entries.find { it.name == row.type }?.let { type ->
                WatchdogEvent(row.at, type, row.detail, row.batteryPercent, row.charging)
            }
        } }
    }
    override fun dayStats(date: LocalDate): Flow<DayStats> {
        val (a, b) = bounds(date)
        fun overlap(start: Long, end: Long) = (minOf(b, end) - maxOf(a, start)).coerceAtLeast(0)
        return combine(dao.segments(a, b), dao.events(a, b), dao.heartbeats(a, b), dao.batteries(a, b), dao.appPowerSamples(a, b)) { segments, events, heartbeats, batteries, power ->
            val discharge = batteries.zipWithNext().filter { (x, y) ->
                x.charging == false && y.charging == false && x.percent != null && y.percent != null &&
                    y.percent <= x.percent && y.at > x.at && y.at - x.at <= CaptureTuning.BATTERY_MS * 2
            }
            val hours = discharge.sumOf { (x, y) -> y.at - x.at } / 3_600_000.0
            DayStats(date, segments.count { it.startAt in a until b }, segments.sumOf { overlap(it.startAt, it.endAt) },
                heartbeats.filter { it.running }.sumOf { overlap(it.intervalStart, it.at) },
                events.count { it.type == "Killed" && it.at in a until b },
                events.count { it.type == "Recovered" && it.detail?.contains("system-sticky") == true && it.at in a until b },
                events.filter { it.type == "Killed" }.sumOf { overlap(it.gapStart ?: it.at, it.gapEnd ?: it.at) },
                if (hours > 0) (discharge.sumOf { (x, y) -> x.percent!! - y.percent!! } / hours).toFloat() else null,
                appPowerPerHour = AppPowerModel.perHour(power, a, b),
                storageBytes = segments.filter { it.startAt in a until b }.sumOf { it.sizeBytes })
        }
    }
    override suspend fun exportDiagnostics(): File = withContext(Dispatchers.IO) {
        val file = File(context.cacheDir, "resound-diagnostics-${System.currentTimeMillis()}.txt")
        file.bufferedWriter().use { out ->
            out.appendLine("${context.appName}运行记录；最近3个自然日（本地时区）；${Instant.now()}")
            out.appendLine(transcribeDiagnostics())
            out.appendLine(app.resound.audio.OggGuard.describe())
            out.appendLine(app.resound.transcribe.PipelineTimes.dump(context))
            out.appendLine(app.resound.update.UpdateDiagnostics.dump(context))
            out.appendLine(VoiceInputTrace.dump())
            val diarySync = context.getSharedPreferences("diary-sync", Context.MODE_PRIVATE)
            if (diarySync.contains("lastFailureAt")) out.appendLine(
                "最近一次日记上传失败：${diarySync.getLong("lastFailureAt", 0)} ${diarySync.getString("lastFailure", "")}")
            val prefs = context.getSharedPreferences("capture", Context.MODE_PRIVATE)
            val last = prefs.getLong("heartbeat", 0)
            val now = System.currentTimeMillis()
            out.appendLine("enabled=${prefs.getBoolean("enabled", false)} unclean=${prefs.getBoolean("unclean", false)} lastHeartbeat=$last pausedUntil=${prefs.getLong("pausedUntil", 0)}")
            if (prefs.getBoolean("enabled", false) && now - last > CaptureTuning.HEARTBEAT_MS * 2)
                out.appendLine("当前尚未恢复的心跳缺口 $last..$now，${now - last}ms（可能暂停/休眠）")
            out.appendLine("最近一次片段保存错误（重试成功后仍保留历史）：${if (prefs.contains("lastSegmentError")) "有记录（原始错误已隐藏）" else "无"}")
            val spool = File(context.filesDir, "segment-spool").listFiles().orEmpty()
            out.appendLine("待处理暂存文件=${spool.size}，字节=${spool.sumOf { it.length() }}")
            out.appendLine("运行时长来自心跳确认区间；心跳可能因休眠延迟；Killed 缺口是最后心跳至下次启动的上界估计。")
            out.appendLine("speechMillis 为保留片段时长，包含前滚/尾静音；dischargePerHour 为整机变化；appPowerPerHour 为自身模型估算（系数未校准），不充电有效区间不足30分钟不显示。")
            out.appendLine("自身模型 a=${AppPowerModel.CPU_MAH_PER_SECOND} mAh/CPU秒 b=${AppPowerModel.MIC_MAH_PER_HOUR} mA c=${AppPowerModel.NETWORK_MAH_PER_MB} mAh/MB；MB=1000000字节；容量由剩余电荷/电量比例估算，失败回退4880mAh")
            repeat(3) { offset ->
                val date = LocalDate.now().minusDays(offset.toLong())
                val (a, b) = bounds(date)
                out.appendLine("\n${dayStats(date).first()}")
                dao.events(a, b).first().forEach { out.appendLine("守护 ${it.copy(detail = null)}") }
                val beats = dao.heartbeats(a, b).first()
                beats.forEachIndexed { index, beat ->
                    out.appendLine("心跳 $beat")
                    val prev = beats.getOrNull(index - 1)
                    if (prev != null && beat.at - prev.at > CaptureTuning.HEARTBEAT_MS * 2)
                        out.appendLine("心跳缺口 ${prev.at}..${beat.at}，${beat.at - prev.at}ms（含暂停/休眠，不能单凭此判定被杀）")
                }
                dao.appPowerSamples(a, b).first().forEach { out.appendLine("自身耗电原始计数 $it") }
                dao.batteries(a, b).first().forEach { out.appendLine("电量 $it") }
            }
        }
        file
    }
}
