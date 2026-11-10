package app.resound.diagnostics

import android.content.Context
import app.resound.data.MemoryDao
import app.resound.digest.LlmError
import app.resound.digest.LlmFailover
import app.resound.usage.UsagePurpose
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import java.net.URI
import java.time.LocalDate

internal class LlmProblemDiagnostics(
    private val context: Context,
    private val dao: MemoryDao? = null,
    private val failover: LlmFailover? = null,
    private val today: () -> LocalDate = LocalDate::now,
) {
    suspend fun dump(): String = scrubProblemReport(buildString {
        val state = context.getSharedPreferences("digest-state", 0)
        val diagnostics = LlmDiagnostics(context)
        val recent = diagnostics.state()
        val error = LlmError.entries.firstOrNull { it.name == recent.getString("errorType", null) }
            ?: LlmError.entries.firstOrNull { it.human == state.getString("lastError", null) }
        appendLine("上次整理时间=${LlmDiagnostics.stamp(state.getLong("lastRunAt", 0))}")
        appendLine("最近错误类型=${error?.name ?: "暂无"} 固定文案=${error?.human ?: "暂无"} 时间=${LlmDiagnostics.stamp(recent.getLong("errorAt", 0))}")
        // lastError 可能带地址、原始异常或其他自由文本，不直接导出。
        val inflight = state.contains("inflight")
        val count = state.getString("inflight-lines", "").orEmpty().split(',').mapNotNull(String::toLongOrNull).distinct().size
        appendLine("在途批次=$inflight 在途句数=${if (inflight) count else 0}")
        appendLine("待整理句数=${runCatching { dao?.undigestedCount() }.getOrNull() ?: "暂无"}")
        val values = state.all
        fun total(prefix: String) = values.filterKeys { it.startsWith(prefix) }.values.sumOf { (it as? Number)?.toLong()?.coerceAtLeast(0) ?: 0 }
        appendLine("格式失败计数（fail-*）=${total("fail-")} 本地保存失败计数（local-*）=${total("local-")}")
        appendLine("最近放弃批次：句数=${if (recent.contains("abandonedCount")) recent.getInt("abandonedCount", 0) else "暂无"} 时间=${LlmDiagnostics.stamp(recent.getLong("abandonedAt", 0))}")
        appendLine("当前模型配置：")
        appendLine(runCatching {
            val providers = context.getSharedPreferences("llm-providers", 0)
            val array = JSONArray(providers.getString("saved", "[]"))
            val saved = (0 until array.length()).map { array.getJSONObject(it) }
            val active = providers.getString("activeId", null)
            val primary = saved.firstOrNull { it.optString("id") == active } ?: saved.firstOrNull()
            buildString {
                appendLine("服务商=${primary?.optString("name") ?: "暂无"} 模型=${primary?.optString("model") ?: "暂无"} 服务地址=${safeServiceAddress(primary?.optString("baseUrl").orEmpty())}")
                appendLine("备用切换=${context.getSharedPreferences("digest", 0).getBoolean("failoverEnabled", true)}")
                append("备用列表（已保存的其他配置）：")
                append(saved.filter { it != primary }.joinToString("；") { "${it.optString("name")} / ${it.optString("model")}" }.ifBlank { "暂无" })
            }
        }.getOrDefault("暂无"))
        val end = today()
        appendLine("最近 3 天用量（${end.minusDays(2)} 至 $end，按用途汇总）：")
        val usage = runCatching { dao?.usageByPurpose(end.minusDays(2).toString(), end.toString())?.first() }.getOrNull()
        if (usage.isNullOrEmpty()) appendLine("暂无") else {
            usage.groupBy { row -> UsagePurpose.entries.firstOrNull { it.name == row.purpose } ?: UsagePurpose.Other }
                .forEach { (purpose, rows) ->
                    appendLine("${purpose.title} calls=${rows.sumOf { it.calls.toLong() }} prompt=${rows.sumOf { it.promptTokens }} completion=${rows.sumOf { it.completionTokens }}")
                }
        }
        appendLine("最近大模型响应摘要（最多 20 条；未到解析阶段或不适用的字段为暂无）：")
        appendLine(diagnostics.dumpResponses())
        appendLine("备用切换记录：${recent.getString("switch", null) ?: "暂无"}")
        appendLine("冷却状态（当前进程）：${runCatching { failover?.coolingDiagnostics() }.getOrNull() ?: "暂无"}")
    })
}

/** 不保留用户名、密码、查询或片段；解析失败也不回显原始输入。 */
internal fun safeServiceAddress(value: String): String = runCatching {
    val uri = URI(value)
    if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) "暂无"
    else URI(uri.scheme, null, uri.host, -1, uri.path, null, null).toASCIIString()
}.getOrDefault("暂无")
