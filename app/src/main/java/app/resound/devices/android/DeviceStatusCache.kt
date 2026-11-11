package app.resound.devices.android

import app.resound.devices.driver.*
import org.json.JSONArray
import org.json.JSONObject

/** 平台持久化状态快照；时间仍是原始读取时间，恢复时不伪造新读数。 */
internal object DeviceStatusCache {
    fun encode(status: DeviceStatusModel): String {
        val batteries = JSONArray()
        status.batteries.forEach { part -> batteries.put(JSONObject().put("part",part.part)
            .put("zh",part.name?.zh).put("en",part.name?.en).put("percent",part.percent)
            .put("charging",part.charging).put("at",part.readAtMillis)) }
        return JSONObject().put("batteries",batteries).put("recording",status.recording?.name)
            .put("storage",status.storage?.let { JSONObject().put("total",it.totalBytes).put("free",it.freeBytes).put("unconfirmed",it.unconfirmedBytes) })
            .toString()
    }
    fun decode(raw: String): DeviceStatusModel? = runCatching {
        val json=JSONObject(raw); val values=json.getJSONArray("batteries")
        require(values.length() <= 15)
        fun JSONObject.longOrNull(key:String) = if (!has(key) || isNull(key)) null else getLong(key)
        val batteries=(0 until values.length()).map { index ->
            val row=values.getJSONObject(index)
            val percent=row.longOrNull("percent")?.toInt()?.also { require(it in 0..100) }
            val name=row.optString("zh").takeIf { it.isNotEmpty() }?.let { LocalizedText(it,row.optString("en",it)) }
            BatteryPart(row.getInt("part").also { require(it in 1..15) },name,percent,
                if (!row.has("charging") || row.isNull("charging")) null else row.getBoolean("charging"),row.longOrNull("at"))
        }
        val storage=json.optJSONObject("storage")?.let { StorageInfo(it.longOrNull("total"),it.longOrNull("free"),it.longOrNull("unconfirmed")) }
        val recording=json.optString("recording").takeIf { it.isNotEmpty() }?.let(RecordingState::valueOf)
        DeviceStatusModel(batteries,storage,recording)
    }.getOrNull()
}
