package app.resound.digest

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * 调用方会自己把“太慢 / 输出被截断”的一批切小重试（整理、专题、导入纪要）时放进协程上下文。
 * 有它：这两类错误先交给调用方切小，不冷却也不切备用模型；没有它（回顾、技能、问问等）：照常切到备用模型。
 */
object LlmSplitsOnSlow : AbstractCoroutineContextElement(Key) {
    object Key : CoroutineContext.Key<LlmSplitsOnSlow>
}
