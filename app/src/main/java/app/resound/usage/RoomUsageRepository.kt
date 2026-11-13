package app.resound.usage

import android.content.SharedPreferences
import app.resound.data.MemoryDao
import app.resound.data.TranscribeDao
import kotlinx.datetime.LocalDate

class RoomUsageRepository(
    private val memory: MemoryDao,
    private val transcribe: TranscribeDao,
    private val prefs: SharedPreferences,
) : UsageRepository {
    private val prices = UsagePriceStore(prefs::contains, { prefs.getString(it, null) }) { values ->
        val edit = prefs.edit()
        values.forEach { (key, value) -> edit.putString(key, value) }
        edit.commit()
    }
    override val price = prices.state

    private val queries = RoomUsageQueries(memory, transcribe)
    override fun llm(from: LocalDate, to: LocalDate) = queries.llm(from, to)
    override fun asr(from: LocalDate, to: LocalDate) = queries.asr(from, to)

    @Synchronized
    override fun setPrice(price: UsagePrice) {
        prices.set(price)
    }

    companion object {
        /** 2026-10 官网价：DeepSeek 白天高峰 输入 2 元、输出 8 元 / 百万 token；豆包流式识别约 0.9 元 / 小时。可在界面改。 */
        val DEFAULT: UsagePrice = UsageRules.DEFAULT
    }
}
