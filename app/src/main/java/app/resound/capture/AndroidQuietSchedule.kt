package app.resound.capture

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.ZoneId

class AndroidQuietSchedule(
    private val prefs: SharedPreferences,
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val arm: (Long?) -> Unit = {},
    private val changed: () -> Unit = {},
) : QuietSchedule {
    init {
        if (!prefs.contains("quietRules")) {
            check(prefs.edit().putString("quietRules", encode(listOf(QuietRule(QuietSchedule.SLEEP_ID, "睡眠", 23 * 60, 7 * 60, enabled = false)))).commit())
        }
        // 只在升级时迁移旧的手动暂停；之后 pausedUntil 是两种暂停的有效结束时间。
        if (!prefs.contains("manualPausedUntil")) check(prefs.edit().putLong("manualPausedUntil", prefs.getLong("pausedUntil", 0)).commit())
    }
    private val currentRules = MutableStateFlow(decode(prefs.getString("quietRules", "[]")!!))
    override val rules = currentRules.asStateFlow()
    private val currentActive = MutableStateFlow<QuietWindow?>(null)
    override val active = currentActive.asStateFlow()
    init { refresh() }

    @Synchronized override fun save(rule: QuietRule) {
        require(rule.id.isNotBlank() && rule.name.isNotBlank())
        require(rule.startMinute in 0..1439 && rule.endMinute in 0..1439)
        val list = rules.value.toMutableList()
        val index = list.indexOfFirst { it.id == rule.id }
        if (index >= 0) list[index] = rule else list += rule
        persist(list)
    }
    @Synchronized override fun delete(id: String) = persist(rules.value.filterNot { it.id == id })
    private fun persist(list: List<QuietRule>) {
        check(prefs.edit().putString("quietRules", encode(list)).commit())
        currentRules.value = list
        refresh(); changed()
    }
    @Synchronized fun refresh(): QuietWindow? {
        val at = now()
        val skip = prefs.getLong("quietSkipUntil", 0)
        currentActive.value = QuietRules.windowAt(rules.value, at, zone()).takeUnless { at < skip }
        val boundary = QuietRules.nextChange(rules.value, at, zone())
        arm(listOfNotNull(boundary, skip.takeIf { it > at && it != Long.MAX_VALUE }).minOrNull())
        return active.value
    }
    /** 只由用户恢复触发；定时器/闹钟只能 refresh，不能跳过规则。 */
    @Synchronized fun resume() {
        val window = QuietRules.windowAt(rules.value, now(), zone())
        if (window != null) check(prefs.edit().putLong("quietSkipUntil", window.endsAt).commit())
        refresh(); changed()
    }
    companion object {
        private fun encode(rules: List<QuietRule>) = JSONArray().apply {
            rules.forEach { rule -> put(JSONObject().put("id", rule.id).put("name", rule.name)
                .put("startMinute", rule.startMinute).put("endMinute", rule.endMinute).put("enabled", rule.enabled)
                .put("days", JSONArray(rule.days.sortedBy { it.value }.map { it.value }))) }
        }.toString()
        private fun decode(json: String): List<QuietRule> = runCatching {
            val rows = JSONArray(json)
            (0 until rows.length()).map { i ->
                val row = rows.getJSONObject(i); val days = row.getJSONArray("days")
                QuietRule(row.getString("id"), row.getString("name"), row.getInt("startMinute"), row.getInt("endMinute"),
                    (0 until days.length()).map { DayOfWeek.of(days.getInt(it)) }.toSet(), row.getBoolean("enabled"))
            }.filter { it.startMinute in 0..1439 && it.endMinute in 0..1439 }
        }.getOrDefault(emptyList())

        fun arm(context: Context, at: Long?) {
            val manager = context.getSystemService(AlarmManager::class.java)
            val intent = PendingIntent.getBroadcast(context, 56, Intent(context, CaptureResumeReceiver::class.java)
                .setAction(CaptureService.RECHECK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            manager.cancel(intent)
            if (at == null) return
            try {
                if (Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms()) manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
                else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
            } catch (_: SecurityException) { manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent) }
        }
    }
}
