package app.resound.importer

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** 时间单位为 Unix 毫秒；未知时间不会自动消耗转写额度。 */
object FolderHistoryPolicy {
    fun expands(old: FolderHistory, new: FolderHistory): Boolean {
        fun rank(h: FolderHistory) = when (h) { FolderHistory.OnlyNew -> 0; FolderHistory.ListAll -> 1; FolderHistory.Days7 -> 2; FolderHistory.Days30 -> 3 }
        return rank(new) > rank(old)
    }
    fun queues(history: FolderHistory, start: Long?, now: Long): Boolean = start != null && start <= now &&
        when (history) {
            FolderHistory.Days7 -> start >= now - 7 * 86400_000L
            FolderHistory.Days30 -> start >= now - 30 * 86400_000L
            else -> false
        }
    fun next(rows: List<FolderRecordingRow>, paused: Boolean): FolderRecordingRow? = rows
        .filter { it.state == "Queued" && (!paused || !it.backfill) && it.pendingId == null }
        .sortedWith(compareBy<FolderRecordingRow> { it.backfill }.thenByDescending { it.startAt }.thenByDescending { it.id }).firstOrNull()
    fun matches(row: FolderRecordingRow, label: String?, query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return false
        val digits = q.replace(" ", "").replace("-", "").removePrefix("+86")
        return row.partyName?.contains(q, true) == true || label?.contains(q,true) == true ||
            (digits.length >= 4 && digits.all(Char::isDigit) && row.number?.endsWith(digits) == true)
    }
}

object CallNamePolicy {
    fun prompt(label: String?, fileName: String?): String = label ?: fileName ?: "对方"
    /** 展示覆盖单独返回，不碰 speakerName / speakerScore；号码备注叫“我”也仍是对方。 */
    fun display(lines: List<CallDisplayLine>): Map<Long,String> = buildMap {
        for (record in lines.groupBy { it.recordId }.values) {
            val groups = record.mapNotNull { it.speakerTag }.distinct()
            if (groups.size != 2 || record.any { it.speakerTag == null }) continue
            val mine = record.filter { it.speakerPersonId == 0L }.mapNotNull { it.speakerTag }.distinct().singleOrNull() ?: continue
            val other = groups.singleOrNull { it != mine } ?: continue
            val party = record.first()
            val name = party.label ?: party.partyName ?: party.number ?: continue
            record.filter { it.speakerTag == other }.forEach { put(it.id, name) }
        }
    }
}

class CallDisplayNames(private val dao: FolderRecordingDao) {
    val names = dao.displayLines().map(CallNamePolicy::display)
    suspend fun snapshot() = names.first()
}
