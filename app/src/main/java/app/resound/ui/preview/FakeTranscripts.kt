package app.resound.ui.preview

import app.resound.transcribe.SegmentTranscript
import app.resound.transcribe.TranscribeQueueState
import app.resound.transcribe.TranscribeSettings
import app.resound.transcribe.TranscriptLine
import app.resound.transcribe.TranscriptRepository
import app.resound.transcribe.TranscriptStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/** 转写底层完成前，界面开发用的假数据。 */
class FakeTranscriptRepository : TranscriptRepository {
    private val scope = CoroutineScope(Dispatchers.Default)
    private val today = LocalDate.now()
    private val segments = MutableStateFlow(sample(today))

    private val _queue = MutableStateFlow(
        TranscribeQueueState(
            pending = 2,
            failed = 1,
            running = false,
            lastRunAt = System.currentTimeMillis() - 18 * 60_000L,
            nextRunAt = System.currentTimeMillis() + 12 * 60_000L,
            lastError = null,
            usedSecondsToday = 38 * 60L,
            usedSecondsTotal = 3 * 3600L + 12 * 60L,
        ),
    )
    override val queue: StateFlow<TranscribeQueueState> = _queue

    override fun day(date: LocalDate): Flow<List<SegmentTranscript>> =
        segments.map { all -> if (date == today) all else sample(date).filter { it.status == TranscriptStatus.Done } }

    override fun runNow() {
        if (_queue.value.running) return
        scope.launch {
            _queue.value = _queue.value.copy(running = true)
            segments.value = segments.value.map {
                if (it.status == TranscriptStatus.Pending) it.copy(status = TranscriptStatus.Running) else it
            }
            delay(2200)
            segments.value = segments.value.map {
                if (it.status == TranscriptStatus.Running) {
                    it.copy(status = TranscriptStatus.Done, lines = listOf(line(it.segmentId * 10, it.startAt, "0", "好，那就这么定了，我回头把方案发群里。")))
                } else {
                    it
                }
            }
            val now = System.currentTimeMillis()
            _queue.value = _queue.value.copy(
                running = false,
                pending = 0,
                lastRunAt = now,
                nextRunAt = now + 30 * 60_000L,
                usedSecondsToday = _queue.value.usedSecondsToday + 95,
            )
        }
    }

    override fun retryFailed() {
        segments.value = segments.value.map {
            if (it.status == TranscriptStatus.Failed) it.copy(status = TranscriptStatus.Pending, error = null) else it
        }
        _queue.value = _queue.value.copy(failed = 0, pending = _queue.value.pending + 1)
    }

    private fun sample(date: LocalDate): List<SegmentTranscript> {
        fun at(h: Int, m: Int, s: Int = 0) = date.atTime(h, m, s).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return listOf(
            SegmentTranscript(9, at(16, 40), at(16, 41, 20), TranscriptStatus.Pending, emptyList(), audioAvailable = true),
            SegmentTranscript(8, at(16, 12), at(16, 12, 40), TranscriptStatus.Pending, emptyList(), audioAvailable = true),
            SegmentTranscript(7, at(15, 3), at(15, 3, 30), TranscriptStatus.Failed, emptyList(), error = "网络中断，已重试 5 次", audioAvailable = true),
            SegmentTranscript(6, at(14, 20), at(14, 20, 8), TranscriptStatus.Empty, emptyList(), audioAvailable = true),
            SegmentTranscript(
                5, at(14, 2), at(14, 5, 10), TranscriptStatus.Done,
                listOf(
                    line(51, at(14, 2, 2), "0", "下周三的评审你那边能准备好吗？"),
                    line(52, at(14, 2, 6), "1", "应该可以，就是测试手机还没到，我周一再催一下。"),
                    line(53, at(14, 2, 14), "1", "要是周二还没到，我先用自己的手机顶上。"),
                    line(54, at(14, 2, 22), "0", "行，那你周二下班前给我个准信。"),
                ),
                audioAvailable = true,
            ),
            SegmentTranscript(
                4, at(12, 31), at(12, 33), TranscriptStatus.Done,
                listOf(
                    line(41, at(12, 31, 1), "0", "这家的酸菜鱼比上次那家好吃，下次带爸妈来。"),
                    line(42, at(12, 31, 9), "1", "可以，周末人多得提前订位。"),
                ),
                audioAvailable = true,
            ),
            SegmentTranscript(
                3, at(9, 15), at(9, 16, 30), TranscriptStatus.Done,
                listOf(line(31, at(9, 15, 3), null, "提醒自己：今天要把体检报告拍照发给医生。")),
                audioAvailable = false,
            ),
        )
    }

    private fun line(id: Long, at: Long, tag: String?, text: String) =
        TranscriptLine(id, at, at + 4000, text, tag)
}

class FakeTranscribeSettings : TranscribeSettings {
    private val _hasKey = MutableStateFlow(false)
    override val hasKey: StateFlow<Boolean> = _hasKey
    override fun saveKey(key: String) {
        _hasKey.value = key.isNotBlank()
    }

    override fun clearKey() {
        _hasKey.value = false
    }

    private val _interval = MutableStateFlow(30)
    override val intervalMinutes: StateFlow<Int> = _interval
    override fun setIntervalMinutes(minutes: Int) {
        _interval.value = minutes
    }

    private val _wifiOnly = MutableStateFlow(true)
    override val wifiOnly: StateFlow<Boolean> = _wifiOnly
    override fun setWifiOnly(value: Boolean) {
        _wifiOnly.value = value
    }

    override suspend fun test(): Result<String> {
        delay(1200)
        return if (_hasKey.value) Result.success("可用，识别出：今天天气不错。") else Result.failure(IllegalStateException("还没填写转写密钥"))
    }
}
