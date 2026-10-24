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
    /** 从没设过用默认价；设过（含主动清空成空字符串）按设的来。 */
    private fun read(key: String, default: Double) = if (!prefs.contains(key)) default
        else prefs.getString(key, null)?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }

    private val currentPrice = MutableStateFlow(UsagePrice(
        read("promptPerMillion", DEFAULT.promptPerMillion!!),
        read("completionPerMillion", DEFAULT.completionPerMillion!!),
        read("asrPerHour", DEFAULT.asrPerHour!!),
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

    companion object {
        /** 2026-10 官网价：DeepSeek 白天高峰 输入 2 元、输出 8 元 / 百万 token；豆包流式识别约 0.9 元 / 小时。可在界面改。 */
        val DEFAULT = UsagePrice(2.0, 8.0, 0.9)
    }
}
