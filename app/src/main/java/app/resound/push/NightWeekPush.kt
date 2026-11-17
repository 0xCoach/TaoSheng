package app.resound.push
import app.resound.AppGraph
import app.resound.night.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import org.json.JSONObject

/** 走每日文档的同一通道状态、路径及失败重试；不带任何音频或梦话全文。 */
internal suspend fun currentNightWeeks(): List<WeeklyReport> {
    if(!AppGraph.isNightReady || !AppGraph.night.settings.value.includeInPush)return emptyList()
    val owner=AppGraph.night as? AndroidNight ?: return emptyList()
    owner.insights.repository.refreshWeekPrivacy()
    return AppGraph.nightInsights.weeklyReports().first()
}
internal fun nightWeekPayload(report: WeeklyReport)=JSONObject(kotlinx.serialization.json.Json.encodeToString(report)).put("type","night_week")
