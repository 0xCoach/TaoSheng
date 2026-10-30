package app.resound.digest

import kotlinx.coroutines.flow.StateFlow

/*
 * 卡 076：多个大模型服务商——内置常见服务商模板（点一下预填地址、模型、余额查询），用户只填密钥；
 * 可保存多个、随时切换。界面（Claude）只依赖这里；底层（Codex）实现。改签名需双方确认。
 */

/** 余额查询：GET [url]，Authorization: Bearer 密钥；余额在返回 JSON 的 [path]（点分，数组用序号）。null 表示该服务商没有可用 API Key 查询的余额接口 */
data class BalanceTemplate(val url: String, val path: String)

/** 内置服务商模板（来自 assets/llm-providers.json，卡 076 调研结果） */
data class ProviderTemplate(
    val id: String,
    val name: String,
    /** OpenAI 兼容接口根地址 */
    val baseUrl: String,
    /** 推荐模型（第一个为默认，适合整理：便宜、中文好、支持长上下文） */
    val models: List<String>,
    val balance: BalanceTemplate?,
    /** 去哪里申请密钥（网页） */
    val keyUrl: String?,
    /** 一句话说明，如“需实名认证”“按量计费，新用户有赠金” */
    val note: String? = null,
)

/** 用户保存的一个服务商；密钥只存在系统安全区，这里只告诉有没有 */
data class SavedProvider(
    val id: String,
    /** 从哪个模板建的；完全自填为 null */
    val templateId: String?,
    val name: String,
    val baseUrl: String,
    val model: String,
    val hasKey: Boolean,
    /** 余额查询；空串表示不查 */
    val balanceUrl: String = "",
    val balancePath: String = "",
)

interface LlmProviders {
    val templates: List<ProviderTemplate>
    val saved: StateFlow<List<SavedProvider>>
    /** 当前用于整理/问问/回顾的服务商 id（技能可另选） */
    val activeId: StateFlow<String>

    /** 新建（[id] 为 null）或修改。[key] 为 null 表示不改密钥。返回保存后的 id。首次保存的自动成为当前服务商 */
    suspend fun save(id: String?, templateId: String?, name: String, baseUrl: String, model: String, key: String?,
                     balanceUrl: String, balancePath: String): String
    fun activate(id: String)
    /** 删除；不能删当前正在用的（界面先切换再删）。引用它的技能回到“跟随整理模型” */
    fun delete(id: String)

    /** 不保存，试连：发一句话看能否回复；[key] 为 null 用已存的 */
    suspend fun test(id: String?, baseUrl: String, model: String, key: String?): Result<String>
    /** 拉取该服务商的模型列表（/models），失败返回空 */
    suspend fun models(id: String?, baseUrl: String, key: String?): List<String>
}
