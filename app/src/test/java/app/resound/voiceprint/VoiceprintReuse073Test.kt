package app.resound.voiceprint

import app.resound.data.TranscriptLineEntity
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import java.io.File

/** 否决门禁：标签中心与逐窗证据不等价，不能用中心填充每个有效窗。 */
class VoiceprintReuse073Test {
    private fun line(start: Long, end: Long) = TranscriptLineEntity(
        id = 1, segmentId = 1, startAt = start, endAt = end, text = "测试", speakerTag = "0")
    private fun observation(a: Long, b: Long, vector: FloatArray?) = WindowedVoiceprint.Observation(
        WindowedVoiceprint.Window(a, b), VoiceprintFeatureCache.Feature(vector, b-a))
    private fun JSONArray.vector() = FloatArray(length()) { getDouble(it).toFloat() }

    @Test fun centroidCanTurnMixedEvidenceIntoFalseCertainty() {
        val a = FloatArray(192).apply { this[0] = 1f }
        val b = FloatArray(192).apply { this[1] = 1f }
        val center = VoiceprintMath.representative(listOf(a, b))
        val candidates = listOf(SpeakerIdentity.Candidate(0, "测试人物", a))
        val original = listOf(observation(0, 3000, a), observation(3000, 6000, b))
        val reused = original.map { it.copy(feature = it.feature!!.copy(vector = center)) }
        assertNull(WindowedVoiceprint.vote(line(0, 6000), original, candidates).personId)
        assertEquals(0L, WindowedVoiceprint.vote(line(0, 6000), reused, candidates).personId)
    }

    @Test fun realS2CounterexampleAlsoChangesProductionMatcher() {
        val root = System.getenv("ASR073_PCM_DIR")
        assumeNotNull(root)
        val cases = JSONArray(File(root, "identity-counterexample.json").readText())
        assertEquals(1, cases.length())
        val sample = cases.getJSONObject(0)
        val span = sample.getJSONObject("line")
        val line = line(span.getLong("a"), span.getLong("b"))
        val banks = sample.getJSONArray("candidates")
        val candidates = (0 until banks.length()).map { index ->
            val bank = banks.getJSONObject(index)
            val values = bank.getJSONArray("vectors")
            val vectors = (0 until values.length()).map { values.getJSONArray(it).vector() }
            SpeakerIdentity.Candidate(bank.getLong("id"), "匿名测试人物",
                VoiceprintMath.representative(vectors), vectors, WindowedVoiceprint.SURE)
        }
        val windows = sample.getJSONArray("windows")
        val observations = (0 until windows.length()).map { index ->
            val w = windows.getJSONArray(index)
            observation(w.getLong(0), w.getLong(1), if (w.isNull(2)) null else w.getJSONArray(2).vector())
        }
        val center = sample.getJSONArray("center").vector()
        val reused = observations.map { observation ->
            val feature = observation.feature!!
            observation.copy(feature = feature.copy(vector = center.takeIf { feature.vector != null }))
        }
        assertNull(WindowedVoiceprint.vote(line, observations, candidates).personId)
        assertEquals(0L, WindowedVoiceprint.vote(line, reused, candidates).personId)
    }
}
