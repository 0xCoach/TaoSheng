package app.resound.digest

import app.resound.data.MemoryDao
import app.resound.data.MemoryItemEntity
import kotlinx.coroutines.*
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

class RoomManualTodos(
    private val dao: MemoryDao,
    private val scope: CoroutineScope,
    private val credentials: () -> Pair<LlmPreset, String>,
    private val chatJson: suspend (LlmPreset, String, String, String) -> LlmReply,
    private val recordUsage: suspend (LlmReply) -> Unit,
    private val today: () -> LocalDate = { LocalDate.now() },
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val timeoutMillis: Long = 20_000,
) : ManualTodos {
    override suspend fun draft(text: String): TodoDrafts {
        val fallback = TodoDrafts(listOf(TodoDraft("待办", "我要做", text, null, null, null)), false)
        return try {
            withTimeoutOrNull(timeoutMillis) {
                val (preset, key) = credentials()
                if (key.isBlank()) return@withTimeoutOrNull fallback
                val reply = chatJson(preset, key, ManualTodoPrompt.system(today(), java.time.Instant.ofEpochMilli(now()).atZone(zone).toLocalTime()),
                    JSONObject().put("sourceText", text).toString())
                withContext(NonCancellable) { recordUsage(reply) }
                TodoDrafts(ManualTodoPrompt.parse(reply.content, zone), true)
            } ?: fallback
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { fallback }
    }

    override fun add(drafts: List<TodoDraft>, origin: ItemOrigin, sourceText: String) {
        require(origin == ItemOrigin.Typed || origin == ItemOrigin.Voice)
        require(drafts.size in 1..5)
        val at = now()
        val rows = drafts.map { draft ->
            require(draft.category in setOf("待办", "约定", "备忘") && draft.content.isNotBlank())
            require(draft.kind == null || draft.kind in setOf("我要做", "我答应别人", "别人答应我"))
            MemoryItemEntity(eventId = null, category = draft.category,
                kind = if (draft.category == "待办") draft.kind ?: "我要做" else null,
                content = draft.content.trim(), dueText = draft.dueText, dueAt = draft.dueAt,
                who = draft.who?.trim()?.takeIf { it.isNotEmpty() }, lineId = null,
                status = "Accepted", createdAt = at, statusChangedAt = at, noMerge = true,
                origin = if (origin == ItemOrigin.Typed) "typed" else "voice", sourceText = sourceText,
                strongReminder = draft.strongReminder && draft.dueAt != null)
        }
        scope.launch(Dispatchers.IO) { dao.addManualItems(rows) }
    }
}
