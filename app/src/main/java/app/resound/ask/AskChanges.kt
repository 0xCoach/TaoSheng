package app.resound.ask

import app.resound.data.writeTransaction
import app.resound.data.*
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.time.ZoneId

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

    private suspend fun item(id: Long): app.resound.ask.core.AskChangeTarget? = db.readSql("""SELECT i.content,i.status,i.dueAt
        FROM memory_items i LEFT JOIN memory_events e ON e.id=i.eventId WHERE i.id=? AND i.deleted=0 AND (e.id IS NULL OR e.deleted=0)""", arrayOf(id)) { c ->
        if (c.step()) app.resound.ask.core.AskChangeTarget(c.getText(0), c.getText(1), if (c.isNull(2)) null else c.getLong(2)) else null
    }
    private suspend fun event(id: Long): app.resound.ask.core.AskChangeTarget? = db.readSql("SELECT title,starred FROM memory_events WHERE id=? AND deleted=0", arrayOf(id)) { c ->
        if (c.step()) app.resound.ask.core.AskChangeTarget(c.getText(0), c.getLong(1).toInt().toString(), null) else null
    }
    private fun due(args: JSONObject): Long? = if (!args.has("due") || args.isNull("due")) null
        else AskTools.timestamp(args.getString("due"), false, ZoneId.systemDefault())
    suspend fun propose(input: JSONObject, sources: List<AskSource>): ProposedChange {
        val core = app.resound.ask.core.AskChangesCore(object : app.resound.ask.core.AskChangeStore {
            override suspend fun item(id: Long) = this@AskChanges.item(id)
            override suspend fun event(id: Long) = this@AskChanges.event(id)
        }, kotlinx.datetime.TimeZone.of(ZoneId.systemDefault().id))
        val proposal = core.propose(input.toString(), sources)
        return ProposedChange(proposal.action, proposal.operation, JSONObject(proposal.args), proposal.expected)
    }

    suspend fun resolve(messageId: Long, confirm: Boolean) {
        try {
            db.writeTransaction {
                val row = db.ask().message(messageId) ?: return@writeTransaction
                val proposal = ProposedChange.decode(row.actionJson) ?: return@writeTransaction
                if (row.role != AskRole.Assistant.name || proposal.action.state != AskActionState.Pending) return@writeTransaction
                if (confirm) apply(proposal)
                db.ask().setAction(messageId, proposal.copy(action = proposal.action.copy(
                    state = if (confirm) AskActionState.Done else AskActionState.Cancelled)).encode())
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            db.writeTransaction {
                val proposal = ProposedChange.decode(db.ask().message(messageId)?.actionJson) ?: return@writeTransaction
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
