package app.resound.ask

import app.resound.data.AskConversationEntity
import app.resound.data.AskDao
import app.resound.data.AskMessageEntity
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 控制命令串行处理，先取消并保存上一轮，再切换会话或提问，避免旧响应串入新会话。 */
class AskStoreRepository(
    private val dao: AskDao,
    private val scope: CoroutineScope,
    private val hasKey: () -> Boolean,
    private val engine: () -> AskSessionEngine,
    private val system: () -> String,
    override val suggestions: kotlinx.coroutines.flow.StateFlow<List<String>>,
    private val refreshSuggestions: suspend () -> Unit,
    private val now: () -> Long,
    private val timeoutMs: Long = 180_000,
    private val readSelected: () -> Long? = { null },
    private val saveSelected: (Long) -> Unit = {},
    private val resolve: (suspend (Long, Boolean) -> Unit)?,
    private val context: suspend (AskSource?, List<AskMessage>) -> AskContext?,
    private val decodeAction: (String?) -> AskAction?,
    private val failureMessage: (Exception) -> String,
) : AskRepository {
    private sealed interface Command {
        data class Ask(val question: String, val source: AskSource? = null) : Command
        data class Resolve(val id: Long, val confirm: Boolean) : Command
        data object Stop : Command
        data object New : Command
        data class Open(val id: Long) : Command
        data class Rename(val id: Long, val title: String) : Command
        data class Delete(val id: Long) : Command
    }
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val state = MutableStateFlow<List<AskMessage>>(emptyList())
    override val messages = state.asStateFlow()
    private val selected = MutableStateFlow<Long?>(null)
    override val currentConversation = selected.asStateFlow()
    override fun history(query: String, limit: Int) = dao.history(query.trim(), limit.coerceAtLeast(1))
    override fun openConversation(id: Long) { commands.trySend(Command.Open(id)) }
    override fun renameConversation(id: Long, title: String) {
        if (title.isNotBlank()) commands.trySend(Command.Rename(id, title.trim().take(80)))
    }
    override fun deleteConversation(id: Long) { commands.trySend(Command.Delete(id)) }
    private fun select(id: Long?) { selected.value = id; saveSelected(id ?: 0) }

    init {
        scope.launch {
            val saved = readSelected()
            var conversation = if (saved == 0L) null else saved?.let { dao.conversation(it) } ?: dao.latest()
            select(conversation?.id)
            state.value = conversation?.let { dao.messages(it.id).map { row -> row.model() } }.orEmpty()
            var active: Job? = null
            for (command in commands) {
                if (command is Command.Rename) { dao.rename(command.id, command.title); continue }
                if (command is Command.Delete && command.id != conversation?.id) { dao.delete(command.id); continue }
                active?.cancelAndJoin()
                active = null
                when (command) {
                    is Command.Resolve -> {
                        resolve?.invoke(command.id, command.confirm)
                        state.value = conversation?.let { dao.messages(it.id).map { row -> row.model() } }.orEmpty()
                    }
                    Command.Stop -> Unit
                    Command.New -> {
                        conversation = null
                        select(null)
                        state.value = emptyList()
                    }
                    is Command.Rename -> Unit
                    is Command.Delete -> {
                        dao.delete(command.id)
                        conversation = null
                        select(null)
                        state.value = emptyList()
                    }
                    is Command.Open -> {
                        dao.conversation(command.id)?.let { row ->
                            conversation = row
                            state.value = dao.messages(row.id).map { it.model() }
                            select(row.id)
                        }
                    }
                    is Command.Ask -> {
                        if (command.source != null) {
                            conversation = null
                            select(null)
                            state.value = emptyList()
                        }
                        val question = command.question
                        val row = conversation ?: AskConversationEntity(createdAt = now(), title = question.take(40)).let {
                            it.copy(id = dao.insertConversation(it))
                        }.also { conversation = it }
                        select(row.id)
                        dao.nameConversation(row.id, question.take(40))
                        val history = state.value
                        // 负 id 只存在流式内存，保存最终结果后替换为 Room 分配的真实 id。
                        // 保存事件入口的定位信息，重开会话后仍能按 ID 重新核验；不保存整份上下文。
                        val user = AskMessage(-1, AskRole.User, question, listOfNotNull(command.source?.copy(quote = "")), now(), false)
                        val assistant = AskMessage(-2, AskRole.Assistant, "", emptyList(), now(), true, "正在查找相关记录…")
                        state.value = history + user + assistant
                        active = scope.launch(start = CoroutineStart.UNDISPATCHED) { answer(row.id, history, user, assistant, command.source) }
                        scope.launch { refreshSuggestions() }
                    }
                }
            }
        }
        scope.launch { refreshSuggestions() }
    }

    override fun ask(question: String) {
        val value = question.trim()
        if (value.isNotEmpty()) commands.trySend(Command.Ask(value.take(4000)))
    }
    override fun confirmAction(messageId: Long) { commands.trySend(Command.Resolve(messageId, true)) }
    override fun cancelAction(messageId: Long) { commands.trySend(Command.Resolve(messageId, false)) }
    override fun askAbout(source: AskSource, question: String) {
        if (question.isNotBlank()) commands.trySend(Command.Ask(question.trim().take(4000), source))
    }
    override fun stop() { commands.trySend(Command.Stop) }
    override fun newConversation() { commands.trySend(Command.New) }

    private suspend fun answer(conversationId: Long, history: List<AskMessage>, user: AskMessage, initial: AskMessage, source: AskSource?) {
        var current = initial
        var proposal: AskStoredProposal? = null
        // 所有回调和结束原因走同一个队列；关闭后迟到回调无法再改状态。
        val updates = Channel<() -> Unit>(Channel.UNLIMITED)
        val collector = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            for (apply in updates) apply()
        }
        try {
            if (!hasKey()) updates.trySend { current = current.copy(error = "还没填写大模型密钥") }
            else withTimeout(timeoutMs) {
                val context = context(source, history)
                engine().answer(system(), history, user.text, context) { update ->
                    updates.trySend {
                        proposal = if (update.mode == AskMode.Prefetch) null else update.proposal ?: proposal
                        current = current.copy(mode = update.mode, action = proposal?.action, text = update.text, sources = update.sources, progress = update.progress, error = update.error)
                        state.value = history + user + current
                    }
                }
            }
        } catch (_: TimeoutCancellationException) {
            updates.trySend { current = current.copy(error = "查询超时，请重试") }
        } catch (e: CancellationException) {
            updates.trySend { if (current.text.isEmpty()) current = current.copy(text = "已停止") }
        } catch (e: Exception) {
            val error = failureMessage(e)
            updates.trySend { current = current.copy(error = error) }
        } finally {
            updates.close()
            withContext(NonCancellable) {
                collector.join()
                // 整个仓储作用域被取消时，补处理已收到但尚未消费的更新，再原子保存。
                while (true) (updates.tryReceive().getOrNull() ?: break).invoke()
                val final = current.copy(streaming = false, progress = null)
                try {
                    val (userId, assistantId) = dao.saveExchange(user.entity(conversationId), final.entity(conversationId).copy(actionJson = proposal?.encoded))
                    state.value = history + user.copy(id = userId) + final.copy(id = assistantId)
                } catch (_: Exception) {
                    state.value = history + user + final.copy(error = "问答保存失败，请重试")
                }
            }
        }
    }

    private fun AskMessage.entity(conversationId: Long) = AskMessageEntity(
        conversationId = conversationId, role = role.name, text = text, sourcesJson = AskCitations.encodeMessage(sources, mode), at = at, error = error,
    )
    private fun AskMessageEntity.model() = AskMessage(
        id, AskRole.valueOf(role), text, AskCitations.decode(sourcesJson), at, streaming = false, error = error, action = decodeAction(actionJson), mode = AskCitations.mode(sourcesJson),
    )
}

/** 仅在仓储接入边界传递模型结果；执行与 JSON 适配由平台提供。 */
data class AskStoredProposal(val action: AskAction, val encoded: String)
data class AskSessionUpdate(val text: String, val sources: List<AskSource>, val progress: String?,
    val error: String?, val proposal: AskStoredProposal?, val mode: AskMode)
fun interface AskSessionEngine {
    suspend fun answer(system: String, history: List<AskMessage>, question: String, context: AskContext?, update: (AskSessionUpdate) -> Unit)
}
