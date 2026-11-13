package app.resound.topic

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

/** 仅存已校验的派生结果，不存请求原话；不进入备份。文件损坏时安全重新生成。 */
internal class TopicCheckpointFiles(context: Context, private val currentSource: (suspend (Long) -> String?)? = null) : TopicCheckpointStore {
    private val dir = File(context.noBackupFilesDir, "topic-checkpoints")
    private val mutex = Mutex()
    private val changes = MutableStateFlow(0L)
    private val json = Json { ignoreUnknownKeys = true }
    private fun file(id: Long) = File(dir, "$id.json")
    override fun observe(id: Long): Flow<TopicProgress> = changes.map { read(id)?.progress ?: TopicProgress() }.distinctUntilChanged()
    private fun readFile(id: Long) = runCatching {
        json.decodeFromString<TopicCheckpoint>(AtomicFile(file(id)).openRead().bufferedReader().use { it.readText() })
    }.getOrNull()
    override suspend fun read(id: Long): TopicCheckpoint? = withContext(Dispatchers.IO) {
        val value = mutex.withLock { readFile(id) }
        // 数据库读取必须在文件锁外，避免与正在清理断点的数据库事务互相等待。
        if (value != null && currentSource != null && currentSource.invoke(id) != value.source) {
            mutex.withLock { if (readFile(id)?.source == value.source) AtomicFile(file(id)).delete() }
            null
        } else value
    }
    override suspend fun write(id: Long, value: TopicCheckpoint) = withContext(Dispatchers.IO) {
        if (currentSource != null && currentSource.invoke(id) != value.source) throw TopicException("专题原话已变化，请重新整理")
        mutex.withLock {
        check(dir.isDirectory || dir.mkdirs())
        val atom = AtomicFile(file(id)); val out = atom.startWrite()
        try { out.write(json.encodeToString(TopicCheckpoint.serializer(), value).toByteArray()); atom.finishWrite(out) }
        catch (e: Throwable) { atom.failWrite(out); throw e }
        changes.value = changes.value + 1
    } }
    override suspend fun remove(id: Long) = withContext(Dispatchers.IO) { mutex.withLock { AtomicFile(file(id)).delete(); changes.value = changes.value + 1 } }
    /** 只校验磁盘上已有的检查点；currentSource 会检查删除、取消及原话变化。 */
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
internal fun topicFailure(e: Throwable) = when (e) {
    is TopicRequestBudgetExceeded -> TopicFailure(TopicFailureKind.Permanent, "本轮请求达到上限，已保存进度，请稍后继续")
    is LlmInputLimitException -> TopicFailure(TopicFailureKind.Capacity, "本段内容超过模型容量，请换模型后继续")
    is LlmException -> when {
        e.truncated -> TopicFailure(TopicFailureKind.Capacity, "模型输出未完成")
        e.type == LlmError.Format -> TopicFailure(TopicFailureKind.Format, "模型回答格式或字段不符合要求")
        e.rejected -> TopicFailure(TopicFailureKind.Capacity, "模型拒收本段内容")
        e.type.retryable -> TopicFailure(TopicFailureKind.Temporary, e.type.human)
        else -> TopicFailure(TopicFailureKind.Permanent, e.type.human)
    }
    is TopicException -> TopicFailure(TopicFailureKind.Permanent, e.message ?: "专题状态已变更")
    else -> TopicFailure(TopicFailureKind.Permanent, "本地处理未完成，请重试")
}

internal fun topicSource(g: TopicGroup, lines: List<app.resound.data.TranscriptLineEntity>): String =
    topicHash(TopicProcessingRules.source(g.snapshot(), lines.map { it.snapshot() }))
