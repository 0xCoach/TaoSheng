package app.resound.net

import app.resound.transcribe.*
import org.junit.Assert.*
import org.junit.Test

class CustomTranscription196Test {
    private fun fixture(name: String) = javaClass.getResource("/custom-asr/issue48-$name.json")!!.readText()
    @Test fun realTwoSpeakerFixturePreservesOnlySegments() {
        val lines = customTranscription(fixture("two"), 57120, true)
        assertEquals(listOf(960L, 17920L, 40020L), lines.map { it.startMillis })
        assertEquals(listOf(16970L, 40020L, 55030L), lines.map { it.endMillis })
        assertEquals(listOf("0", "1", "0"), lines.map { it.speakerTag })
        assertEquals(listOf("美国的包括它的金融，包括资本的走势会产生的变化……", "第二个预言，我相信很多人一直很关心这个……", "场我相信下一轮全球化很可能是以中国为主角……"), lines.map { it.text })
    }
    @Test fun realPlainFixtureAndErrorsKeepOldMapping() {
        assertEquals(listOf(AsrSentence(0, 5590, "开饭时间早上9点至下午5点。", null)), customTranscription(fixture("one"), 5590, true))
        for ((code, expected) in listOf(1004 to AsrError.Auth, 1001 to AsrError.Protocol)) {
            val error = runCatching { customTranscription("""{"base_resp":{"status_code":$code}}""", 1000, true) }.exceptionOrNull()
            assertEquals(expected, (error as AsrException).type)
        }
    }
    @Test fun badRowsDiscardedAndValidRowsClippedSortedAndCleaned() {
        val lines = customTranscription("""{"text":"不应重复", "segments":[
            null, {"start":0,"end":1,"text":{}}, {"start":-1,"end":1,"text":"负数"},
            {"start":1,"end":1,"text":"零长"}, {"start":0,"end":1,"text":" "},
            {"start":8,"end":12,"text":"  [00:08] 说话人2： 后一句  ","speaker":2},
            {"start":0.1,"end":2,"text":"说话人1：前一句","speaker":1},
            {"start":11,"end":12,"text":"越界"}, {"start":"NaN","end":1,"text":"异常"}
        ]}""", 10000, true)
        assertEquals(listOf(AsrSentence(100, 2000, "前一句", "1"), AsrSentence(8000, 10000, "后一句", "0")), lines)
        assertEquals(listOf(AsrSentence(0, 1000, "原文", null)), customTranscription("""{"text":" 原文 ","segments":[{"start":-1,"end":0,"text":"坏段"}]}""", 1000, true))
    }
    @Test fun mixedSpeakerFieldsAndMissingTags() {
        val lines = customTranscription("""{"segments":[
            {"start":0,"end":1,"text":"甲","speaker":"A"},
            {"start":1,"end":2,"text":"乙","speaker_id":9},
            {"start":2,"end":3,"text":"丙"},
            {"start":3,"end":4,"text":"丁","speaker_id":"A"}
        ]}""", 5000, true)
        assertEquals(listOf("0", "1", null, "0"), lines.map { it.speakerTag })
    }
    @Test fun disabledSpeakersRetainsTiming() {
        val enabled = customTranscription(fixture("two"), 57120, true)
        assertEquals(enabled.map { it.copy(speakerTag = null) }, customTranscription(fixture("two"), 57120, false))
    }
}
