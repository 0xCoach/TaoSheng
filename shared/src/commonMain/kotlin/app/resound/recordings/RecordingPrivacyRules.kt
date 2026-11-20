package app.resound.recordings

/** 只接受本功能登记的单个文件路径，绝不允许目录/通配删除。 */
object RecordingPrivacyRules {
    fun owned(path: String, text: Boolean = false): Boolean {
        val parts = path.split('/')
        return parts.size == 3 && parts[0] == "录音" && parts[1].matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")) &&
            parts[2].isNotBlank() && parts.none { it == ".." || '\\' in it || '*' in it || '?' in it || it.any(Char::isISOControl) } &&
            (path.endsWith(".ogg") || path.endsWith(".m4a") || text && (path.endsWith(".txt") || path.endsWith(".part")))
    }
    fun textPath(audio: String) = audio.substringBeforeLast('.') + ".txt"
    fun partPath(audio: String) = audio.substringBeforeLast('/') + "/." + audio.substringAfterLast('/') + ".part"
    fun files(audio: String) = if (owned(audio)) listOf(audio, textPath(audio)) else emptyList()
}

data class PublishedRecording(val id: Long, val path: String, val temporary: String? = null)
data class RecordingDeletePlan(val segment: Long, val path: String, val day: String)

/** 计划不含系统 URI/凭据；落盘、互斥和执行由平台适配器负责。 */
object RecordingPrivacyPlan {
    fun create(ids: Set<Long>, records: List<PublishedRecording>, dailyDays: Set<String>, dailyTemporaryDays: Set<String> = emptySet()): List<RecordingDeletePlan> =
        records.filter { it.id in ids && RecordingPrivacyRules.owned(it.path) }.flatMap { row ->
            val day = row.path.split('/')[1]
            val files = RecordingPrivacyRules.files(row.path) + listOfNotNull(row.temporary?.takeIf { RecordingPrivacyRules.owned(it, true) }) +
                (if (day in dailyDays) listOf(RecordingKeepRules.dailyPath(day)) else emptyList()) +
                (if (day in dailyTemporaryDays) listOf(RecordingPrivacyRules.partPath(RecordingKeepRules.dailyPath(day))) else emptyList())
            files.distinct().map { RecordingDeletePlan(row.id, it, day) }
        }.distinctBy { it.path }
    fun mayRetry(savedDestination: String, currentDestination: String?) = currentDestination != null && savedDestination == currentDestination
    fun finished(audioPath: String, pendingPaths: Set<String>) = RecordingPrivacyRules.files(audioPath).none { it in pendingPaths }
}

data class DailyRecording(val recording: KeepRecording, val filename: String, val completed: Boolean, val lines: String)
object RecordingDailyRules {
    fun documents(rows: List<DailyRecording>, subscription: app.resound.push.PushSubscription, today: String,
        currentScope: Set<Long>, uploaded: Set<Long>, recorded: Set<Long>, knownDays: Set<String>, dirtyDays: Set<String>): Map<String, String> {
        val byDay = rows.filter { (it.recording.id in currentScope || it.recording.id in uploaded) &&
            RecordingKeepRules.eligible(it.recording, subscription, null, today) }.groupBy { it.recording.day }
        val changedDays = rows.filter { it.recording.id in recorded && it.recording.day in knownDays }.map { it.recording.day }
        return (byDay.keys + changedDays + dirtyDays).filter { it < today }.sorted().associateWith { day ->
            RecordingKeepRules.dailyText(day, byDay[day].orEmpty().filter { it.recording.id in uploaded && it.completed }
                .map { it.filename to it.lines })
        }
    }
}

/** 文件已不存在时直接完成；有文件但没有旧凭据时不能猜测归属。 */
enum class RecordingDeleteCheck { Delete, Missing, MissingBytes, SizeMismatch }
object RecordingPrivacyDeleteRules {
    fun manual(enabled: Boolean, temporary: Boolean) = !enabled || temporary
    fun check(bytes: Long?, remoteBytes: Long?): RecordingDeleteCheck = when {
        bytes == null || bytes < 0 -> RecordingDeleteCheck.MissingBytes
        remoteBytes == null -> RecordingDeleteCheck.Missing
        bytes != remoteBytes -> RecordingDeleteCheck.SizeMismatch
        else -> RecordingDeleteCheck.Delete
    }
}
