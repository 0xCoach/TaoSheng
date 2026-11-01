package app.resound.things

import androidx.room.withTransaction
import app.resound.data.CaptureDatabase
import app.resound.data.EventEntity
import app.resound.data.TranscriptLineEntity
import app.resound.digest.DigestPrompt
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

/** 自动寻物复用整理请求；本类不持有 LLM 客户端，不发起任何网络请求。 */
class DigestThingExtraction internal constructor(
    private val db: CaptureDatabase,
    private val repository: RoomThingRepository,
    private val template: () -> String,
    private val initialize: suspend () -> Unit = {},
    private val notify: (ThingRecordRow) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
) {
    internal data class Batch(val lines: List<TranscriptLineEntity>, val eligible: Set<Long>,
        val known: Set<Long>, val revision: Long, val system: String, val input: String)

    internal suspend fun prepare(lines: List<TranscriptLineEntity>): Batch? {
        initialize()
        val state = db.things().state() ?: ThingWatchState()
        if (!state.enabled) return null
        val eligible = db.things().eligible(lines.filter { it.id > state.watermark }.map { it.id }).map { it.id }.toSet()
        if (eligible.isEmpty()) return null
        val rows = db.things().allThings()
        val catalog = things(rows, db.things().allRecords()).sortedByDescending { t -> rows.first { it.id == t.id }.updatedAt }
        val previous = ThingPrompt.context(lines, db.things().context(lines.minOf { it.id } - 1).reversed())
        val selected = ThingPrompt.known(lines, previous, catalog)
        val data = JSONObject(ThingPrompt.input(emptyList(), previous, selected))
        // 整理正文已包含所有新原话，附录不重复发送；旧上下文没有可引用编号。
        data.remove("lines")
        val context = data.getJSONArray("context")
        for (i in 0 until context.length()) context.getJSONObject(i).remove("id")
        data.put("eligibleLines", JSONArray(lines.mapIndexedNotNull { i, l -> (i + 1).takeIf { l.id in eligible } }))
        val rules = """
            附加任务：在同一次整理中顺便提取寻物事实。以下寻物规则只约束顶层新增的 things 数组，不改变 events/items/done_hints 的格式。
            ${ThingPrompt.system(template())}
            寻物事实与事件概括分别判断：先逐句核对新原话与 context 的衔接，再归纳事件。旧上下文提出对象和目标位置、新句确认收好或做完时，要补全并记录这条新完成事实。后面的设计讨论、示例或影视引用仅约束对应局部，不能反向把前面的现实问答或完成确认也判为演示；不能以事件标题、摘要或整段印象替代逐句检查。
            输出整合规则（优先于上文的独立输出格式）：最终仍输出一个整理 JSON，先输出顶层 "things":[]，再输出 events、items、done_hints；将寻物示例中 items 的元素放进 things，绝不能放入整理的 items。
            寻物 lineId 使用正文 [1]、[2] 的编号，不是数据库 ID，且必须属于附录 eligibleLines。新原话就是整理正文，不重复提供 lines。context 可补全新句的对象和位置，但不能作为 lineId 的来源。没有寻物事实返回 things:[]。媒体/导入内容不得提取。必须保留正常的时间线整理结果。
        """.trimIndent()
        return Batch(lines, eligible, selected.map { it.id }.toSet(), state.revision, rules, "\n\n寻物参考资料：$data")
    }

    /** 整理与寻物同事务入库；寻物格式问题不触发另一轮付费请求。 */
    internal suspend fun commit(batch: Batch?, content: String, events: List<DigestPrompt.ParsedEvent>, save: suspend () -> Unit) {
        if (batch == null) { save(); return }
        val proposals = mutableListOf<ThingProposal>()
        var rejected = 0
        val root = JSONObject(content.trim().removePrefix("```json").removePrefix("```").removeSuffix("```"))
        val array = root.optJSONArray("things")
        if (array == null) rejected++
        val mediaLines = events.filter { it.scene == "媒体" }.flatMap { it.lineIds }.toSet()
        if (array != null) for (i in 0 until array.length()) {
            try {
                val value = JSONObject(array.getJSONObject(i).toString())
                val source = batch.lines.getOrNull(value.getInt("lineId") - 1) ?: error("invalid source")
                require(source.id in batch.eligible && source.id !in mediaLines)
                value.put("lineId", source.id)
                proposals += ThingPrompt.parse(JSONObject().put("items", JSONArray().put(value)).toString(), batch.lines, batch.known, now())
            } catch (e: CancellationException) { throw e } catch (_: Exception) { rejected++ }
        }
        val notices = db.withTransaction {
            // 在改写事件归属前核对原始资格及原话，防止删除/重转写后复活旧请求。
            if (db.memory().refreshLines(batch.lines.map { it.id }).size != batch.lines.size) return@withTransaction emptyList<ThingRecordRow>()
            val current = db.things().state() ?: ThingWatchState()
            val live = db.things().eligible(batch.eligible.toList()).associateBy { it.id }
            val valid = if (current.enabled && current.revision == batch.revision) proposals.filter { p ->
                live[p.row.sourceLineId]?.let { it.text == p.row.sourceText && it.startAt == p.row.at && it.id > current.watermark } == true
            } else emptyList()
            save()
            val existing = db.things().allRecords()
            val saved = valid.filterNot { p -> existing.any { r ->
                r.sourceLineId == p.row.sourceLineId && r.name == p.row.name && r.action == p.row.action && r.place == p.row.place && r.who == p.row.who
            } }.mapNotNull { repository.propose(it) }
            db.capture().event(EventEntity(at = now(), type = "ThingsShared", detail =
                "lines=${batch.eligible.size} results=${saved.size} rejected=$rejected sharedRequest=true", batteryPercent = null, charging = null))
            saved.filter { it.explicit }
        }
        notices.distinctBy { it.id }.forEach { runCatching { notify(it) } }
    }
}
