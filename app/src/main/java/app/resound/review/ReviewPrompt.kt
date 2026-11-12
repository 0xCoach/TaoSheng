package app.resound.review
import app.resound.data.ReviewInput
import app.resound.digest.*
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.datetime.toKotlinLocalDate
import app.resound.digest.core.ReviewPrompt as Core
object ReviewPrompt {
    val limits = Core.limits
    fun system(template: String, appName: String) = Core.system(template, appName)
    fun input(date: LocalDate, source: ReviewInput, zone: ZoneId = ZoneId.systemDefault(), dailyAtMinutes: Int = 22 * 60) = Core.input(date.toKotlinLocalDate(), source.snapshot(), zone.sharedZone(), dailyAtMinutes)
    fun staleOnly(source: ReviewInput, zone: ZoneId) = Core.staleOnly(source.snapshot(), zone.sharedZone())
    fun parse(content: String, source: ReviewInput, zone: ZoneId) = promptResult { Core.parse(content, source.snapshot(), zone.sharedZone()) }
    fun parse(content: String, eventIds: Set<Long>) = promptResult { Core.parse(content, eventIds) }
    fun encode(sections: List<ReviewSection>) = Core.encode(sections)
    fun decode(text: String) = Core.decode(text)
}
