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
    private val deferAudioCleanup: Boolean = false,
    private val updateProgress: (Int, Int) -> Unit = { _, _ -> },
    private val identity: () -> String = key,
    private val ready: () -> Boolean = { true },
    private val beforeTranscribe: suspend (SegmentEntity) -> Boolean = { false },
    private val afterImport: suspend () -> Unit = {},
    private val sourceReady: (TranscribeSource) -> Boolean = { true },
    private val sourceKey: (TranscribeSource) -> String = { key() },
    private val sourceNetwork: (TranscribeSource) -> Boolean = { networkAllowed() },
    private val beginBatch: () -> Unit = {},
    private val endBatch: () -> Unit = {},
    private val sharedPcm: SharedPcmFiles? = null,
    private val afterSegment: suspend (SegmentEntity) -> Unit = {},
    private val recordSpeed: (TranscribeSource?, Long, Long) -> Unit = { _, _, _ -> },
) {
    suspend fun withIdleAudio(block: suspend () -> Unit) = gate.withLock { block() }

    /** 启动恢复与上传共用 gate；若已有任务运行，由该任务自己的启动恢复负责。 */
    suspend fun recoverOnStartup() {
        if (!gate.tryLock()) return
        try { dao.recoverInterrupted(now()); dao.refreshFailures(now()) } finally { gate.unlock() }
    }
    /** true 表示仍有暂时错误，交 WorkManager 退避重试；鉴权失败立即结束本轮。 */
    suspend fun run(): Boolean {
        if (!gate.tryLock()) return false
        try {
            beginBatch()
            dao.recoverInterrupted(now())
            dao.refreshFailures(now())
            if (!deferAudioCleanup) cleanup()
            else cleanupImportedAudio(dao, now(), waitForVoiceprint = true)
            val started = now()
            val configuration = runCatching { identity() }.getOrDefault("")
            if (!ready() && dao.candidates(now()).none { it.asrSource != null }) { updateState(started, "转写未就绪，请配置服务或下载本地模型"); return false }
            updateState(started, null)
            val candidates = dao.candidates(now())
            var done = 0
            updateProgress(candidates.size, done)
            for (candidate in candidates) {
                currentCoroutineContext().ensureActive()
                val selected = candidate.asrSource?.let { TranscribeSource.valueOf(it) }
                if (selected != null && !sourceReady(selected)) { updateState(started, "指定转写方式未就绪"); continue }
                if (selected == null && !ready()) continue
                val credential = try { if (selected != null) sourceKey(selected) else key() }
                catch (_: Exception) { updateState(started, "密钥安全读取失败，请重新填写"); continue }
                if (!(selected?.let(sourceNetwork) ?: networkAllowed())) { updateState(started, "等待不计流量网络"); return true }
                // 清除/更换密钥后不继续使用本轮旧密钥。
                if (runCatching { identity() }.getOrDefault("") != configuration) return false
                // 清理任务或本轮前一段的声纹收尾可能已删除过期导入音频，不能上传旧快照。
                // 缺失文件也走统一失败落库，避免永久留在队列。
                val running = candidate.copy(status = "Running", attempts = candidate.attempts + 1, nextRetryAt = null, error = null)
                if (dao.claim(candidate.id) == 0) continue
                val began = now()
                var reserved = 0L
                var outcome = "Failed"
                var error: AsrError? = null
                var logId: String? = null
                try {
                    if (!File(candidate.path).isFile) throw AsrException(AsrError.Audio, detail = "录音文件已不存在")
                    if (!candidate.redoPending && beforeTranscribe(candidate) && dao.finishEnvSkip(candidate.id, now()) > 0) {
                        outcome = "SkippedEnv"
                        continue
                    }
                    val selectedProvider = if (selected != null && provider is ConfiguredAsrProvider) object : AsrProvider { override suspend fun recognize(audio: AsrAudio, key: String, beforeAudio: suspend () -> Unit) = provider.recognize(selected, audio, key, beforeAudio) } else provider
                    val result = selectedProvider.recognize(AsrAudio(File(candidate.path), File(candidate.path).extension, (candidate.endAt - candidate.startAt).coerceAtLeast(0), pcmFile = sharedPcm?.find(File(candidate.path))), credential) {
                        check(dao.findSegment(candidate.id) != null)
                        reserved = (candidate.endAt - candidate.startAt).coerceAtLeast(0)
                        dao.reserveAudio(today(), reserved, candidate.id)
                    }
                    recordSpeed(selected, (candidate.endAt - candidate.startAt).coerceAtLeast(0), (now() - began).coerceAtLeast(1))
                    logId = TranscribePolicy.safeLogId(result.logId, credential)
                    outcome = if (result.sentences.isEmpty()) "Empty" else "Done"
                    val lines = result.sentences.map { line ->
                        TranscriptLineEntity(segmentId = candidate.id, startAt = candidate.startAt + line.startMillis,
                            endAt = candidate.startAt + line.endMillis, text = line.text, speakerTag = line.speakerTag, originalText = line.originalText)
                    }
                    dao.findSegment(candidate.id)?.let { current -> val completed = current.copy(status = outcome, completedAt = now(), error = null, nextRetryAt = null, logId = logId, voiceprintProcessedAt = null, firstFailedAt = null)
                        if (current.redoPending) dao.completeRedo(completed, lines, now()) else dao.complete(completed, lines) }
                } catch (e: Exception) {
                    error = when (e) {
                        is CancellationException -> AsrError.Interrupted
                        is AsrException -> e.type
                        else -> AsrError.Storage
                    }
                    logId = TranscribePolicy.safeLogId((e as? AsrException)?.logId, credential)
                    // 断网不是这一段的问题，不占它的重试次数。
                    val attempts = if (error == AsrError.Network) candidate.attempts else running.attempts
                    val failedAt = now()
                    val abandoned = error == AsrError.Audio || (e as? AsrException)?.detail == "录音文件已不存在"
                    val quiet = !abandoned && candidate.firstFailedAt?.let { failedAt - it > 259_200_000L } == true
                    outcome = if (abandoned) "Abandoned" else if (quiet) "Quiet" else "Failed"
                    val reason = when {
                        abandoned -> if (!File(candidate.path).isFile) "录音文件已不存在" else "录音损坏，无法转写"
                        quiet -> "多次转写失败"
                        else -> (e as? AsrException)?.detail ?: error.human
                    }
                    withContext(NonCancellable) {
                        dao.findSegment(candidate.id)?.let { current -> dao.update(current.copy(status = outcome, completedAt = if (abandoned || quiet) failedAt else null, error = reason,
                            firstFailedAt = current.firstFailedAt ?: failedAt,
                            attempts = attempts, nextRetryAt = if (abandoned || quiet) null else TranscribePolicy.retryAt(error, attempts.coerceAtLeast(1), failedAt), logId = logId)) }
                        updateState(started, (e as? AsrException)?.detail ?: error.human)
                    }
                    if (e is CancellationException) throw e
                } finally {
                    try { if (outcome in setOf("Done", "Empty")) afterSegment(candidate) }
                    finally {
                    sharedPcm?.release(File(candidate.path))
                    withContext(NonCancellable) {
                        dao.attempt(AsrAttemptEntity(segmentId = candidate.id, at = began, result = outcome,
                            errorType = error?.name, elapsedMillis = (now() - began).coerceAtLeast(0), sentMillis = reserved, logId = logId))
                        updateProgress(candidates.size, ++done)
                    }
                    }
                }
                if (candidate.sourceId == "import" && outcome in setOf("Done", "Empty")) {
                    // 声纹失败不应把成功的转写改回失败；音频留待重试或三天上限清理。
                    try { afterImport() } catch (e: CancellationException) { throw e } catch (_: Exception) { }
                    if (!deferAudioCleanup) cleanupImportedAudio(dao, now())
                }
                if (error == AsrError.Auth) return false
                // 网络或服务端出问题时后面的段大概率也失败：结束本轮，交给退避重试，不逐段耗时间和次数。
                if (error in TranscribePolicy.ROUND_STOPPERS) return dao.candidates(now()).isNotEmpty() || dao.earliestRetry() != null
            }
            return dao.earliestRetry() != null
        } finally {
            try { sharedPcm?.clear(); endBatch(); updateProgress(0, 0) } finally { gate.unlock() }
        }
    }
    private suspend fun cleanup() {
        val timestamp = now()
        cleanupImportedAudio(dao, timestamp)
        for (segment in dao.expired(timestamp - TranscribePolicy.RETENTION_MILLIS)) {
            if (TranscribePolicy.canDelete(segment.status, segment.completedAt, timestamp)) File(segment.path).delete()
        }
    }
    suspend fun test(audio: AsrAudio, credential: String): Result<String> = gate.withLock {
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
