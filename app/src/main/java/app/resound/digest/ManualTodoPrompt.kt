package app.resound.digest
import java.time.*
import kotlinx.datetime.toKotlinLocalDate
import kotlinx.datetime.toKotlinLocalTime
import app.resound.digest.core.ManualTodoPrompt as Core
internal object ManualTodoPrompt {
    fun system(today: LocalDate, now: LocalTime = LocalTime.of(12, 0)) = Core.system(today.toKotlinLocalDate(), now.toKotlinLocalTime())
    fun parse(raw: String, zone: ZoneId) = Core.parse(raw, zone.sharedZone())
}
