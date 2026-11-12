package app.resound.ask
import android.content.SharedPreferences
import java.time.ZoneId
import app.resound.ask.core.AskSuggestionsCore
import app.resound.ask.core.AskSuggestionStore
class AskSuggestions(
    prefs: SharedPreferences,
    hasKey: () -> Boolean,
    recent: suspend () -> String,
    generate: suspend (String) -> List<String>,
    now: () -> Long = System::currentTimeMillis,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    private val core = AskSuggestionsCore(object : AskSuggestionStore {
        override fun day() = prefs.getString("day", null)
        override fun count() = prefs.getInt("count", 0)
        override fun at() = prefs.getLong("at", 0)
        override fun questions() = prefs.getString("questions", "[]") ?: "[]"
        override fun reserve(day: String, count: Int, at: Long) = prefs.edit().putString("day", day).putInt("count", count).putLong("at", at).commit()
        override fun save(questions: String) = prefs.edit().putString("questions", questions).commit()
    }, hasKey, recent, generate, app.resound.platform.NowMillis(now), app.resound.platform.ZoneProvider { zone.id })
    val questions = core.questions
    suspend fun refresh() = core.refresh()
    companion object { val EXAMPLES = AskSuggestionsCore.EXAMPLES }
}
