package app.resound.ask

import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import app.resound.data.*
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 私有执行载荷与界面 AskAction 分离；只能执行本地白名单操作，模型不能提供 SQL。 */
data class ProposedChange(val action: AskAction, val operation: String, val args: JSONObject, val expected: String?) {
    fun encode(): String = JSONObject().put("summary", action.summary).put("detail", action.detail)
        .put("state", action.state.name).put("operation", operation).put("args", args).put("expected", expected).toString()
    companion object {
        fun decode(raw: String?): ProposedChange? = raw?.let {
            val j = JSONObject(it)
            ProposedChange(AskAction(j.getString("summary"), j.optString("detail").takeIf { it.isNotEmpty() },
                AskActionState.valueOf(j.getString("state"))), j.getString("operation"), j.getJSONObject("args"),
                j.optString("expected").takeIf { it.isNotEmpty() })
        }
    }
}

class AskChanges(private val db: CaptureDatabase, private val now: () -> Long = System::currentTimeMillis,
    private val onChanged: () -> Unit = {}) {
    private data class Target(val content: String, val status: String, val dueAt: Long?) {
        fun snapshot() = JSONObject().put("content", content).put("status", status).put("dueAt", dueAt).toString()
    }
    private fun item(id: Long): Target? = db.query(SimpleSQLiteQuery("""SELECT i.content,i.status,i.dueAt
        FROM memory_items i LEFT JOIN memory_events e ON e.id=i.eventId WHERE i.id=? AND i.deleted=0 AND (e.id IS NULL OR e.deleted=0)""", arrayOf(id))).use { c ->
        if (c.moveToFirst()) Target(c.getString(0), c.getString(1), if (c.isNull(2)) null else c.getLong(2)) else null
    }
    private fun event(id: Long): Target? = db.query(SimpleSQLiteQuery("SELECT title,starred FROM memory_events WHERE id=? AND deleted=0", arrayOf(id))).use { c ->
        if (c.moveToFirst()) Target(c.getString(0), c.getInt(1).toString(), null) else null
    }
    private fun due(args: JSONObject): Long? = if (!args.has("due") || args.isNull("due")) null
        else AskTools.timestamp(args.getString("due"), false, ZoneId.systemDefault())
    private fun date(value: Long?) = value?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) } ?: "无截止时间"
    private fun status(value: String) = mapOf("Done" to "完成", "Accepted" to "接受", "Ignored" to "忽略", "Pending" to "待确认").getValue(value)

    fun propose(input: JSONObject, sources: List<AskSource>): ProposedChange {
        val args = JSONObject(input.toString())
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
            val target = (if (kind == "event") event(id) else item(id)) ?: throw IllegalArgumentException("记录已不存在")
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
                    require(args.get("starred") is Boolean)
                    val starred = args.getBoolean("starred")
                    summary = "${if (starred) "标重点" else "取消重点"}：${target.content.take(100)}"
                    detail = "${target.content}\n重点：${target.status == "1"} → $starred" +
                        if (starred) "；确认后，待确认的待办和备忘会加入进行中。" else "；条目状态保持不变。"
                }
            }
        }
        return ProposedChange(AskAction(summary, detail, AskActionState.Pending), op, args, expected)
    }

    suspend fun resolve(messageId: Long, confirm: Boolean) {
        try {
            db.withTransaction {
                val row = db.ask().message(messageId) ?: return@withTransaction
                val proposal = ProposedChange.decode(row.actionJson) ?: return@withTransaction
                if (row.role != AskRole.Assistant.name || proposal.action.state != AskActionState.Pending) return@withTransaction
                if (confirm) apply(proposal)
                db.ask().setAction(messageId, proposal.copy(action = proposal.action.copy(
                    state = if (confirm) AskActionState.Done else AskActionState.Cancelled)).encode())
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            db.withTransaction {
                val proposal = ProposedChange.decode(db.ask().message(messageId)?.actionJson) ?: return@withTransaction
                if (proposal.action.state == AskActionState.Pending) db.ask().setAction(messageId,
                    proposal.copy(action = proposal.action.copy(state = AskActionState.Failed,
                        detail = if (e is StaleChange) "记录已变化或被删除，请重新查询后再修改。" else "修改未成功，数据未更改，请重新提问后重试。")).encode())
            }
        }
        if (confirm) runCatching { onChanged() }
    }
    private class StaleChange : Exception()
    private suspend fun apply(proposal: ProposedChange) {
        require(proposal.operation in setOf("item_status", "item_due", "event_star", "create_item"))
        val args = proposal.args
        val id = args.optLong("id")
        if (proposal.operation != "create_item") {
            val current = if (proposal.operation == "event_star") event(id) else item(id)
            if (current?.snapshot() != proposal.expected) throw StaleChange()
        }
        when (proposal.operation) {
            "item_status" -> db.memory().setItemStatus(id, args.getString("status"), now())
            "item_due" -> {
                val row = db.memory().itemIncludingDeleted(id) ?: throw StaleChange()
                db.memory().updateItem(id, row.content, due(args), args.optString("due").length > 10, row.who, now())
            }
            "event_star" -> db.memory().setStarred(id, args.getBoolean("starred"), now())
            "create_item" -> {
                val at = now()
                val eventId = db.memory().insertEvent(MemoryEventEntity(startAt = at, endAt = at, scene = "其他",
                    title = "问问新建", summary = args.getString("content"), pointsJson = "[]", peopleJson = "[]",
                    place = null, emotion = null, intensity = null, important = false, createdAt = at, model = "user-confirmed"))
                db.memory().insertItems(listOf(MemoryItemEntity(eventId = eventId, category = args.getString("category"),
                    kind = null, content = args.getString("content"), dueAt = due(args), dueText = args.optString("due").takeIf { args.has("due") && !args.isNull("due") },
                    who = null, lineId = null, status = "Accepted", createdAt = at, statusChangedAt = at)))
            }
            else -> error("未知改动")
        }
    }
}
