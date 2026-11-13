package app.resound.ask.core
import app.resound.ask.*
import app.resound.net.Obj
import kotlinx.datetime.TimeZone

data class AskChangeTarget(val content: String, val status: String, val dueAt: Long?) {
    fun snapshot() = Obj().put("content", content).put("status", status).put("dueAt", dueAt).toString()
}
interface AskChangeStore {
    suspend fun item(id: Long): AskChangeTarget?
    suspend fun event(id: Long): AskChangeTarget?
}
class AskChangesCore(private val store: AskChangeStore, private val zone: TimeZone) {
    suspend fun propose(input: String, sources: List<AskSource>): ChangeProposal = proposeJson(Obj(input), sources)
    private fun due(args: Obj): Long? = if (!args.has("due") || args.isNull("due")) null
        else AskTime.timestamp(args.getString("due"), false, zone)
    private fun date(value: Long?) = value?.let { AskTime.format(it, zone) } ?: "无截止时间"
    private fun status(value: String) = mapOf("Done" to "完成", "Accepted" to "接受", "Ignored" to "忽略", "Pending" to "待确认").getValue(value)

    private suspend fun proposeJson(input: Obj, sources: List<AskSource>): ChangeProposal {
        val args = Obj(input.toString())
        val op = args.getString("operation")
        val allowed = when (op) {
            "item_status" -> setOf("operation", "id", "status")
            "create_item" -> setOf("operation", "category", "content", "due")
            "item_due" -> setOf("operation", "id", "due")
            "event_star" -> setOf("operation", "id", "starred")
            else -> throw IllegalArgumentException("未知改动")
        }
        require(args.keys().asSequence().all { it in allowed })
        var expected: String? = null
        val summary: String; val detail: String
        if (op == "create_item") {
            val category = args.getString("category"); require(category in setOf("待办", "备忘"))
            val content = args.getString("content").trim(); require(content.length in 1..2000)
            args.put("content", content)
            summary = "新建${category}：${content.take(100)}"
            detail = "$content\n截止：${date(due(args))}；确认后加入清单，并创建一件『问问新建』事件。"
        } else {
            val id = args.getLong("id"); require(id > 0)
            val kind = if (op == "event_star") "event" else "item"
            require(sources.any { it.kind == kind && it.id == id }) { "请先检索要修改的记录" }
            val target = (if (kind == "event") store.event(id) else store.item(id)) ?: throw IllegalArgumentException("记录已不存在")
            expected = target.snapshot()
            when (op) {
                "item_status" -> {
                    val next = args.getString("status"); require(next in setOf("Done", "Accepted", "Ignored"))
                    summary = "把『${target.content.take(100)}』标为${status(next)}"
                    detail = "${target.content}\n状态：${status(target.status)} → ${status(next)}"
                }
                "item_due" -> {
                    require(args.has("due"))
                    summary = "修改『${target.content.take(100)}』的截止时间"
                    detail = "${target.content}\n截止：${date(target.dueAt)} → ${date(due(args))}"
                }
                else -> {
                    require(args.opt("starred") is Boolean)
                    val starred = args.getBoolean("starred")
                    summary = "${if (starred) "标重点" else "取消重点"}：${target.content.take(100)}"
                    detail = "${target.content}\n重点：${target.status == "1"} → $starred" +
                        if (starred) "；确认后，待确认的待办和备忘会加入进行中。" else "；条目状态保持不变。"
                }
            }
        }
        return ChangeProposal(AskAction(summary, detail, AskActionState.Pending), op, args.toString(), expected)
    }

}
