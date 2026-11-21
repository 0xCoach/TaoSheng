package app.resound.transcribe

import app.resound.data.TranscribeDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 低置信复核列表项：一行原话 + 它的可疑标记；weight 只用于排序。 */
data class ReviewItem(
    val lineId: Long, val segmentId: Long, val startAt: Long, val endAt: Long,
    val text: String, val speakerTag: String?, val flags: Set<ReviewFlag>, val weight: Int,
)

/**
 * 低置信转写复核：近 14 天内未人工复核（reviewedAt 为 null）的原话行，
 * 按 TranscriptReviewRules 过滤出可疑项。复核动作就是把 reviewedAt 打上时间戳。
 */
class TranscriptReviewRepository(private val dao: TranscribeDao, private val now: () -> Long) {

    fun pending(days: Int = 14): Flow<List<ReviewItem>> =
        dao.reviewLines(now() - days * 86_400_000L).map { rows ->
            rows.map { row ->
                val flags = TranscriptReviewRules.flagsOf(row.text, row.endAt - row.startAt,
                    row.attempts, row.speakerScore, row.speakerTag, row.originalText != null)
                ReviewItem(row.lineId, row.segmentId, row.startAt, row.endAt, row.text, row.speakerTag,
                    flags, TranscriptReviewRules.weight(flags))
            }.filter { it.flags.isNotEmpty() }
                .sortedWith(compareByDescending<ReviewItem> { it.weight }.thenByDescending { it.startAt })
        }

    fun observeCount(): Flow<Int> = pending().map { it.size }

    suspend fun markReviewed(lineId: Long) = dao.markReviewed(lineId, now())
}
