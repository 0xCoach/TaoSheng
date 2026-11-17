package app.resound.importer

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
    fun matches(row: FolderRecordingRow, query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return false
        val digits = q.replace(" ", "").replace("-", "").removePrefix("+86")
        return row.partyName?.contains(q, true) == true ||
            (digits.length >= 4 && digits.all(Char::isDigit) && row.number?.endsWith(digits) == true)
    }
}

object CallNamePolicy {
    /** 只向纪要提供文件名里的名字，没有就写“对方”，绝不带号码。 */
    fun prompt(fileName: String?): String = fileName ?: "对方"
}
