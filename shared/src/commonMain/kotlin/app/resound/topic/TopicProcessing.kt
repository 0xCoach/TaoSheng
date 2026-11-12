package app.resound.topic

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

/** 派生缓存不替代原话；source 改变即作废。平台提供原子存储，iOS 可用同一协议。 */
@Serializable data class TopicCheckpoint(val source: String = "", val entries: Map<String, String> = emptyMap(),
    val progress: TopicProgress = TopicProgress())
@Serializable data class TopicProgress(val phase: String = "", val completed: Int = 0, val total: Int = 0,
    val notes: Map<String, String> = emptyMap(), val error: String? = null, val finished: Boolean = false, val draft: String? = null) {
    val readableNotes: List<String> get() {
        val summaries = notes.filterKeys { it.startsWith("整理纪要:") }
        val extracts = notes.filterKeys { it.startsWith("提取要点:") }
        return (summaries.ifEmpty { extracts }.ifEmpty { notes }).values.toList()
    }
    val label: String get() = when {
        error != null -> if (notes.isEmpty()) "需要重试" else "部分完成"
        finished -> "已完成"
        total > 0 -> "$phase $completed / $total 段"
        else -> phase
    }
}
interface TopicCheckpointStore {
    fun observe(id: Long): Flow<TopicProgress>
    suspend fun read(id: Long): TopicCheckpoint?
    suspend fun write(id: Long, value: TopicCheckpoint)
    suspend fun remove(id: Long)
}
class MemoryTopicCheckpoints : TopicCheckpointStore {
    private val state = MutableStateFlow<Map<Long, TopicCheckpoint>>(emptyMap())
    private val lock = Mutex()
    override fun observe(id: Long) = state.map { it[id]?.progress ?: TopicProgress() }
    override suspend fun read(id: Long) = state.value[id]
    override suspend fun write(id: Long, value: TopicCheckpoint) { lock.withLock { state.value = state.value + (id to value) } }
    override suspend fun remove(id: Long) { lock.withLock { state.value = state.value - id } }
}
