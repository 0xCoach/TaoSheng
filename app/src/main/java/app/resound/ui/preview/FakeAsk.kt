package app.resound.ui.preview

import app.resound.ask.AskMessage
import app.resound.ask.AskRepository
import app.resound.ask.AskRole
import app.resound.ask.AskSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** 问问后端接上前，界面开发用的假数据：模拟“查资料 → 逐字输出 → 附出处” */
class FakeAskRepository : AskRepository {
    override val currentConversation = MutableStateFlow<Long?>(null)
    override fun history(query: String, limit: Int) = MutableStateFlow(emptyList<app.resound.ask.AskConversation>())
    override fun openConversation(id: Long) {}
    override fun renameConversation(id: Long, title: String) {}
    override fun deleteConversation(id: Long) {}
    private val scope = CoroutineScope(Dispatchers.Default)
    private val list = MutableStateFlow<List<AskMessage>>(emptyList())
    override val messages: Flow<List<AskMessage>> = list
    override val suggestions: StateFlow<List<String>> = MutableStateFlow(listOf("今天我都做了什么", "客户要什么会议室", "我有哪些产品想法", "明天要做什么"))
    private var job: Job? = null
    private var nextId = 1L

    override fun ask(question: String) {
        val now = System.currentTimeMillis()
        val q = AskMessage(nextId++, AskRole.User, question, emptyList(), now, streaming = false)
        val aId = nextId++
        list.value = list.value + q + AskMessage(aId, AskRole.Assistant, "", emptyList(), now, streaming = true, progress = "正在理解问题…")
        job = scope.launch {
            delay(600); update(aId) { it.copy(progress = "正在翻 10 月 23 日的原话…") }
            delay(900); update(aId) { it.copy(progress = "正在找关于“会议室”的事…") }
            delay(700)
            val answer = "客户是星河科技的行政部，需要几间会议室，主要用来频繁开线上会议[^1]。你当时答应帮他们推荐合适的会议室产品[^2]，这条待办目前还在“待确认”里。"
            val sources = listOf(
                AskSource("line", 66, now - 3_600_000, "10月23日 16:43 我说", "星河科技，行政部，主要是他们开会用。"),
                AskSource("item", 21, now - 3_600_000, "待办 · 为星河科技行政部推荐会议室产品", "为星河科技行政部推荐合适的会议室产品"),
            )
            update(aId) { it.copy(progress = null) }
            for (i in 1..answer.length step 2) {
                delay(28)
                update(aId) { it.copy(text = answer.take(i)) }
            }
            update(aId) { it.copy(text = answer, sources = sources, streaming = false) }
        }
    }

    override fun stop() {
        job?.cancel()
        list.value = list.value.map { if (it.streaming) it.copy(streaming = false, progress = null) else it }
    }

    override fun newConversation() {
        stop(); list.value = emptyList()
    }

    private fun update(id: Long, f: (AskMessage) -> AskMessage) {
        list.value = list.value.map { if (it.id == id) f(it) else it }
    }
}
