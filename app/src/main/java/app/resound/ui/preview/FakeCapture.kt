package app.resound.ui.preview

import app.resound.capture.CaptureController
import app.resound.capture.CaptureState
import app.resound.capture.CaptureStatsRepository
import app.resound.capture.CaptureStatus
import app.resound.capture.DayStats
import app.resound.capture.SegmentInfo
import app.resound.capture.SetupGuide
import app.resound.capture.SetupItem
import app.resound.capture.WatchdogEvent
import app.resound.capture.WatchdogEventType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random

/** 底层实现完成前，界面开发用的假数据。 */
class FakeCaptureController : CaptureController {
    private val _status = MutableStateFlow(CaptureStatus(CaptureState.Listening, System.currentTimeMillis()))
    override val status: StateFlow<CaptureStatus> = _status

    init {
        CoroutineScope(Dispatchers.Default).launch {
            var tick = 0
            while (true) {
                delay(100)
                tick++
                val s = _status.value
                if (s.state != CaptureState.Listening && s.state != CaptureState.Recording) continue
                val talking = (tick / 60) % 3 != 0
                val state = if (talking) CaptureState.Recording else CaptureState.Listening
                val level = if (talking) Random.nextFloat() * 0.8f + 0.1f else 0.03f
                _status.value = s.copy(
                    state = state,
                    since = if (state != s.state) System.currentTimeMillis() else s.since,
                    level = level,
                )
            }
        }
    }

    override fun start() {
        _status.value = CaptureStatus(CaptureState.Listening, System.currentTimeMillis())
    }

    override fun stop() {
        _status.value = CaptureStatus(CaptureState.Stopped, System.currentTimeMillis())
    }

    override fun pause(minutes: Int) {
        val now = System.currentTimeMillis()
        _status.value = CaptureStatus(CaptureState.Paused, now, pausedUntil = now + minutes * 60_000L)
    }

    override fun resume() = start()
}

class FakeStatsRepository : CaptureStatsRepository {
    private fun at(date: LocalDate, h: Int, m: Int) =
        date.atTime(h, m).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    override fun dayStats(date: LocalDate): Flow<DayStats> = flowOf(
        DayStats(
            date = date,
            segmentCount = 47,
            speechMillis = (2 * 60 + 14) * 60_000L,
            runningMillis = (11 * 60 + 32) * 60_000L,
            killedCount = 1,
            recoveredCount = 1,
            gapMillis = 6 * 60_000L,
            dischargePerHour = 0.7f,
            storageBytes = 31_400_000,
        ),
    )

    override fun segments(date: LocalDate): Flow<List<SegmentInfo>> = flowOf(emptyList())

    override fun watchdogEvents(date: LocalDate): Flow<List<WatchdogEvent>> = flowOf(
        listOf(
            WatchdogEvent(at(date, 7, 42), WatchdogEventType.ServiceStarted, "手动开启", 92, false),
            WatchdogEvent(at(date, 10, 5), WatchdogEventType.MicBusy, "来电", 85, false),
            WatchdogEvent(at(date, 10, 11), WatchdogEventType.MicReleased, null, 85, false),
            WatchdogEvent(at(date, 13, 20), WatchdogEventType.Killed, "漏掉约 6 分钟", 71, false),
            WatchdogEvent(at(date, 13, 26), WatchdogEventType.Recovered, "定时检查拉起", 71, false),
            WatchdogEvent(at(date, 15, 0), WatchdogEventType.Paused, "暂停 1 小时", 64, false),
            WatchdogEvent(at(date, 16, 0), WatchdogEventType.Resumed, "到点自动恢复", 60, true),
        ).sortedByDescending { it.at },
    )

    override suspend fun exportDiagnostics(): File = File.createTempFile("diag", ".txt")
}

class FakeSetupGuide : SetupGuide {
    private val done = mutableSetOf("mic", "notify")
    override fun items() = listOf(
        SetupItem("mic", "麦克风权限", "用来收音，只在有人说话时保存", "mic" in done),
        SetupItem("notify", "通知权限", "显示“正在聆听”和中断提醒", "notify" in done),
        SetupItem("battery", "忽略电池优化", "避免系统为省电把录音停掉", "battery" in done),
        SetupItem("autostart", "允许自启动", "被关掉后能自己重新启动", if ("autostart" in done) true else null),
        SetupItem("powerkeeper", "省电策略设为“无限制”", "小米最容易杀后台的一项", if ("powerkeeper" in done) true else null),
        SetupItem("lock", "在最近任务里锁定回响", "多任务界面下拉回响卡片，出现小锁", if ("lock" in done) true else null),
    )

    override fun open(key: String) {
        done += key
    }

    override fun markDone(key: String) {
        done += key
    }
}
