package app.resound.things

object FindThingRules {
    data class Match(val thing: Thing?, val history: List<ThingRecordData>, val pending: List<ThingRecordData>)
    data class Result(val matches: List<Match>, val truncated: Boolean)
    fun select(ts: List<ThingData>, rs: List<ThingRecordData>, query: String, place: String): Result {
        fun matches(value: String?, filter: String) = filter.isEmpty() || value?.contains(filter, ignoreCase = true) == true
        val results = mutableListOf<Match>()
        val attached = mutableSetOf<Long>()
        var truncated = false
        for (t in thingCatalog(ts, rs)) {
            val pending = rs.filter { it.status == "Pending" && (it.thingId == t.id || it.sameAsId == t.id) }
            if (!(listOf(t.name, t.latest.place, t.lastPlace, t.latest.who).plus(t.aliases).plus(pending.flatMap { listOf(it.name, it.place, it.who) }).any { matches(it, query) } &&
                    (listOf(t.latest.place, t.lastPlace) + pending.map { it.place }).any { matches(it, place) })) continue
            if (results.size >= 40) { truncated = true; break }
            attached.addAll(pending.map { it.id })
            results += Match(t, rs.filter { it.thingId == t.id && it.status == "Accepted" }.take(5), pending)
        }
        for (r in rs.filter { it.status == "Pending" && it.id !in attached }) {
            if (!listOf(r.name, r.place, r.who).any { matches(it, query) } || !matches(r.place, place)) continue
            if (results.size >= 40) { truncated = true; break }
            results += Match(null, listOf(r), emptyList())
        }
        return Result(results, truncated)
    }
}
