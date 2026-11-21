package app.resound.topic

import app.resound.diagnostics.FailureNotes

import app.resound.data.TranscriptLineEntity

import android.content.Context
import android.util.AtomicFile
import app.resound.topic.core.*
import app.resound.digest.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/** 存派生结果、指纹和冻结的待办上下文，不存整场原话；不进入备份。文件损坏时安全重新生成。 */
internal class TopicCheckpointFiles(context: Context, private val currentRevision: (suspend (Long) -> Long?)? = null) : TopicCheckpointStore {
    private val dir = File(context.noBackupFilesDir, "topic-checkpoints")
    private val mutex = Mutex()
    private val epochs = mutableMapOf<Long, Long>()
    private val changes = MutableStateFlow(0L)
    private val json = Json { ignoreUnknownKeys = true }
    private fun file(id: Long) = File(dir, "$id.json")
    override fun observe(id: Long): Flow<TopicProgress> = changes.map { read(id)?.progress ?: TopicProgress() }.distinctUntilChanged()
    private fun readFile(id: Long) = runCatching {
        json.decodeFromString<TopicCheckpoint>(AtomicFile(file(id)).openRead().bufferedReader().use { it.readText() })
            .takeIf { it.format == 3 }
    }.getOrNull() // 旧格式、缺文件和损坏的派生缓存都按无缓存处理。
    override suspend fun read(id: Long): TopicCheckpoint? = withContext(Dispatchers.IO) {
        mutex.withLock { readFile(id) }
    }
    override suspend fun write(id: Long, value: TopicCheckpoint) = withContext(Dispatchers.IO) {
        val epoch = mutex.withLock { epochs[id] ?: 0L }
        if (currentRevision != null && currentRevision.invoke(id) != value.revision) throw TopicException("专题状态已变化，保留有效块等待下轮处理")
        mutex.withLock {
        if ((epochs[id] ?: 0L) != epoch || (readFile(id)?.revision ?: value.revision) > value.revision)
            throw TopicException("专题已重新生成或删除，停止旧任务写入")
        check(dir.isDirectory || dir.mkdirs())
        app.resound.util.atomicWriteFile(file(id)) { out -> out.write(json.encodeToString(TopicCheckpoint.serializer(), value).toByteArray()) }
        changes.value = changes.value + 1
    } }
    override suspend fun remove(id: Long) = withContext(Dispatchers.IO) { mutex.withLock { epochs[id] = (epochs[id] ?: 0L) + 1; AtomicFile(file(id)).delete(); changes.value = changes.value + 1 } }
    /** 只校验磁盘上已有的检查点；不再因整场原话变化删除有效块。 */
    suspend fun refresh(ids: Set<Long>? = null) = withContext(Dispatchers.IO) {
        val selected = ids ?: dir.listFiles()?.filter { it.extension == "json" }
            ?.mapNotNull { it.nameWithoutExtension.toLongOrNull() }.orEmpty().toSet()
        selected.forEach { read(it) }
        changes.value = changes.value + 1
    }
    suspend fun retain(ids: Set<Long>) = withContext(Dispatchers.IO) {
        dir.listFiles()?.filter { it.extension == "json" }?.forEach {
            it.nameWithoutExtension.toLongOrNull()?.let { id -> if (id !in ids) remove(id) else read(id) }
        }
        changes.value = changes.value + 1
    }
}
internal fun topicHash(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
internal fun topicFailure(e: Throwable): TopicFailure = when (e) {
    is TopicStalledException -> TopicFailure(TopicFailureKind.Permanent, e.message.orEmpty())
    is app.resound.skills.core.SkillFormatException, is IllegalArgumentException -> TopicFailure(TopicFailureKind.Format, "模型回答格式或字段不符合要求")
    is app.resound.net.NetLlmException -> topicFailure(LlmException(LlmError.valueOf(e.type.name), e.status, truncated = e.truncated))
    is TopicRequestBudgetExceeded -> TopicFailure(TopicFailureKind.Permanent, "本轮请求达到上限，已保存进度，请稍后继续")
    is LlmInputLimitException -> TopicFailure(TopicFailureKind.Capacity, "本段内容超过模型容量，请换模型后继续")
    is LlmException -> when {
        e.type == LlmError.TooSlow -> TopicFailure(TopicFailureKind.Capacity, e.detail)
        e.type == LlmError.Truncated || e.truncated -> TopicFailure(TopicFailureKind.Capacity, "模型输出未完成")
        e.type == LlmError.Format -> TopicFailure(TopicFailureKind.Format, "模型回答格式或字段不符合要求")
        e.rejected -> TopicFailure(TopicFailureKind.Capacity, "模型拒收本段内容")
        e.type.retryable -> TopicFailure(TopicFailureKind.Temporary, e.type.human)
        else -> TopicFailure(TopicFailureKind.Permanent, e.type.human)
    }
    is TopicException -> TopicFailure(TopicFailureKind.Permanent, e.message ?: "专题状态已变更")
    else -> TopicFailure(TopicFailureKind.Permanent, "本地处理未完成，请重试")
}

internal fun topicSource(g: TopicGroup, lines: List<TranscriptLineEntity>): String =
    topicHash(TopicProcessingRules.source(g.snapshot(), lines.map { it.snapshot() }))
