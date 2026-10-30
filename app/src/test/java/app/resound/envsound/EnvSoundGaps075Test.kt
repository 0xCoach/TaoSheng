package app.resound.envsound

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class EnvSoundGaps075Test {
    private val enabled = EnvSoundSettings(enabled = true, skip = SkipMode.On, media = MediaMode.SkipTranscribe)
    private fun scores(vararg pairs: Pair<String, Float>) =
        (EnvSoundRules.speech + EnvSoundRules.human + EnvSoundRules.tags.values.flatMap { it.second } +
            EnvSoundRules.sounds.values.flatMap { it.second }).associateWith { 0f } + pairs

    @Test fun noGapAndSubHalfSecondRunsGiveNoAudio() {
        val pcm = ShortArray(160000)
        assertEquals(0, EnvSoundGaps.extract(pcm, emptyList()).pcm.size)
        val result = EnvSoundGaps.extract(pcm, listOf(0 until 7999, 9000 until 16999))
        assertEquals(0, result.availableSamples)
        assertEquals(0, result.pcm.size)
    }

    @Test fun trimBothEdgesBeforeApplyingTwoSecondMinimum() {
        val pcm = ShortArray(40000) { it.toShort() }
        val short = EnvSoundGaps.extract(pcm, listOf(0 until 35199))
        assertEquals(31999, short.availableSamples)
        assertTrue(short.pcm.isEmpty())
        val enough = EnvSoundGaps.extract(pcm, listOf(0 until 35200))
        assertArrayEquals(pcm.copyOfRange(1600, 33600), enough.pcm)
        // 0.5秒是收边前门槛；七段各剩0.3秒，累计2.1秒。
        val fragmented = EnvSoundGaps.extract(ShortArray(70000), (0..6).map { it*10000 until it*10000+8000 })
        assertEquals(33600, fragmented.pcm.size)
    }

    @Test fun collectorKeepsActualTailAndExcludesSpeech() {
        val pcm = ShortArray(60000) { it.toShort() }
        val collector = EnvSoundGaps.Collector()
        collector.accept(10000, true)
        collector.accept(20000, false)
        collector.accept(20000, false)
        collector.accept(9999, false)
        collector.accept(1, false)
        val result = collector.finish(pcm)
        assertEquals(46800, result.availableSamples)
        assertArrayEquals(pcm.copyOfRange(11600, 58400), result.pcm)
    }

    @Test fun longGapsUseThirtySecondsAcrossBeginningMiddleAndEnd() {
        val pcm = ShortArray(70*16000) { 9 }
        pcm.fill(1, 0, 10*16000)
        pcm.fill(2, 20*16000, 40*16000)
        pcm.fill(3, 50*16000, 70*16000)
        val result = EnvSoundGaps.extract(pcm, listOf(0 until 10*16000, 20*16000 until 40*16000, 50*16000 until 70*16000))
        assertEquals(790400, result.availableSamples)
        assertEquals(480000, result.pcm.size)
        assertEquals(1.toShort(), result.pcm.first())
        assertEquals(3.toShort(), result.pcm.last())
        assertEquals(setOf<Short>(1, 2, 3), result.pcm.toSet())
        assertTrue((1 until result.pcm.size).all { result.pcm[it-1] <= result.pcm[it] })
    }

    @Test fun labelsAndSpecialSoundsOnlyUseGapScoresAndPersistBothSources() {
        val whole = scores("Speech" to .9f, "Rain" to .9f, "Knock" to .9f)
        val gaps = scores("Typing" to .1f, "Bark" to .2f)
        val evidence = EnvSoundEvidence(whole, gaps, 48000, 48000)
        val decision = EnvSoundRules.decide(evidence, enabled, false)
        assertEquals(setOf(EnvTag.Keyboard, EnvTag.Pet), decision.tags)
        assertEquals(setOf(SpecialSound.DogBark), decision.sounds)
        assertEquals(SegmentVerdict.Keep, decision.verdict)
        val json = JSONObject(evidence.json())
        assertEquals(.9, json.getDouble("Rain"), 1e-6)
        assertEquals(0.0, json.getJSONObject("_gap075").getJSONObject("scores").getDouble("Rain"), 0.0)
        assertEquals(48000, json.getJSONObject("_gap075").getInt("usedSamples"))
    }

    @Test fun gapScoresCannotChangeSkipMediaOrIdentityProtection() {
        val loudGap = scores("Music" to .99f, "Bark" to .9f)
        for (whole in listOf(scores("Speech" to .9f), scores("Music" to .95f), scores("Speech" to .8f, "Music" to .95f))) {
            for (me in listOf(false, true)) {
                val old = EnvSoundRules.decide(whole, enabled, me)
                val next = EnvSoundRules.decide(EnvSoundEvidence(whole, loudGap, 32000, 32000), enabled, me)
                assertEquals(old.verdict, next.verdict)
                assertEquals(old.skip, next.skip)
                assertEquals(old.reason, next.reason)
                if (next.verdict == SegmentVerdict.Media) assertTrue(next.sounds.isEmpty())
            }
        }
    }

    @Test fun insufficientMissingOrInvalidGapsNeverFallBackToWholeLabels() = runBlocking {
        val whole = scores("Rain" to .9f)
        var reads = 0
        var inferences = 0
        val pcm = ShortArray(16000)
        val analysis = EnvSoundAnalysis({ reads++; pcm }, { inferences++; whole }, { false },
            extractGaps = { assertSame(pcm, it); EnvSoundGaps.Result(ShortArray(0), 31999) })
        val result = analysis.analyze(File("unused")).first
        assertEquals(1, reads)
        assertEquals(1, inferences)
        assertEquals(31999, result.availableGapSamples)
        for (evidence in listOf(result, EnvSoundEvidence(whole),
            EnvSoundEvidence(whole, whole, 31999, 31999),
            EnvSoundEvidence(whole, scores("Rain" to Float.NaN), 32000, 32000))) {
            val decision = EnvSoundRules.decide(evidence, enabled, false)
            assertTrue(decision.tags.isEmpty())
            assertTrue(decision.sounds.isEmpty())
        }
    }

    @Test fun analysisDecodesOnceAndInfersGapOnceWithoutExtraIdentityCheck() = runBlocking {
        val pcm = ShortArray(160000)
        val gap = ShortArray(32000)
        var reads = 0
        val inputs = mutableListOf<ShortArray>()
        val result = EnvSoundAnalysis({ reads++; pcm }, {
            inputs += it
            if (it === pcm) scores("Speech" to .9f) else scores("Music" to .99f)
        }, { error("gap music must not trigger identity") },
            extractGaps = { assertSame(pcm, it); EnvSoundGaps.Result(gap, gap.size) }).analyze(File("unused"))
        assertEquals(1, reads)
        assertEquals(2, inputs.size)
        assertSame(pcm, inputs[0])
        assertSame(gap, inputs[1])
        assertEquals(setOf(EnvTag.Music), EnvSoundRules.decide(result.first, enabled, result.second).tags)
    }

    @Test fun gapFailureKeepsOriginalVerdictAndCancellationPropagates() = runBlocking {
        val phases = mutableListOf<String?>()
        val whole = scores("Music" to .95f)
        val analysis = EnvSoundAnalysis({ ShortArray(32000) }, { whole }, { false }, phase = phases::add,
            extractGaps = { error("vad unavailable") })
        val result = analysis.analyze(File("unused")).first
        assertTrue(result.gapFailed)
        assertEquals(SegmentVerdict.Skipped, EnvSoundRules.decide(result, enabled, false).verdict)
        assertTrue(EnvSoundRules.decide(result, enabled, false).tags.isEmpty())
        assertNull(phases.last())
        try {
            EnvSoundAnalysis({ ShortArray(32000) }, { whole }, { false },
                extractGaps = { throw CancellationException("cancel") }).analyze(File("unused"))
            fail("取消必须向上传播")
        } catch (_: CancellationException) { /* expected */ }
    }

    /** 同轮私有证据回放：核对Python取样与生产Kotlin逐采样一致，360段规则结论一致；不重复推理。 */
    @Test fun realRecordingEvidenceReplaysThroughProductionExtractionAndRules() {
        val path = System.getenv("TAOSHENG_ENV075_EVIDENCE")
        assumeTrue("仅本机075证据回放时设置路径", path != null)
        val dir = File(path!!)
        val expected = File(dir, "expected.jsonl").readLines().map(::JSONObject).associateBy { it.getLong("id") }
        val rows = File(dir, "scores.jsonl").readLines().map(::JSONObject)
        assertEquals(360, rows.size)
        fun scores(json: JSONObject): Map<String, Float> = json.keys().asSequence().associateWith { json.getDouble(it).toFloat() }
        for (row in rows) {
            val pcm = ShortArray(row.getInt("audio_samples")) { ((it % 65536)-32768).toShort() }
            val raw = row.getJSONArray("raw_gaps")
            val extracted = EnvSoundGaps.extract(pcm, (0 until raw.length()).map {
                raw.getJSONArray(it).let { r -> r.getInt(0) until r.getInt(1) }
            })
            assertEquals(row.getInt("available_samples"), extracted.availableSamples)
            val spans = row.getJSONArray("selected_spans")
            val reference = ShortArray(row.getInt("used_samples"))
            var at = 0
            for (i in 0 until spans.length()) {
                val span = spans.getJSONArray(i)
                val a = span.getInt(0); val b = span.getInt(1)
                pcm.copyInto(reference, at, a, b); at += b-a
            }
            assertArrayEquals("取样 ${row.getLong("id")}", reference, extracted.pcm)
            val evidence = EnvSoundEvidence(scores(row.getJSONObject("whole")), scores(row.getJSONObject("gaps")),
                extracted.availableSamples, extracted.pcm.size)
            val decision = EnvSoundRules.decide(evidence, enabled, false)
            val want = expected.getValue(row.getLong("id"))
            fun names(key: String) = want.getJSONArray(key).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
            assertEquals(names("new_tags"), decision.tags.map { it.name }.toSet())
            assertEquals(names("new_sounds"), decision.sounds.map { it.name }.toSet())
            assertEquals(want.getString("verdict"), decision.verdict.name)
        }
    }
}
