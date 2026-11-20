package app.resound.topic.core

import app.resound.digest.core.TranscriptLineData
import app.resound.topic.TopicProgress
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** 所有时刻均为 epoch 毫秒；范围半开，暂停空档不计入会议时间。 */
@Serializable data class TopicBlockRange(val startAt: Long, val endAt: Long)
data class TopicBlock(val index: Int, val ranges: List<TopicBlockRange>, val closed: Boolean) {
    val startAt get() = ranges.first().startAt
    val endAt get() = ranges.last().endAt
    fun contains(at: Long) = ranges.any { at >= it.startAt && at < it.endAt }
    fun overlaps(start: Long, end: Long) = ranges.any { start < it.endAt && end > it.startAt }
}
@Serializable enum class TopicBlockState { Waiting, Done, Failed, Skipped }
@Serializable data class TopicBlockNote(val index: Int, val ranges: List<TopicBlockRange>, val state: TopicBlockState,
    val text: String = "", val reason: String? = null)
@Serializable data class TopicBlockCache(
    val fingerprint: String, val ranges: List<TopicBlockRange>, val state: TopicBlockState = TopicBlockState.Waiting,
    val voiceprintWaitAt: Long? = null, val todoContext: String? = null,
    val entries: Map<String, String> = emptyMap(), val result: TopicBlockResult? = null, val error: String? = null,
)
@Serializable data class TopicBlockResult(
    val events: List<app.resound.digest.core.DigestPrompt.ParsedEvent> = emptyList(),
    val items: List<app.resound.digest.core.DigestPrompt.ParsedItem> = emptyList(),
    val cards: List<app.resound.skills.core.SkillCardData> = emptyList(),
    val organized: List<String> = emptyList(), val verbatim: List<String> = emptyList(),
    val lineIds: List<Long> = emptyList(),
) {
    val notes get() = (cards.map { it.summary } + organized + events.map { it.summary }).filter { it.isNotBlank() }.distinct().joinToString("\n")
}
data class TopicBlockSegment(val id: Long, val startAt: Long, val endAt: Long, val status: String, val voiceprintPending: Int = 0)
data class TopicBlockReadiness(val transcribeIds: Set<Long>, val voiceprint: Int, val recording: Boolean, val ready: Boolean, val voiceprintIds: Set<Long> = emptySet()) {
    val reason get() = when {
        recording -> "等待录音落盘"
        transcribeIds.isNotEmpty() -> "还有 ${transcribeIds.size} 段未成功转写"
        voiceprint > 0 -> "等待 $voiceprint 段认人"
        else -> "时间块尚未结束"
    }
}
object TopicBlocks {
    const val MINUTES = 10
    const val MILLIS = MINUTES * 60_000L
    const val VOICEPRINT_WAIT = 5 * 60_000L
    const val STALL = 30 * 60_000L
    const val SUMMARY_CHARS = 12_000
    const val PROMPT_VERSION = "topic-blocks-217-v1"
    val terminal = setOf("Done", "Empty", "SkippedEnv", "SkippedPlayback")
    fun active(status: String) = status in setOf("Running", "Paused")
    fun timeline(g: TopicGroup, now: Long): List<TopicBlock> {
        val result = mutableListOf<TopicBlock>()
        var ranges = mutableListOf<TopicBlockRange>()
        var used = 0L
        for (span in g.spans.sortedBy { it.startAt }) {
            var cursor = span.startAt
            val end = (span.endAt ?: now).coerceAtLeast(cursor)
            while (cursor < end) {
                val until = minOf(end, cursor + MILLIS - used)
                ranges += TopicBlockRange(cursor, until)
                used += until - cursor
                cursor = until
                if (used == MILLIS) {
                    result += TopicBlock(result.size, ranges.toList(), true)
                    ranges = mutableListOf(); used = 0
                }
            }
        }
        if (ranges.isNotEmpty()) result += TopicBlock(result.size, ranges, !active(g.row.status))
        return result
    }
    fun fingerprint(g: TopicGroup, block: TopicBlock, lines: List<TranscriptLineData>, previous: List<TranscriptLineData>,
        prompt: String, words: List<String>, extra: String, hash: (String) -> String): String = hash(buildJsonArray {
        add(PROMPT_VERSION); add(g.row.kind); add(g.row.name); add(prompt)
        add(buildJsonArray { words.forEach { add(it) } }); add(extra)
        add(buildJsonArray { block.ranges.forEach { add(buildJsonArray { add(it.startAt); add(it.endAt) }) } })
        fun speech(rows: List<TranscriptLineData>) = buildJsonArray { rows.forEach { l -> add(buildJsonArray {
            add(l.id); add(l.segmentId); add(l.startAt); add(l.endAt); add(l.text); add(l.speakerTag); add(l.speakerName); add(l.voiceInputJson)
        }) } }
        add(speech(lines)); add(speech(previous))
    }.toString())
    fun readiness(block: TopicBlock, segments: List<TopicBlockSegment>, recording: Boolean, voiceprintWaitAt: Long?, now: Long): TopicBlockReadiness {
        val overlapping = segments.filter { block.overlaps(it.startAt, it.endAt) }
        val waiting = overlapping.filter { it.status !in terminal }.map { it.id }.toSet()
        val voices = overlapping.filter { it.status == "Done" }.sumOf { it.voiceprintPending }
        val voiceReady = voices == 0 || voiceprintWaitAt != null && now - voiceprintWaitAt >= VOICEPRINT_WAIT
        return TopicBlockReadiness(waiting, voices, recording, block.closed && !recording && waiting.isEmpty() && voiceReady,
            overlapping.filter { it.status == "Done" && it.voiceprintPending > 0 }.map { it.id }.toSet())
    }
    /**
     * 汇总分组：按字数把一组装到约 [SUMMARY_CHARS] 再开下一组，而不是固定每 6 份一组。
     * 两小时培训有几十张分块卡，固定 6 份会连调十几次模型（串行，每次都要思考）；按字数装满通常 3～4 次。
     * 每组至少两份（只剩最后一份时除外），保证逐层合并一定收敛。
     */
    fun <T> summaryGroups(parts: List<T>, text: (T) -> String): List<List<T>> {
        if (parts.sumOf { text(it).length } <= SUMMARY_CHARS) return listOf(parts)
        val groups = mutableListOf<MutableList<T>>(); var chars = 0
        for (part in parts) {
            val size = text(part).length
            if (groups.isEmpty() || groups.last().size >= 2 && chars + size > SUMMARY_CHARS) { groups += mutableListOf<T>(); chars = 0 }
            groups.last() += part; chars += size
        }
        return groups
    }
    fun stalled(progress: TopicProgress, now: Long) = now - progress.lastProgressAt >= STALL
}

/** 独立新入口，不变更既有 TopicContract.kt 的签名。 */
interface TopicBlockActions {
    suspend fun summarizeNow(topicId: Long)
    suspend fun retryFailedBlocks(topicId: Long)
}

/** 为 AppGraph.topics 的既有 TopicRepository 类型提供兼容入口。 */
suspend fun app.resound.topic.TopicRepository.summarizeNow(topicId: Long) =
    (this as? TopicBlockActions ?: throw app.resound.topic.TopicException("当前专题实现不支持按块整理")).summarizeNow(topicId)
suspend fun app.resound.topic.TopicRepository.retryFailedBlocks(topicId: Long) =
    (this as? TopicBlockActions ?: throw app.resound.topic.TopicException("当前专题实现不支持按块整理")).retryFailedBlocks(topicId)
