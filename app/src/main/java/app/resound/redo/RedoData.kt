package app.resound.redo

import androidx.room.*
import app.resound.data.*
import org.json.JSONArray
import org.json.JSONObject

@Entity(tableName = "redo_snapshot")
internal data class RedoSnapshotRow(@PrimaryKey val slot: Int = 1, val jobId: String, val at: Long, val payload: String, val applied: Boolean = false)

@Dao
internal interface RedoDao {
    /** 不读快照正文：整天重做的快照可能超过 2MB，一次读出会让数据库游标装不下而闪退 */
    @Query("SELECT slot, jobId, at, '' AS payload, applied FROM redo_snapshot WHERE slot = 1") suspend fun snapshotMeta(): RedoSnapshotRow?
    @Query("SELECT length(payload) FROM redo_snapshot WHERE slot = 1") suspend fun payloadLength(): Int?
    @Query("SELECT substr(payload, :from, :count) FROM redo_snapshot WHERE slot = 1") suspend fun payloadPart(from: Int, count: Int): String?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun snapshot(row: RedoSnapshotRow)
    @Query("DELETE FROM redo_snapshot") suspend fun clear()
    @Query("SELECT * FROM memory_events WHERE deleted = 0 AND startAt < :end AND endAt > :start ORDER BY startAt, id")
    suspend fun events(start: Long, end: Long): List<MemoryEventEntity>
    @Query("SELECT * FROM segments WHERE startAt < :end AND endAt > :start ORDER BY startAt, id")
    suspend fun segments(start: Long, end: Long): List<SegmentEntity>
    @Query("SELECT * FROM memory_items WHERE eventId IN (:ids)") suspend fun items(ids: List<Long>): List<MemoryItemEntity>
    @Query("SELECT * FROM transcript_lines WHERE eventId IN (:ids) ORDER BY startAt, id") suspend fun eventLines(ids: List<Long>): List<TranscriptLineEntity>
    @Query("SELECT * FROM skill_cards WHERE deletedAt IS NULL") suspend fun cards(): List<app.resound.skills.SkillCardRow>
    @Update suspend fun item(row: MemoryItemEntity)
}

internal data class RedoRange(val start: Long, val end: Long) {
    fun overlaps(start: Long, end: Long) = this.start < end && this.end > start
}
/** 分段读出快照正文，每段 20 万字（中文按 UTF-8 约 600KB），不受游标 2MB 上限影响 */
internal suspend fun RedoDao.fullSnapshot(): RedoSnapshotRow? {
    val meta = snapshotMeta() ?: return null
    val length = payloadLength() ?: 0
    val text = StringBuilder(length)
    var from = 1
    while (from <= length) { text.append(payloadPart(from, SNAPSHOT_PART) ?: ""); from += SNAPSHOT_PART }
    return meta.copy(payload = text.toString())
}
private const val SNAPSHOT_PART = 200_000
internal fun mergedRanges(ranges: List<RedoRange>): List<RedoRange> {
    val out = mutableListOf<RedoRange>()
    for (r in ranges.sortedBy { it.start }) {
        val last = out.lastOrNull()
        if (last != null && r.start <= last.end) out[out.lastIndex] = last.copy(end = maxOf(last.end, r.end)) else out += r
    }
    return out
}
internal fun overlap(a: Long, b: Long, c: Long, d: Long) = (minOf(b, d) - maxOf(a, c)).coerceAtLeast(0)
internal fun List<Long>.sqlIds() = if (isEmpty()) "NULL" else distinct().joinToString(",")

/** 只快照本任务涉及的行；JSON 保留列类型和 NULL，不复制整个数据库，也不回滚计费用量。 */
internal class RedoSnapshot(private val db: CaptureDatabase) {
    private val sql get() = db.openHelper.writableDatabase
    fun rows(table: String, where: String): JSONArray = JSONArray().apply {
        sql.query("SELECT * FROM $table WHERE $where ORDER BY id").use { c ->
            while (c.moveToNext()) put(JSONObject().apply {
                c.columnNames.forEachIndexed { i, name -> put(name, when (c.getType(i)) {
                    android.database.Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                    android.database.Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                    android.database.Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                    else -> c.getString(i)
                }) }
            })
        }
    }
    fun capture(selectors: Map<String, String>) = JSONObject().apply { selectors.forEach { (t, w) -> put(t, rows(t, w)) } }
    fun restore(data: JSONObject) {
        // UPDATE segments 避免 REPLACE 级联删除原话；其余表由调用者先按 ID 清理。
        listOf("segments", "memory_events", "transcript_lines", "memory_items", "skill_cards").filter { data.has(it) }.forEach { table ->
            val rows = data.getJSONArray(table)
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                val keys = row.keys().asSequence().filterNot { table == "segments" && it == "sentMillis" }.toList()
                val values = keys.map { if (row.isNull(it)) null else row.get(it) }.toTypedArray()
                if (table == "segments") {
                    sql.execSQL("UPDATE segments SET ${keys.joinToString { "$it = ?" }} WHERE id = ${row.getLong("id")}", values)
                } else sql.execSQL("INSERT INTO $table (${keys.joinToString()}) VALUES (${keys.joinToString { "?" }})", values)
            }
        }
    }
    fun delete(data: JSONObject) {
        // 子表先删，避免 FK 级联影响保留项。
        listOf("memory_items", "transcript_lines", "skill_cards", "memory_events").forEach { table ->
            val rows = data.optJSONArray(table) ?: return@forEach
            val ids = (0 until rows.length()).map { rows.getJSONObject(it).getLong("id") }
            sql.execSQL("DELETE FROM $table WHERE id IN (${ids.sqlIds()})")
        }
    }
    fun unchanged(data: JSONObject, voiceprint: Boolean = false): Boolean = data.keys().asSequence().all { table ->
        val old = data.getJSONArray(table)
        val ids = (0 until old.length()).map { old.getJSONObject(it).getLong("id") }
        fun comparable(value: JSONArray): String {
            val copy = JSONArray(value.toString())
            for (i in 0 until copy.length()) {
                val row = copy.getJSONObject(i)
                if (table == "segments") row.remove("sentMillis")
                if (voiceprint && table == "segments") row.remove("voiceprintProcessedAt")
                if (voiceprint && table == "transcript_lines") listOf("speakerName", "speakerScore", "speakerPersonId").forEach(row::remove)
            }
            return copy.toString()
        }
        comparable(rows(table, "id IN (${ids.sqlIds()})")) == comparable(old)
    }
}
