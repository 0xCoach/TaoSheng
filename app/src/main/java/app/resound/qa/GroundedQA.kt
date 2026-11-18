package app.resound.qa

import app.resound.platform.NowMillis
import app.resound.qa.QaRules
import app.resound.decision.DecisionDao
import app.resound.digest.LlmPreset
import app.resound.digest.LlmReply
import app.resound.inbox.IdeaDao
import app.resound.inbox.IdeaRules
import app.resound.search.RecordSearch
import app.resound.search.SearchHit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** 有出处问答的一条来源：编号即回答里的 [#n]。 */
data class QaSource(val no: Int, val kind: String, val id: Long, val at: Long, val title: String, val quote: String)

data class QaAnswer(
    val text: String,
    val sources: List<QaSource>,
    /** true = 纯记录作答；false = 记录不足补了通用知识（界面要标注） */
    val grounded: Boolean,
    /** 没配模型时为 true：此时 text 是"等待配置服务"说明，sources 是检索命中 */
    val needsService: Boolean = false,
    /** 证据为空的诚实态：记录里确实没找到 */
    val noEvidence: Boolean = false,
    /** 模型引用了不存在的出处编号、已被守卫清掉的个数（>0 界面提示） */
    val invalidCitations: Int = 0,
    /** 证据强度 0-3（EvidenceGuard.strength）：1 单薄、2 尚可、3 强 */
    val evidenceLevel: Int = 0,
)

enum class QaMode(val label: String) { RecordsOnly("仅查我的记录"), RecordsPlus("记录+通用知识") }

/**
 * 跨项目有出处问答：先本地 FTS 取证，再把编号资料交给模型，要求引用 [#n]。
 * 无证据时明说"记录里没找到"，不编造回忆；未配模型时返回原始命中+等待配置。
 */
class GroundedQA(
    private val search: RecordSearch,
    private val decisions: DecisionDao,
    private val ideas: IdeaDao,
    private val io: CoroutineDispatcher,
    private val now: NowMillis,
    private val credentials: () -> Pair<LlmPreset, String>,
    private val canUseWithoutKey: (LlmPreset) -> Boolean,
    private val hasBackup: (LlmPreset) -> Boolean,
    private val chat: suspend (LlmPreset, String, String, String) -> LlmReply,
) {
    val mode = MutableStateFlow(QaMode.RecordsOnly)
    private val _last = MutableStateFlow<QaAnswer?>(null)
    val last = _last.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    suspend fun ask(question: String): QaAnswer = withContext(io) {
        _busy.value = true
        try {
            // 解析"上周/还没验证/否决过"这类回忆式问法，先限定维度再取证
            val dayStart = run {
                val cal = java.util.Calendar.getInstance()
                cal.set(java.util.Calendar.HOUR_OF_DAY, 0); cal.set(java.util.Calendar.MINUTE, 0)
                cal.set(java.util.Calendar.SECOND, 0); cal.set(java.util.Calendar.MILLISECOND, 0)
                cal.timeInMillis
            }
            val hints = QaRules.parse(question, now.now(), dayStart)
            // 价值加权：星标/重点事件的证据排前，编号靠前优先被引用；同加成按时间倒序。
            val hits = search.search(question).filter { QaRules.inWindow(hints, it.at) }
                .sortedWith(compareByDescending<SearchHit> { it.valueBonus }.thenByDescending { it.at }).take(12)
            val ideaHits = ideas.search(IdeaRules.likePattern(question)).first()
                .filter { QaRules.inWindow(hints, it.updatedAt) && (hints.statuses == null || it.status in hints.statuses!!) }.take(6)
            val decisionHits = decisions.search(IdeaRules.likePattern(question)).first()
                .filter { QaRules.inWindow(hints, it.decidedAt) && (hints.statuses == null || it.status in hints.statuses!!) }.take(6)
            val sources = buildList {
                hits.forEachIndexed { i, h ->
                    add(QaSource(size + 1, if (h.kind == SearchHit.Kind.Line) "line" else "event", h.id, h.at,
                        h.speaker ?: if (h.kind == SearchHit.Kind.Line) "原话" else "事件", h.text))
                }
                ideaHits.forEach { add(QaSource(size + 1, "idea", it.id, it.updatedAt, it.title, it.content.take(120))) }
                decisionHits.forEach { add(QaSource(size + 1, "decision", it.id, it.decidedAt, it.title, it.detail.take(120))) }
            }
            if (sources.isEmpty() && mode.value == QaMode.RecordsOnly) {
                return@withContext QaAnswer("记录里没找到相关内容。可以换个说法再搜，或切到「记录+通用知识」模式。", emptyList(), grounded = true, noEvidence = true)
            }
            val (preset, key) = credentials()
            if (key.isBlank() && !canUseWithoutKey(preset) && !hasBackup(preset)) {
                val body = if (sources.isEmpty()) "等待配置服务：到「设置 → 模型与整理时间」填好模型就能问。" else
                    "等待配置服务。先给你按关键词搜到的 ${sources.size} 条出处，填好模型可让 AI 综合回答。"
                return@withContext QaAnswer(body, sources, grounded = true, needsService = true, noEvidence = sources.isEmpty())
            }
            val context = sources.joinToString("\n") { "[#${it.no}] (${it.kind}#${it.id}) ${it.title}：${it.quote}" }
            val filterNote = if (hints.active) "（已按问题限定：${QaRules.describe(hints)}）\n" else ""
            val system = if (mode.value == QaMode.RecordsOnly)
                "你是用户的记忆问答助手。只用给出的资料回答，每条结论后用 [#编号] 标明出处；资料里没有就直说没找到，不许编造。"
            else
                "你是用户的记忆问答助手。优先用给出的资料回答并标 [#编号]；资料不足可补充通用知识，但要明确标注「补充：」，不许把补充内容伪装成记录。"
            val reply = chat(preset, key, system, "${filterNote}资料：\n$context\n\n问题：$question")
            // 证据守卫：清掉越界出处编号；证据强度随回答带出，界面如实标注。
            val checked = EvidenceGuard.clean(reply.content, sources.size)
            QaAnswer(checked.text, sources, grounded = mode.value == QaMode.RecordsOnly,
                noEvidence = sources.isEmpty(), invalidCitations = checked.dropped,
                evidenceLevel = EvidenceGuard.strength(sources.size,
                    sources.count { it.kind == "event" }, hits.count { it.valueBonus > 0 }))
        } finally { _busy.value = false }
    }.also { _last.value = it }

}
