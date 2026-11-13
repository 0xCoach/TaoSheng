package app.resound.location

object Timeline {
    private val rules = TimelineRules(Track::meters)
    const val GAP_MS = TimelineRules.GAP_MS
    fun groups(stays: List<Stay>, dayStart: Long, isHome: (Double, Double) -> Boolean = { _, _ -> false }) = rules.groups(stays, dayStart, isHome)
    fun build(points: List<LocationPoint>, stays: List<Stay>, from: Long, to: Long, isHome: (Double, Double) -> Boolean = { _, _ -> false }) = rules.build(points, stays, from, to, isHome)
}
