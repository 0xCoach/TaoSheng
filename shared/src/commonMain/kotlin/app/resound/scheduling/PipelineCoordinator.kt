package app.resound.scheduling

import app.resound.digest.core.DigestOutcome
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** 旧 Worker 的 mode 字符串也是持久化协议，不能改值。 */
object PipelineModes {
    const val FULL = "full"
    const val REALTIME = "realtime"
    const val TRANSCRIBE = "transcribe"
    const val DIGEST = "digest"
    const val TIMED = "timed"
    const val REVIEW = "review"
    const val AUTO = "auto"
    fun periodic(mode: String, guard: Boolean) = mode == AUTO || mode == TRANSCRIBE ||
        mode == DIGEST || mode == TIMED && guard
    fun organizes(mode: String) = mode in listOf(FULL, DIGEST, TIMED, REVIEW)
    fun force(mode: String, manual: Boolean) = manual || mode == TIMED || mode == REVIEW
    fun retryFailed(mode: String) = mode == TIMED || mode == REVIEW
}

interface PipelineStages {
    suspend fun transcribe(retryFailed: Boolean): Boolean
    suspend fun voiceprint(): Boolean
    suspend fun organize(force: Boolean, manual: Boolean): DigestOutcome
}

data class PipelineResult(val transcribeRetry: Boolean = false, val voiceprintRetry: Boolean = false,
                          val digest: DigestOutcome = DigestOutcome.NothingToDo) {
    val retry: Boolean get() = transcribeRetry || voiceprintRetry || digest is DigestOutcome.Retryable
    val blocked: Boolean get() = digest is DigestOutcome.NeedsUser
    val reason: String get() = listOfNotNull(
        "转写未完成".takeIf { transcribeRetry }, "声纹未完成".takeIf { voiceprintRetry },
        when (val result = digest) {
            is DigestOutcome.Retryable -> result.reason
            is DigestOutcome.NeedsUser -> result.reason
            else -> null
        }).joinToString("；")
}

/** 只处理业务阶段，不持有任何安卓对象；耗时使用调用方传入的单调毫秒时钟。 */
class PipelineCoordinator(private val stages: PipelineStages, private val elapsed: () -> Long) {
    suspend fun run(mode: String, manual: Boolean, skip: String?,
                    timing: (String, Long) -> Unit): PipelineResult {
        var transcribeRetry = false
        var voiceprintRetry = false
        var digest: DigestOutcome = DigestOutcome.NothingToDo
        suspend fun <T> measured(name: String, block: suspend () -> T): T {
            val start = elapsed()
            try { return block() }
            finally { timing(name, (elapsed() - start).coerceAtLeast(0)) }
        }
        selectStages(mode, skip, {
            transcribeRetry = measured("转写") { stages.transcribe(PipelineModes.retryFailed(mode)) }
            voiceprintRetry = measured("声纹") { stages.voiceprint() }
        }, {
            digest = measured("整理") { stages.organize(PipelineModes.force(mode, manual), manual) }
        })
        currentCoroutineContext().ensureActive()
        return PipelineResult(transcribeRetry, voiceprintRetry, digest)
    }

    companion object {
        fun digestTranscribeSkip(local: Boolean, wifiOnly: Boolean, unmetered: Boolean,
                                 deviceAllows: Boolean, pendingMillis: Long): String? = when {
            !local && wifiOnly && !unmetered -> "只在 WiFi 下转写，当前不是不计流量的网络"
            pendingMillis <= 0 -> "没有待转写片段"
            !deviceAllows -> "电量低或手机偏热"
            else -> null
        }

        suspend fun selectStages(mode: String, skip: String?, transcribe: suspend () -> Unit,
                                 organize: suspend () -> Unit) {
            if (mode != PipelineModes.DIGEST || skip == null) transcribe()
            if (PipelineModes.organizes(mode)) organize()
        }
    }
}

/** 尝试历史与凭据消费由共用业务规则决定，平台只负责持久化。 */
object PipelineRunRules {
    fun started(before: PipelineDue.History, at: Long) = before.copy(lastAttempt = at,
        failure = PipelineDue.Failure.Retryable, failures = before.failures + 1)
    fun completed(before: PipelineDue.History, started: Long, now: Long, result: PipelineResult): PipelineDue.History {
        val failure = failure(result.retry, result.blocked)
        return PipelineDue.History(started, if (failure == PipelineDue.Failure.None) now else before.lastSuccess,
            failure, if (failure == PipelineDue.Failure.None) 0 else before.failures + 1)
    }
    fun consumeTicket(result: PipelineResult) = !result.retry || result.blocked
    fun manual(before: PipelineDue.History, mode: PipelineDue.Mode, now: Long, result: PipelineResult): PipelineDue.History {
        val blocked = mode in listOf(PipelineDue.Mode.IntervalDigest, PipelineDue.Mode.TimedDigest) && result.blocked
        val failure = failure(result.retry, blocked)
        return PipelineDue.History(now, if (failure == PipelineDue.Failure.None) now else before.lastSuccess,
            failure, if (result.retry) 1 else 0)
    }
    private fun failure(retry: Boolean, blocked: Boolean) = when {
        blocked -> PipelineDue.Failure.Blocked
        retry -> PipelineDue.Failure.Retryable
        else -> PipelineDue.Failure.None
    }
    /** 只传入启用的候选；设置模式互斥时另一个为 null。 */
    fun nextAlarm(intervalAt: Long?, timedAt: Long?): Long? = listOfNotNull(intervalAt, timedAt).minOrNull()
}
