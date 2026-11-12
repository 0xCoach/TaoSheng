@file:OptIn(kotlin.time.ExperimentalTime::class)
package app.resound.ask.core
import kotlinx.datetime.*
import kotlin.time.Instant

class AskDateException(cause: IllegalArgumentException) : IllegalArgumentException(cause)

object AskTime {
    fun timestamp(value: String, end: Boolean, zone: TimeZone): Long {
        require(value.length == 10 || value.length == 16)
        return try {
        val local = if (value.length == 10) LocalDate.parse(value).plus(if (end) 1 else 0, DateTimeUnit.DAY).atTime(0, 0)
            else {
                require(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}").matches(value))
                val parsed = LocalDateTime.parse(value.replace(' ', 'T'))
                // 先做本地分钟加法，再转换时区，与旧日期 API 的夏令时语义一致。
                Instant.fromEpochMilliseconds(parsed.toInstant(TimeZone.UTC).toEpochMilliseconds() + if (end) 60000 else 0)
                    .toLocalDateTime(TimeZone.UTC)
            }
        local.toInstant(zone).toEpochMilliseconds()
        } catch (e: IllegalArgumentException) { throw AskDateException(e) }
    }
    fun format(at: Long, zone: TimeZone): String {
        val local = Instant.fromEpochMilliseconds(at).toLocalDateTime(zone)
        return "${local.date} ${local.hour.toString().padStart(2, '0')}:${local.minute.toString().padStart(2, '0')}"
    }
}
