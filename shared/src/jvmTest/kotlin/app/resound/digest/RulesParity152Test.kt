package app.resound.digest

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RulesParity152Test {
    @Test fun batchesKeepOriginalLimitsAndOrder() {
        val lines = (0..600).map { "$it" }
        assertEquals(listOf(lines.take(600), lines.drop(600)), DigestBatches.batches(lines) { it })
        assertEquals(listOf(listOf("a".repeat(39970)), listOf("b")), DigestBatches.batches(listOf("a".repeat(39970), "b")) { it })
    }
    @Test fun sessionsUseMaximumEndAndInclusiveGap() {
        val rows = listOf(0L to 500L, 10L to 20L, 600L to 700L, 801L to 900L)
        assertEquals(listOf(rows.take(3), rows.drop(3)), DigestBatches.sessions(rows, 100, { it.first }, { it.second }))
    }
    private val presets = (1..4).map { LlmPreset("$it", "$it", "https://example.invalid", "$it") }
    private val candidates = presets.map { FailoverCandidate(it, "synthetic", 0) }
    private fun rules(cooling: MutableMap<String, FailoverCooling>, sizes: List<Int> = listOf(10,20,30,40)) = FailoverRules(object : FailoverStore {
        override fun cooling(id: String) = cooling[id]
        override fun coolingSnapshot(ids: List<String>) = cooling.filterKeys { it in ids }
        override fun coolIfCurrent(id: String, generation: Long, value: FailoverCooling) { if (generation == 0L) cooling[id] = value }
    }, app.resound.platform.NowMillis { 1000 }, { sizes[it.id.toInt()-1] }, {
        FailoverFailure(it.message!!, capacity = it.message == "容量", longCooling = it.message == "鉴权")
    }, { it }, { e, suffix -> Exception(e.message + suffix) })
    @Test fun failoverCoolingKeepsStableOrderAndThirtyMinuteAuthDelay() = runBlocking {
        val cooling = mutableMapOf<String, FailoverCooling>()
        val calls = mutableListOf<String>()
        val core = rules(cooling)
        val result = core.execute(presets[0], candidates, true, {true}, { _, _ -> }) {
            calls += it.preset.id
            if (it.preset.id == "1") throw Exception("鉴权")
            "ok"
        }
        assertEquals(listOf("1","2"), calls)
        assertEquals("2", result.second.id)
        assertEquals(1801000L, cooling["1"]!!.until)
        calls.clear()
        core.execute(presets[0], candidates, true, {true}, { _, _ -> }) { calls += it.preset.id; "ok" }
        assertEquals(listOf("2"), calls)
    }
    @Test fun capacityFailureSkipsSmallCandidatesWithoutConsumingAttempt() = runBlocking {
        val cooling = mutableMapOf<String, FailoverCooling>()
        val calls = mutableListOf<String>()
        val result = rules(cooling, listOf(20,10,20,40)).execute(presets[0], candidates, true, {true}, { _, _ -> }) {
            calls += it.preset.id
            if (it.preset.id == "1") throw Exception("容量")
            "ok"
        }
        assertEquals(listOf("1","4"), calls)
        assertEquals("4", result.second.id)
        assertTrue(cooling.isEmpty())
    }
}
