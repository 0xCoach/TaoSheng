package app.resound.things

import androidx.room.withTransaction
import app.resound.data.CaptureDatabase
import app.resound.data.TranscriptLineEntity
import app.resound.data.EventEntity
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class ThingWatcher(private val db: CaptureDatabase, private val repository: RoomThingRepository,
    private val detect: suspend (List<TranscriptLineEntity>, List<TranscriptLineEntity>, List<Thing>) -> List<ThingProposal>,
    private val notify: (ThingRecordRow) -> Unit = {}, private val now: () -> Long = System::currentTimeMillis,
) {
    private val gate = Mutex()
    private val requests = Channel<Unit>(Channel.CONFLATED)
    fun request() { requests.trySend(Unit) }
    /** 请求失败后隔多久再试：断网一阵不至于连败 3 次就跳过（本机转写断网也照常出新句，所以不提前唤醒） */
    private val retryMillis = 10 * 60_000L
    private suspend fun retryPause() {
        if ((db.things().state()?.failures ?: 0) > 0) delay(retryMillis)
    }
    /**
     * 寻物只管装上以后说的话：skipBefore 不为空时（第一次启动，含卡 079 初版从第 0 句补旧原话的手机），
     * 先把水位跳到这个时刻之前的最后一句，旧原话不补、不发给模型。跳过后调 onSkipped 记下，以后不再跳
     */
    fun start(scope: CoroutineScope, debounceMillis: Long = 30_000, skipBefore: Long? = null, onSkipped: () -> Unit = {}) {
        scope.launch {
            if (skipBefore != null) { skipHistory(skipBefore); onSkipped() }
            request() // 启动补跑；只有这个消费循环执行，触发不会取消进行中的模型请求。
            for (ignored in requests) {
                // 真正的尾沿防抖：最后一次触发后安静 30 秒再跑；不取消正在进行的请求。
                while (withTimeoutOrNull(debounceMillis) { requests.receive(); true } == true) { }
                try { if (runOnce()) { retryPause(); request() } }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { delay(retryMillis); request() } // 存储临时失败下次重试，不推进水位。
            }
        }
    }
    internal suspend fun skipHistory(before: Long) = gate.withLock {
        val dao = db.things()
        db.withTransaction {
            val state = dao.state() ?: ThingWatchState()
            val cut = dao.maxLineIdBefore(before)
            if (cut > state.watermark) dao.state(state.copy(watermark = cut, failedEnd = null, failures = 0))
        }
    }
    /** 返回 true 表示仍有批次/失败待重试；每批最多 80 句，水位含不合格句子。 */
    internal suspend fun runOnce(): Boolean = gate.withLock {
        val dao = db.things()
        val state = dao.state() ?: ThingWatchState()
        if (!state.enabled) {
            db.withTransaction { val latest = dao.state() ?: state
                if (!latest.enabled) dao.state(latest.copy(watermark = maxOf(latest.watermark, dao.maxLineId()), failures = 0, failedEnd = null)) }
            return@withLock false
        }
        val batch = dao.batch(state.watermark, state.failedEnd ?: Long.MAX_VALUE)
        // 失败批的原话可能已被删除，仍需要越过固定上界。
        val end = state.failedEnd ?: batch.lastOrNull()?.id ?: return@withLock false
        val lines = dao.eligible(batch.map { it.id })
        val rows = dao.allThings(); val records = dao.allRecords()
        val known = things(rows, records).sortedByDescending { t -> rows.first { it.id == t.id }.updatedAt }.take(200)
        val proposals = try {
            if (lines.isEmpty()) emptyList() else detect(lines, dao.context(state.watermark).reversed(), known)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            db.withTransaction {
                val current = dao.state() ?: ThingWatchState()
                if (current != state) return@withTransaction
                val failures = state.failures + 1
                if (failures >= 3) {
                    dao.state(state.copy(watermark = end, failedEnd = null, failures = 0))
                    db.capture().event(EventEntity(at = now(), type = "ThingsSkipped", detail = "寻物连续失败3次，跳过原话 ${state.watermark + 1}..$end", batteryPercent = null, charging = null))
                } else dao.state(state.copy(failedEnd = end, failures = failures))
            }
            return@withLock true
        }
        val notifications = db.withTransaction {
            if ((dao.state() ?: ThingWatchState()) != state) return@withTransaction emptyList<ThingRecordRow>()
            // 请求期间可能删原话、重转写或改为媒体；不能把旧请求结果重新插回。
            val live = dao.eligible(lines.map { it.id }).associateBy { it.id }
            val saved = proposals.filter { p -> live[p.row.sourceLineId]?.let { it.text == p.row.sourceText && it.startAt == p.row.at } == true }
                .mapNotNull { repository.propose(it) }
            dao.state(state.copy(watermark = end, failures = 0, failedEnd = null))
            saved.filter { it.explicit }
        }
        notifications.distinctBy { it.id }.forEach { runCatching { notify(it) } }
        dao.maxLineId() > end
    }
}
