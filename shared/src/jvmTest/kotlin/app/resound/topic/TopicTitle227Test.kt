package app.resound.topic

import app.resound.digest.core.MemoryEventData
import app.resound.digest.core.TranscriptLineData
import app.resound.platform.NowMillis
import app.resound.platform.ZoneProvider
import app.resound.skills.core.MemoryTopicStore160
import app.resound.topic.core.*
import app.resound.topic.core.TopicGroup
import app.resound.topic.core.TopicRow
import app.resound.topic.core.TopicSpanRow
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.datetime.TimeZone
import org.junit.Assert.*
import org.junit.Test

/** 卡 227：没起名字的专题用整理出的标题（整理中用暂定标题），列表能认出是哪一场。 */
class TopicTitle227Test {
    private val minute = 60_000L

    private class TitledStore(val inner: MemoryTopicStore160, val titles: Map<Long, String>) : TopicStore by inner {
        override fun observeAll() = kotlinx.coroutines.flow.flow { emit(inner.all()) }
        override suspend fun event(id: Long) = titles[id]?.let {
            MemoryEventData(id = id, startAt = 0, endAt = 1, scene = "会议", title = it, summary = "", pointsJson = "[]", peopleJson = "[]",
                place = null, emotion = null, intensity = null, important = false, createdAt = 0, model = "m")
        }
    }

    @Test fun doneTopicWithoutNameCarriesEventTitleAndIsSearchable() = runBlocking {
        var now = 1_000_000L
        val inner = MemoryTopicStore160()
        val store = TitledStore(inner, mapOf(77L to "大模型与智能体框架培训"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val repo = TopicRulesRepository(store, scope, NowMillis { now }, ZoneProvider { "Asia/Shanghai" })
            val unnamed = repo.start(TopicKind.Meeting, null, 15).topicId!!
            now += 60_000; repo.end()
            inner.update(inner.get(unnamed)!!.row.copy(status = "Done", eventId = 77))
            now += 60_000
            val named = repo.start(TopicKind.Meeting, "周三例会", 15).topicId!!
            now += 60_000; repo.end()
            inner.update(inner.get(named)!!.row.copy(status = "Done", eventId = 77))

            val history = repo.observeHistory().first().associateBy { it.id }
            assertEquals("大模型与智能体框架培训", history.getValue(unnamed).title)
            assertNull("用户起了名字就不再带整理标题", history.getValue(named).title)
            assertEquals("大模型与智能体框架培训", repo.observe(unnamed).first()!!.title)
            assertEquals(listOf(unnamed), TopicProcessingRules.history(history.values.toList(), "智能体").map { it.id })
        } finally { scope.coroutineContext[Job]!!.cancel() }
    }

    @Test fun unfinishedTopicGetsProvisionalTitleFromFirstOrganizedBlock() = runBlocking {
        val g = TopicGroup(TopicRow(id = 1, kind = "Normal", name = null, status = "Ended", createdAt = 0),
            listOf(TopicSpanRow(topicId = 1, startAt = 0, endAt = 30 * minute)), emptyList())
        val lines = (0..2).flatMap { b -> (0..4).map { i ->
            TranscriptLineData(id = b * 5L + i + 1, segmentId = b + 1L, startAt = b * 10 * minute + i * minute,
                endAt = b * 10 * minute + i * minute + 1000, text = "块$b 句$i", speakerTag = "0")
        } }
        val store = MemoryTopicCheckpoints()
        val generator = TopicBlockGenerator({ "提取 events items" }, { error("普通专题无卡模板") }, { s, u ->
            if (s == TopicBlockGenerator.NORMAL_SUMMARY) "{\"summary\":\"全文纪要\"}"
            // 第一块失败：暂定标题应取第一块“整理好的”，也就是第二块
            else if (u.contains("块0 句0")) throw IllegalStateException("模拟失败")
            else if (u.contains("块1 句0")) """{"events":[{"from":1,"to":2,"title":"  ","summary":"空标题","points":[]},{"from":3,"to":5,"title":"预算讨论","summary":"s","points":[]}],"items":[]}"""
            else """{"events":[{"from":1,"to":5,"title":"排期","summary":"s","points":[]}],"items":[]}"""
        }, { emptyList() }, { 30 * minute }, { TimeZone.UTC }, "涛声")
        TopicBlockProcessor(1, store, generator, { it }, { TopicFailure(TopicFailureKind.Permanent, "模拟失败") }, {}, { 30 * minute },
            { (it / 60_000).toString() }).run(g, lines, emptyList(), { false }, true, { "" })
        val progress = store.read(1)!!.progress
        assertEquals(1, progress.blocksFailed)
        assertEquals("预算讨论", progress.title)
    }
}
