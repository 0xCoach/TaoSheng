package app.resound.privacy

object PrivacyRules {
    fun recentStart(minutes: Int, until: Long): Long {
        require(minutes > 0)
        return until - minutes.toLong() * 60_000
    }
    fun validateRange(from: Long, until: Long) { require(from <= until) }
    fun discardRecording(until: Long, now: Long) = until >= now
    fun cleanupFile(path: String, sourceId: String) = path.isNotBlank() && sourceId != "voice-input"
    fun deleteWholeSegment(sourceCount: Int, targetCount: Int) = targetCount == sourceCount
}
