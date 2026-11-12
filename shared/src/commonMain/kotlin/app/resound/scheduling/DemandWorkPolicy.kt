package app.resound.scheduling

/** 系统只负责执行；是否有活、等待何种条件由共用规则决定。 */
object DemandWorkPolicy {
    enum class Embedding { None, DownloadModel, Compute, WaitForPower }
    fun embedding(pending: Boolean, modelReady: Boolean, powerAllowed: Boolean): Embedding = when {
        !pending -> Embedding.None
        !modelReady -> Embedding.DownloadModel
        !powerAllowed -> Embedding.WaitForPower
        else -> Embedding.Compute
    }
    fun privacy(pendingCount: Int) = pendingCount > 0
    fun importNeedsCleanup(status: String) = status !in setOf("Running", "Done", "Empty", "SkippedPlayback", "SkippedEnv")
    fun audioCleanup(hasExpiringAudio: Boolean, hasOrphans: Boolean) = hasExpiringAudio || hasOrphans
    // 暂停仍保持 enabled；只有明确停止才撤掉系统杀进程后的唯一提醒。
    fun watchdog(enabled: Boolean) = enabled
}
