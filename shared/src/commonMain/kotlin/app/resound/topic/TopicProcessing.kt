package app.resound.topic

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import app.resound.topic.core.TopicBlockCache
import app.resound.topic.core.TopicBlockNote

/** 派生缓存不替代原话。format=3 按块保存；source 用于完成快检；entries 供块内请求断点使用。 */
@Serializable data class TopicCheckpoint(val source: String = "", val entries: Map<String, String> = emptyMap(),
    val progress: TopicProgress = TopicProgress(),
    val format: Int = 0, val revision: Long = 0,
    val blocks: Map<Int, TopicBlockCache> = emptyMap(),
    val summaryKey: String = "", val summary: String? = null,
    val summaryEntries: Map<String, String> = emptyMap(),
    val skipWaiting: Boolean = false, val completedSegments: Set<Long> = emptySet(),
    val endedAt: Long? = null, val generationStartedAt: Long? = null)
@Serializable data class TopicProgress(val phase: String = "", val completed: Int = 0, val total: Int = 0,
    val notes: Map<String, String> = emptyMap(), val error: String? = null, val finished: Boolean = false, val draft: String? = null,
    val blocksTotal: Int = 0, val blocksDone: Int = 0, val blocksFailed: Int = 0,
    val waitingTranscribe: Int = 0, val waitingVoiceprint: Int = 0, val waitingNetwork: Boolean = false,
    val lastProgressAt: Long = 0, val organizedUntil: Long? = null,
    val blockNotes: List<TopicBlockNote> = emptyList(), val stalledReason: String? = null,
    /** 卡 227：整理中的暂定标题，取已整理块里的第一件事，和最后合成标题的规则一致。 */
    val title: String? = null) {
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
