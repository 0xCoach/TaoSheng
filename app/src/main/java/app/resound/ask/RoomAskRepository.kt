package app.resound.ask

import app.resound.data.AskDao
import app.resound.digest.LlmException
import kotlinx.coroutines.CoroutineScope

/** 安卓模型、建议、旧动作 JSON 和修改执行器的装配。 */
class RoomAskRepository private constructor(private val store: AskStoreRepository) : AskRepository by store {
    constructor(
    dao: AskDao, scope: CoroutineScope, hasKey: () -> Boolean,
    engine: () -> AskEngine, system: () -> String, recommended: AskSuggestions,
    now: () -> Long, timeoutMs: Long = 180_000,
    readSelected: () -> Long? = { null }, saveSelected: (Long) -> Unit = {},
    changes: AskChanges? = null, contextReader: AskContextReader? = null,
) : this(AskStoreRepository(dao, scope, hasKey, {
    val delegate = engine()
    AskSessionEngine { prompt, history, question, context, update ->
        delegate.answer(prompt, history, question, context) {
            update(AskSessionUpdate(it.text, it.sources, it.progress, it.error,
                it.proposal?.let { proposal -> AskStoredProposal(proposal.action, proposal.encode()) }, it.mode))
        }
    }
}, system, recommended.questions, recommended::refresh, now, timeoutMs, readSelected, saveSelected,
    changes?.let { it::resolve }, { source, history ->
        if (source != null) contextReader?.read(source) else contextReader?.followUp(history)
    }, { ProposedChange.decode(it)?.action }, { if (it is LlmException) it.detail else "查询失败，请重试" }))
    override val messages get() = store.messages
}
