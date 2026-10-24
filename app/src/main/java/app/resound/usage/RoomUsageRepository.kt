package app.resound.usage

import android.content.SharedPreferences
import app.resound.data.MemoryDao
import app.resound.data.TranscribeDao
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import java.time.LocalDate

class RoomUsageRepository(
    private val memory: MemoryDao,
    private val transcribe: TranscribeDao,
    private val prefs: SharedPreferences,
) : UsageRepository {
    private fun read(key: String) = prefs.getString(key, null)?.toDoubleOrNull()
        ?.takeIf { it.isFinite() && it >= 0 }

    private val currentPrice = MutableStateFlow(UsagePrice(
        read("promptPerMillion"), read("completionPerMillion"), read("asrPerHour"),
    ))
    override val price = currentPrice.asStateFlow()

    override fun llm(from: LocalDate, to: LocalDate) = memory.usageByPurpose(from.toString(), to.toString()).map { rows ->
        rows.map { LlmUsageRow(LocalDate.parse(it.day), UsagePurpose.valueOf(it.purpose), it.promptTokens, it.completionTokens, it.calls) }
    }

    override fun asr(from: LocalDate, to: LocalDate) = transcribe.usageBetween(from.toString(), to.toString()).map { rows ->
        rows.map { AsrUsageRow(LocalDate.parse(it.day), it.sentMillis) }
    }

    @Synchronized
    override fun setPrice(price: UsagePrice) {
        require(listOf(price.promptPerMillion, price.completionPerMillion, price.asrPerHour)
            .all { it == null || it.isFinite() && it >= 0 }) { "单价必须是大于等于 0 的有限数值" }
        // 空字符串表示主动清空，备份恢复时也能覆盖目的端已有单价。
        check(prefs.edit()
            .putString("promptPerMillion", price.promptPerMillion?.toString().orEmpty())
            .putString("completionPerMillion", price.completionPerMillion?.toString().orEmpty())
            .putString("asrPerHour", price.asrPerHour?.toString().orEmpty())
            .commit()) { "单价保存失败" }
        currentPrice.value = price
    }
}
