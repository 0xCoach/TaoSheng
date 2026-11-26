package app.resound.transcribe

import app.resound.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.time.LocalDate

/** 一个进程只有一个实例；周期、手动与密钥测试共用同一把锁。 */
class TranscribeRunner(
    private val dao: TranscribeDao,
    private val provider: AsrProvider,
    private val key: () -> String,
    private val updateState: (Long?, String?) -> Unit,
    private val networkAllowed: () -> Boolean = { true },
    private val now: () -> Long = System::currentTimeMillis,
    private val today: () -> String = { LocalDate.now().toString() },
    private val gate: Mutex = Mutex(),
    private val updateProgress: (Int, Int) -> Unit = { _, _ -> },
    private val identity: () -> String = key,
    private val ready: () -> Boolean = { true },
    private val beforeTranscribe: suspend (SegmentEntity) -> Boolean = { false },
    private val afterImport: suspend () -> Unit = {},
    private val afterRound: () -> Unit = {},
    private val sourceReady: (TranscribeSource) -> Boolean = { true },
    private val sourceKey: (TranscribeSource) -> String = { key() },
    private val sourceNetwork: (TranscribeSource) -> Boolean = { networkAllowed() },
    private val recordTiming: (String) -> Unit = {},
    private val separateSpeakers: suspend (Long) -> Boolean = { true },
    private val defaultSource: () -> TranscribeSource? = { null },
    private val beginBatch: () -> Unit = {},
    private val endBatch: () -> Unit = {},
    private val sharedPcm: SharedPcmFiles? = null,
    private val afterSegment: suspend (SegmentEntity) -> Unit = {},
    private val recordSpeed: (TranscribeSource?, Long, Long) -> Unit = { _, _, _ -> },
    private val topicTraining: suspend (Long) -> Boolean = { false },
    private val doubaoFallback: () -> Boolean = { false },
    private val customFallbackCredential: () -> String? = { null },
) {
    internal var localResume: app.resound.transcribe.local.LocalResumeQueue? = null

    suspend fun withIdleAudio(block: suspend () -> Unit) = gate.withLock { block() }

    /** 启动恢复与上传共用 gate；若已有任务运行，由该任务自己的启动恢复负责。 */
    suspend fun recoverOnStartup() {
        if (!gate.tryLock()) return
        try {
            val timestamp = now()
            localResume?.prepare(dao.allAudio(), defaultSource())
            dao.recoverInterrupted(timestamp)
            cleanupImportedAudio(dao, timestamp)
        } finally { gate.unlock() }
    }
    /** true 表示仍有暂时错误，交 WorkManager 退避重试；鉴权失败立即结束本轮。 */
    suspend fun run(): Boolean {
        if (!gate.tryLock()) return false
        var producedLines = false
        try {
            beginBatch()
            localResume?.prepare(dao.allAudio(), defaultSource())
            dao.recoverInterrupted(now())
            cleanupImportedAudio(dao, now())
            val started = now()
            val configuration = runCatching { identity() }.getOrDefault("")
            if (!ready() && dao.candidates(now()).none { it.asrSource != null }) { updateState(started, "转写未就绪，请配置服务或下载本地模型"); return false }
            updateState(started, null)
            val candidates = dao.candidates(now())
            var done = 0
            val progress = Mutex()
            // 环境音、本地转写和声纹互斥；两路豆包仅重叠网络等待。
            val heavy = Mutex()
            updateProgress(candidates.size, done)
            suspend fun process(candidate: SegmentEntity): RoundDecision {
                currentCoroutineContext().ensureActive()
                val selected = candidate.asrSource?.let { TranscribeSource.valueOf(it) } ?: defaultSource()
                if (selected == TranscribeSource.Local && localResume?.deferred(candidate.id) == true) return RoundDecision.Continue
                if (selected != null && !sourceReady(selected)) { updateState(started, "指定转写方式未就绪"); return RoundDecision.Continue }
                if (selected == null && !ready()) return RoundDecision.Continue
                val credential = try { if (selected != null) sourceKey(selected) else key() }
                catch (_: Exception) { updateState(started, "密钥安全读取失败，请重新填写"); return RoundDecision.Continue }
                if (!(selected?.let(sourceNetwork) ?: networkAllowed())) { updateState(started, "等待不计流量网络"); return RoundDecision.Retry }
                // 清除/更换密钥后不继续使用本轮旧密钥。
                if (runCatching { identity() }.getOrDefault("") != configuration) return RoundDecision.Stop
                // 清理任务或本轮前一段的声纹收尾可能已删除过期导入音频，不能上传旧快照。
                // 缺失文件也走统一失败落库，避免永久留在队列。
                val running = candidate.copy(status = "Running", attempts = candidate.attempts + 1, nextRetryAt = null, error = null)
                if (dao.claim(candidate.id) == 0) return RoundDecision.Continue
                val began = now()
                var reserved = 0L
                var outcome = "Failed"
                var error: AsrError? = null
                var logId: String? = null
                var primaryAttemptRecorded = false
                try {
                    if (!File(candidate.path).isFile) throw AsrException(AsrError.Audio, detail = "录音文件已不存在")
                    if (!candidate.redoPending && heavy.withLock { beforeTranscribe(candidate) } && dao.finishEnvSkip(candidate.id, now()) > 0) {
                        outcome = "SkippedEnv"
                        return RoundDecision.Continue
                    }
                    val training = topicTraining(candidate.startAt)
                    val distinguish = if (training) false else candidate.separateSpeakers ?: separateSpeakers(candidate.startAt)
                    if (training) dao.topicTraining(candidate.id)
                    dao.rememberSpeakers(candidate.id, distinguish)
                    val file = File(candidate.path)
                    val audio = AsrAudio(file, file.extension, (candidate.endAt - candidate.startAt).coerceAtLeast(0),
                        separateSpeakers = distinguish, pcmFile = sharedPcm?.find(file), speakerCount = candidate.speakerCount)
                    if (selected == TranscribeSource.Local) localResume?.begin(candidate.id)
                    suspend fun reserve() {
                        check(dao.findSegment(candidate.id) != null)
                        reserved = audio.durationMillis
                        dao.reserveAudio(today(), reserved, candidate.id)
                    }
                    suspend fun recognize() = if (selected != null && provider is SourceAsrProvider)
                        provider.recognize(selected, audio, credential, ::reserve) else provider.recognize(audio, credential, ::reserve)
                    var result = if (selected == TranscribeSource.Local) heavy.withLock { recognize() } else recognize()
                    val primaryElapsed = (now() - began).coerceAtLeast(0)
                    withContext(NonCancellable) { dao.recordTokens(today(), result.inputTokens, result.outputTokens) }
                    recordSpeed(selected, audio.durationMillis, primaryElapsed.coerceAtLeast(1))
                    logId = TranscribePolicy.safeLogId(result.logId, credential)
                    if (provider is SourceAsrProvider && TranscribePolicy.shouldTryCustomFallback(selected, candidate.asrSource != null || candidate.redoPending, result, doubaoFallback())
                        && sourceNetwork(TranscribeSource.Custom)) {
                        customFallbackCredential()?.let { customKey ->
                            dao.attempt(AsrAttemptEntity(segmentId = candidate.id, at = began, result = "Empty",
                                errorType = null, elapsedMillis = primaryElapsed, sentMillis = reserved, logId = logId))
                            primaryAttemptRecorded = true
                            val fallback = recognizeCustomFallback(provider, candidate.id, audio, customKey)
                            if (fallback != null && fallback.sentences.isNotEmpty()) {
                                result = fallback
                                logId = TranscribePolicy.safeLogId(fallback.logId, customKey)
                            }
                        }
                    }
                    outcome = if (result.sentences.isEmpty()) "Empty" else "Done"
                    val lines = result.sentences.map { line ->
                        TranscriptLineEntity(segmentId = candidate.id, startAt = candidate.startAt + line.startMillis,
                            endAt = candidate.startAt + line.endMillis, text = line.text, speakerTag = candidate.importChannel?.takeIf { !distinguish && candidate.speakerCount != 0 }?.let { "channel:$it" } ?: line.speakerTag.takeIf { distinguish }, originalText = line.originalText)
                    }
                    dao.findSegment(candidate.id)?.let { current -> val completed = current.copy(status = outcome, completedAt = now(), error = null, nextRetryAt = null, logId = logId, voiceprintProcessedAt = if (distinguish || (candidate.importChannel != null && candidate.speakerCount != 0)) null else now(), firstFailedAt = null)
                        if (current.redoPending) dao.completeRedo(completed, lines, now()) else dao.complete(completed, lines)
                        if (selected == TranscribeSource.Local) localResume?.success(candidate.id)
                        if (lines.isNotEmpty()) producedLines = true }
                } catch (e: Exception) {
                    error = when (e) {
                        is CancellationException -> AsrError.Interrupted
                        is AsrException -> e.type
                        else -> AsrError.Storage
                    }
                    logId = TranscribePolicy.safeLogId((e as? AsrException)?.logId, credential)
                    val resume = selected == TranscribeSource.Local && localResume?.failed(candidate.id, e) == true
                    // 断网不是这一段的问题，不占它的重试次数。
                    val attempts = if (resume || error == AsrError.Network) candidate.attempts else running.attempts
                    val failedAt = now()
                    val abandoned = error == AsrError.Audio || (e as? AsrException)?.detail == "录音文件已不存在"
                    val quiet = !abandoned && candidate.firstFailedAt?.let { failedAt - it > 259_200_000L } == true
                    outcome = if (abandoned) "Abandoned" else if (quiet) "Quiet" else if (resume) "Pending" else "Failed"
                    val reason = when {
                        abandoned -> if (!File(candidate.path).isFile) "录音文件已不存在" else "录音损坏，无法转写"
                        quiet -> "多次转写失败"
                        else -> (e as? AsrException)?.detail ?: error.human
                    }
                    withContext(NonCancellable) {
                        dao.findSegment(candidate.id)?.let { current -> dao.update(current.copy(status = outcome, completedAt = if (abandoned || quiet) failedAt else null, error = reason,
                            firstFailedAt = if (resume) candidate.firstFailedAt else current.firstFailedAt ?: failedAt,
                            attempts = attempts, nextRetryAt = if (resume || abandoned || quiet) null else TranscribePolicy.retryAt(error, attempts.coerceAtLeast(1), failedAt), logId = logId)) }
                        updateState(started, (e as? AsrException)?.detail ?: error.human)
                    }
                    if (e is CancellationException) throw e
                } finally {
                    try { if (outcome in setOf("Done", "Empty")) heavy.withLock { afterSegment(candidate) } }
                    finally {
                    sharedPcm?.release(File(candidate.path))
                    withContext(NonCancellable) {
                        currentCoroutineContext()[SegmentTimings]?.outcome = outcome
                        if (!primaryAttemptRecorded) dao.attempt(AsrAttemptEntity(segmentId = candidate.id, at = began, result = outcome,
                            errorType = error?.name, elapsedMillis = (now() - began).coerceAtLeast(0), sentMillis = reserved, logId = logId))
                        progress.withLock { updateProgress(candidates.size, ++done) }
                    }
                    }
                }
                if (candidate.sourceId == "import" && outcome in setOf("Done", "Empty")) {
                    // 声纹失败不应把成功的转写改回失败；音频留待重试或三天上限清理。
                    try { heavy.withLock { afterImport() } } catch (e: CancellationException) { throw e } catch (_: Exception) { }
                }
                if (error == AsrError.Auth) return RoundDecision.Stop
                if (error in TranscribePolicy.ROUND_STOPPERS) return RoundDecision.Retry
                return RoundDecision.Continue
            }
            var index = 0
            while (index < candidates.size) {
                currentCoroutineContext().ensureActive()
                val first = candidates[index]
                fun cloud(segment: SegmentEntity) = (segment.asrSource?.let { TranscribeSource.valueOf(it) } ?: defaultSource()) == TranscribeSource.Doubao
                val count = if (cloud(first) && DoubaoConcurrency.global.limit == 2 && index + 1 < candidates.size && cloud(candidates[index + 1])) 2 else 1
                val batch = candidates.subList(index, index + count)
                val decisions = coroutineScope { batch.map { segment -> async {
                    val timing = SegmentTimings(segment.id, segment.asrSource?.let { TranscribeSource.valueOf(it) } ?: defaultSource(), segment.endAt - segment.startAt)
                    withContext(timing) { try { process(segment) } finally { recordTiming(timing.line()) } }
                } }.awaitAll() }
                if (RoundDecision.Stop in decisions) return false
                if (RoundDecision.Retry in decisions) return true
                index += count
            }
            return dao.earliestRetry() != null
        } finally {
            try { sharedPcm?.clear(); endBatch(); updateProgress(0, 0) } finally { gate.unlock(); if (producedLines) afterRound() }
        }
    }
    private enum class RoundDecision { Continue, Stop, Retry }

    private suspend fun recognizeCustomFallback(provider: SourceAsrProvider, segmentId: Long, audio: AsrAudio, key: String): AsrResult? {
        val began = now()
        var sentMillis = 0L
        var result: AsrResult? = null
        var error: AsrError? = null
        var logId: String? = null
        try {
            result = provider.recognize(TranscribeSource.Custom, audio, key) {
                check(dao.findSegment(segmentId) != null)
                // 用量按每次上传计：补位又把整段发了一次，同样预占。
                sentMillis = audio.durationMillis
                dao.reserveAudio(today(), sentMillis, segmentId)
            }
            logId = result.logId
        } catch (e: CancellationException) {
            error = AsrError.Interrupted
            throw e
        } catch (e: Exception) {
            error = (e as? AsrException)?.type ?: AsrError.Storage
            logId = (e as? AsrException)?.logId
        } finally {
            val elapsed = (now() - began).coerceAtLeast(0)
            val outcome = when {
                result == null -> "CustomFallbackFailed"
                result.sentences.isEmpty() -> "CustomFallbackEmpty"
                else -> "CustomFallbackDone"
            }
            withContext(NonCancellable) {
                dao.attempt(AsrAttemptEntity(segmentId = segmentId, at = began, result = outcome, errorType = error?.name,
                    elapsedMillis = elapsed, sentMillis = sentMillis, logId = TranscribePolicy.safeLogId(logId, key)))
                if (result != null) {
                    dao.recordTokens(today(), result.inputTokens, result.outputTokens)
                    recordSpeed(TranscribeSource.Custom, audio.durationMillis, elapsed.coerceAtLeast(1))
                }
            }
        }
        return result
    }

    suspend fun test(audio: AsrAudio, credential: String, onResult: (AsrResult) -> Unit = {}): Result<String> = gate.withLock {
        val began = now()
        var reserved = 0L
        var result = "Failed"
        var error: AsrError? = null
        var logId: String? = null
        try {
            val response = provider.recognize(audio, credential) {
                reserved = audio.durationMillis
                dao.reserveAudio(today(), reserved, null)
            }
            withContext(NonCancellable) { dao.recordTokens(today(), response.inputTokens, response.outputTokens) }
            onResult(response)
            result = if (response.sentences.isEmpty()) "Empty" else "Done"
            logId = TranscribePolicy.safeLogId(response.logId, credential)
            // 云端文字可能回显凭证：测试返回界面的内容额外去除密钥及其组成部分。
            var text = response.sentences.joinToString("") { it.text }
            (listOf(credential) + credential.split('-')).filter { it.isNotEmpty() }.forEach {
                text = text.replace(it, "[已隐藏]", ignoreCase = true)
            }
            Result.success("可用，识别出：${text.take(120).ifBlank { "（没有文字）" }}")
        } catch (e: Exception) {
            error = if (e is CancellationException) AsrError.Interrupted else (e as? AsrException)?.type ?: AsrError.Storage
            logId = TranscribePolicy.safeLogId((e as? AsrException)?.logId, credential)
            if (e is CancellationException) throw e
            Result.failure(AsrException(error, detail = (e as? AsrException)?.detail ?: error.human))
        } finally {
            withContext(NonCancellable) {
                dao.attempt(AsrAttemptEntity(segmentId = null, at = began, result = result, errorType = error?.name,
                    elapsedMillis = (now() - began).coerceAtLeast(0), sentMillis = reserved, logId = logId))
            }
        }
    }
}
