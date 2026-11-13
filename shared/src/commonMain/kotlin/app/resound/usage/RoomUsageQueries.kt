package app.resound.usage

import app.resound.data.MemoryDao
import app.resound.data.TranscribeDao
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate

/** 用量的两个观察查询；价格的原子保存仍由平台仓储负责。 */
class RoomUsageQueries(private val memory: MemoryDao, private val transcribe: TranscribeDao) {
    fun llm(from: LocalDate, to: LocalDate) = memory.usageByPurpose(from.toString(), to.toString()).map { rows ->
        rows.map { LlmUsageRow(LocalDate.parse(it.day), UsagePurpose.valueOf(it.purpose), it.promptTokens, it.completionTokens, it.calls) }
    }

    fun asr(from: LocalDate, to: LocalDate) = transcribe.usageBetween(from.toString(), to.toString()).map { rows ->
        rows.map { AsrUsageRow(LocalDate.parse(it.day), it.sentMillis) }
    }

}
