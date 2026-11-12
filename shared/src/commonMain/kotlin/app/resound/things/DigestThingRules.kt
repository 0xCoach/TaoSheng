package app.resound.things

import app.resound.platform.legacyText
import app.resound.platform.legacyLong
import app.resound.platform.SharedJson
import app.resound.platform.NowMillis
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

object DigestThingRules {
        fun system(template: String, today: String): String = """
            附加任务：在同一次整理中顺便提取寻物事实。以下寻物规则只约束顶层新增的 things 数组，不改变 events/items/done_hints 的格式。
            ${ThingPromptRules.system(template, today)}
            寻物事实与事件概括分别判断：先逐句核对新原话与 context 的衔接，再归纳事件。旧上下文提出对象和目标位置、新句确认收好或做完时，要补全并记录这条新完成事实。后面的设计讨论、示例或影视引用仅约束对应局部，不能反向把前面的现实问答或完成确认也判为演示；不能以事件标题、摘要或整段印象替代逐句检查。
            输出整合规则（优先于上文的独立输出格式）：最终仍输出一个整理 JSON，先输出顶层 "things":[]，再输出 events、items、done_hints；将寻物示例中 items 的元素放进 things，绝不能放入整理的 items。
            寻物 lineId 使用正文 [1]、[2] 的编号，不是数据库 ID，且必须属于附录 eligibleLines。新原话就是整理正文，不重复提供 lines。context 可补全新句的对象和位置，但不能作为 lineId 的来源。没有寻物事实返回 things:[]。媒体/导入内容不得提取。必须保留正常的时间线整理结果。
        """.trimIndent()

    fun input(lines: List<ThingLine>, previous: List<ThingLine>, selected: List<Thing>, eligible: Set<Long>): String {
        val data = SharedJson.parseToJsonElement(ThingPromptRules.input(emptyList(), previous, selected)).jsonObject.toMutableMap()
        data.remove("lines")
        data["context"] = JsonArray(data.getValue("context").jsonArray.map { JsonObject(it.jsonObject - "id") })
        data["eligibleLines"] = JsonArray(lines.mapIndexedNotNull { i, l -> (i + 1).takeIf { l.id in eligible }?.let(::JsonPrimitive) })
        return "\n\n寻物参考资料：${JsonObject(data).legacyText()}"
    }
    data class Parsed(val proposals: List<ThingProposalData>, val rejected: Int)
    fun parse(content: String, lines: List<ThingLine>, eligible: Set<Long>, known: Set<Long>, mediaLines: Set<Long>, now: NowMillis): Parsed {
        val root = SharedJson.parseToJsonElement(content.trim().removePrefix("```json").removePrefix("```").removeSuffix("```")).jsonObject
        val array = root["things"] as? JsonArray
        val proposals = mutableListOf<ThingProposalData>()
        var rejected = if (array == null) 1 else 0
        array?.forEach { element ->
            try {
                val value = element.jsonObject.toMutableMap()
                val source = lines.getOrNull(value.getValue("lineId").legacyLong().toInt() - 1) ?: error("invalid source")
                require(source.id in eligible && source.id !in mediaLines)
                value["lineId"] = JsonPrimitive(source.id)
                proposals += ThingPromptRules.parse(buildJsonObject { put("items", JsonArray(listOf(JsonObject(value)))) }.toString(), lines, known, now.now())
            } catch (e: CancellationException) { throw e } catch (_: Exception) { rejected++ }
        }
        return Parsed(proposals, rejected)
    }
    fun valid(proposals: List<ThingProposalData>, current: ThingWatchData, revision: Long, live: List<ThingLine>): List<ThingProposalData> {
        val byId = live.associateBy { it.id }
        return if (current.enabled && current.revision == revision) proposals.filter { p ->
            byId[p.row.sourceLineId]?.let { it.text == p.row.sourceText && it.startAt == p.row.at && it.id > current.watermark } == true
        } else emptyList()
    }
    fun duplicate(row: ThingRecordData, existing: ThingRecordData) = existing.sourceLineId == row.sourceLineId &&
        existing.name == row.name && existing.action == row.action && existing.place == row.place && existing.who == row.who
}
