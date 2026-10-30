package app.resound.transcribe

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import java.io.File

class DoubaoIncremental073Test {
    @Test fun revisionDuplicateOverlapAndEmptyFinalPreserveCompleteResult() {
        val acc = DoubaoIncremental()
        acc.accept(JSONObject("""{"result":{"utterances":[{"start_time":0,"end_time":100,"text":"确定","definite":true},{"start_time":200,"end_time":400,"text":"临时","definite":false}]}}"""), false)
        acc.accept(JSONObject("""{"result":{"utterances":[{"start_time":0,"end_time":100,"text":"确定","definite":true},{"start_time":200,"end_time":400,"text":"尾句A","definite":false,"additions":{"speaker_id":"0"}},{"start_time":200,"end_time":450,"text":"尾句B","definite":false,"additions":{"speaker_id":"1"}}]}}"""), false)
        val result = DoubaoProtocol.result(acc.accept(JSONObject(), true), 1000, null)
        assertEquals(listOf("确定", "尾句A", "尾句B"), result.sentences.map { it.text })
        assertEquals(listOf(null, "0", "1"), result.sentences.map { it.speakerTag })
        assertEquals("single", JSONObject(String(DoubaoProtocol.config("pcm"))).getJSONObject("request").getString("result_type"))
        assertEquals("full", JSONObject(String(DoubaoProtocol.config("pcm", incrementalResults = false))).getJSONObject("request").getString("result_type"))
    }

    @Test fun replayRecordedSinglePacketsMatchesProbeSentenceTimeAndSpeakerExactly() {
        val root = System.getenv("ASR073_PCM_DIR")
        assumeNotNull(root)
        for (id in listOf("S1", "S2", "S3", "M1", "M2")) {
            val saved = JSONObject(File(root, "incremental/${id}_single.json").readText())
            assertTrue(saved.getBoolean("success"))
            val acc = DoubaoIncremental()
            val replies = saved.getJSONArray("replies")
            var result: AsrResult? = null
            for (i in 0 until replies.length()) {
                val packet = replies.getJSONObject(i)
                val final = packet.getInt("flags") and 2 != 0
                val body = acc.accept(packet.getJSONObject("payload"), final)
                if (final) result = DoubaoProtocol.result(body, 300_000, null)
            }
            val expected = JSONObject().put("result", JSONObject().put("utterances", saved.getJSONArray("utterances")))
            assertEquals(id, DoubaoProtocol.result(expected, 300_000, null).sentences, result!!.sentences)
        }
    }
}
