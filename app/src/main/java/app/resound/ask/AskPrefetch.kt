package app.resound.ask
import org.json.JSONObject
import java.time.LocalDate
internal class AskPrefetch(
    search: suspend (String, JSONObject, MutableList<AskSource>) -> JSONObject,
    today: () -> LocalDate = LocalDate::now,
) {
    private val core = app.resound.ask.core.AskPrefetchCore({ name, args, sources -> search(name, JSONObject(args), sources).toString() },
        { kotlinx.datetime.LocalDate.parse(today().toString()) })
    suspend fun read(question: String, context: AskContext?) = core.read(question, context)
    companion object {
        internal fun timeRange(question: String, today: LocalDate): JSONObject = JSONObject(
            app.resound.ask.core.AskPrefetchCore.timeRange(question, kotlinx.datetime.LocalDate.parse(today.toString())))
    }
}
