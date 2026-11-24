package app.resound.platform

enum class ProFeature {
    MorePushChannels,
    MarkdownDailyNotes,
    AdvancedExport,
    DailyReview,
    WeeklyReview,
}

/** 高级功能是否可用。 */
fun interface Entitlements {
    fun has(feature: ProFeature): Boolean
}
