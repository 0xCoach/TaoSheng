package app.resound.ask

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/*
 * “问问”（对话式记忆）与界面之间的约定。界面（Claude）只依赖这里；底层（Codex）实现。改签名需双方确认。
 */

enum class AskRole { User, Assistant }

/** 回答引用的出处，点开能回到原话或那件事 */
data class AskSource(
    /** line = 一句原话；event = 一件事；item = 一条提取项 */
    val kind: String,
    val id: Long,
    /** 出处时间（毫秒） */
    val at: Long,
    /** 简短标题：事件标题或“10月23日 16:42 我说” */
    val title: String,
    /** 引用的原文片段（原话原样，不改写） */
    val quote: String,
)

data class AskMessage(
    val id: Long,
    val role: AskRole,
    /** 回答正文；流式输出时逐步变长 */
    val text: String,
    val sources: List<AskSource>,
    val at: Long,
    /** 还在输出中 */
    val streaming: Boolean,
    /** 正在做什么，给界面显示，如“正在翻 10 月 23 日的原话…”；没有为 null */
    val progress: String? = null,
    /** 出错时给人看的原因 */
    val error: String? = null,
    /** 回答里要改数据时附的确认卡片；没有为 null */
    val action: AskAction? = null,
)

enum class AskActionState { Pending, Done, Cancelled, Failed }

/** 问问想改数据（完成待办、新建备忘、改截止时间、标重要…），先给人确认 */
data class AskAction(
    /** 一句话说清要做什么，如“把『给张三回电话』标为已完成” */
    val summary: String,
    /** 改动前后的对比，可空，如“截止：10月28日 → 10月30日” */
    val detail: String?,
    val state: AskActionState,
)

data class AskConversation(val id: Long, val title: String, val preview: String, val updatedAt: Long)

interface AskRepository {
    val currentConversation: StateFlow<Long?>
    fun history(query: String, limit: Int): Flow<List<AskConversation>>
    fun openConversation(id: Long)
    fun renameConversation(id: Long, title: String)
    fun deleteConversation(id: Long)

    /** 当前会话的全部消息（按时间正序） */
    val messages: Flow<List<AskMessage>>

    /** 推荐问题（胶囊），根据最近的事件生成，最多 4 个 */
    val suggestions: StateFlow<List<String>>

    /** 提一个问题；回答以流式更新到 messages */
    fun ask(question: String)

    /** 停止正在输出的回答 */
    fun stop()

    /** 开一个新会话（旧会话保存在库里） */
    fun newConversation()

    /** 确认 / 取消某条消息上的改动卡片 */
    fun confirmAction(messageId: Long) {}

    fun cancelAction(messageId: Long) {}

    /** “就这个问问”：带着一件事或一条条目开新会话提问，后台把它的内容和原话一起交给模型 */
    fun askAbout(source: AskSource, question: String) { newConversation(); ask(question) }
}
