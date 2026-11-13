package app.resound.digest

import app.resound.data.MemoryDao
import app.resound.platform.NowMillis
import app.resound.platform.ZoneProvider
import app.resound.transcribe.VoiceInputMetadata
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import org.json.JSONArray
import org.json.JSONException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 安卓接入执行器、偏好、旧 JSON 和系统日期表示；仓储规则在共用层。 */
class RoomMemoryRepository(
    dao: MemoryDao, runner: DigestRunner, runNowAction: () -> Unit, scope: CoroutineScope,
    reminderSettings: AndroidAppointmentSettings? = null, eventChanged: () -> Unit = {},
    zoneProvider: ZoneProvider, now: NowMillis, io: CoroutineDispatcher,
) : MemoryRepository by MemoryStoreRepository(dao, runner.running, runner.last, runNowAction, scope,
    reminderSettings?.state, reminderSettings?.let { it::set }, eventChanged, zoneProvider, now, io,
    { LocalDate.now().toString() }, AndroidMemoryLegacyCodec, { at ->
        val date = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
        MemoryDueDate(date.toLocalDate().atStartOfDay(date.zone).toInstant().toEpochMilli(),
            date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")),
            date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")))
    }, VoiceInputMetadata::read)

private object AndroidMemoryLegacyCodec : MemoryLegacyCodec {
    override fun strings(text: String): List<String> = JSONArray(text).let { a -> (0 until a.length()).map { a.getString(it) } }
    override fun encodeStrings(values: List<String>): String = JSONArray(values).toString()
    override fun canonicalArray(text: String): String = try { JSONArray(text).toString() } catch (_: JSONException) { "[]" }
    override fun appendLong(text: String, value: Long): String = JSONArray(text).put(value).toString()
}
