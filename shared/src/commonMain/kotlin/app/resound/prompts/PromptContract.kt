package app.resound.prompts

import kotlinx.coroutines.flow.StateFlow

/** 整理用的指令模板：可以改、可以恢复默认。界面由 Claude 做。 */
data class PromptInfo(
    /** digest / review / ask */
    val id: String,
    /** 给人看的名字，如“整理成事件” */
    val title: String,
    /** 一句话说明它管什么 */
    val description: String,
    /** 改过（和内置默认不同） */
    val customized: Boolean,
    /** 模板里必须保留的占位符，如 {{CALENDAR}}；保存时缺了会被拒绝 */
    val requiredPlaceholders: List<String>,
)

interface PromptTemplates {
    val list: StateFlow<List<PromptInfo>>

    /** 当前生效的文本（改过的或默认的） */
    fun text(id: String): String

    fun defaultText(id: String): String

    /** 保存修改；缺少必需占位符或为空时返回失败（给人看的原因） */
    fun save(id: String, text: String): Result<Unit>

    fun reset(id: String)
}
