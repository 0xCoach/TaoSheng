package app.resound.digest

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** 官方 AI 服务商条目的固定 id：与用户自建 BYOK 条目并存，真实 Base/模型/密钥只存服务端。 */
const val OFFICIAL_PROVIDER_ID = "official"

/**
 * 一次模型调用的计费任务类别（服务端按类别施加限额；auto_digest 仅 Pro 且另有周期/每日点数上限）。
 * 由发起方放入协程上下文；failover 只在缺省时按 feature 名补一个，安静自动整理显式覆盖为 auto_digest。
 */
class LlmTaskKind(val kind: String) : AbstractCoroutineContextElement(LlmTaskKind) {
    companion object Key : CoroutineContext.Key<LlmTaskKind>
}

/** 功能中文名 → 服务端任务类别；安静自动整理、未列出的功能分别记 auto_digest / generic。 */
fun officialTaskKind(feature: String): String = when {
    feature.contains("整理") -> "digest"
    feature.contains("分析") || feature.contains("项目") -> "analysis"
    feature.contains("问答") || feature.endsWith("问") -> "ask"
    feature.contains("待办") -> "manual_todo"
    feature.contains("复盘") || feature.contains("回顾") -> "review"
    feature.contains("日记") -> "diary"
    feature.contains("话题") || feature.contains("专题") -> "topic"
    feature.contains("技能") -> "skill"
    feature.contains("灵感") -> "idea"
    feature.contains("会议") || feature.contains("纪要") -> "meeting"
    else -> "generic"
}
