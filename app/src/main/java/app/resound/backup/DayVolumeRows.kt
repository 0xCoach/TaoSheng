package app.resound.backup

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import app.resound.data.CaptureDatabase
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId

/** 分卷只保存数据，不执行备份中的 SQL；列名、表名始终由当前 App 决定。 */
internal object DayVolumeRows {
    val dated = linkedMapOf(
        "segments" to "startAt", "transcript_lines" to "startAt", "memory_events" to "startAt",
        "memory_items" to "createdAt", "skill_cards" to "at", "reviews" to "generatedAt",
        "ask_conversations" to "createdAt", "ask_messages" to "at",
        "asr_attempts" to "at", "asr_usage" to "day", "llm_usage" to "day", "skill_runs" to "at",
        "app_power" to "at", "battery" to "at", "heartbeats" to "at", "watchdog" to "at",
    )
    val global = setOf("voiceprint_people", "voiceprint_samples", "voiceprint_exclusions")
    val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    fun day(at: Long): String = Instant.ofEpochMilli(at).atZone(zone).toLocalDate().toString()
    fun canonical(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") {
            JSONObject.quote(it) + ":" + canonical(value.get(it))
        }
        is org.json.JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value)
        is Number, is Boolean -> value.toString()
        else -> error("不支持的数据类型")
    }
    fun write(source: File, directory: File): Pair<Int, Map<String, File>> {
        check(directory.mkdirs())
        val files = sortedMapOf<String, File>()
        val writers = java.util.LinkedHashMap<String, java.io.BufferedWriter>(16, 0.75f, true)
        try { SQLiteDatabase.openDatabase(source.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            (dated.keys + global).sorted().forEach { table ->
                val keys = mutableListOf<Pair<Int, String>>()
                db.rawQuery("PRAGMA table_info(`$table`)", null).use { c ->
                    while (c.moveToNext()) if (c.getInt(5) > 0) keys += c.getInt(5) to c.getString(1)
                }
                require(keys.isNotEmpty())
                val order = keys.sortedBy { it.first }.joinToString { "`${it.second}`" }
                db.rawQuery("SELECT * FROM `$table` ORDER BY $order", null).use { c ->
                    val columns = c.columnNames.sorted()
                    while (c.moveToNext()) {
                        val values = JSONObject()
                        for (column in columns) {
                            val i = c.getColumnIndexOrThrow(column)
                            val v: Any = when (c.getType(i)) {
                                Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                                Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                                Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                                Cursor.FIELD_TYPE_STRING -> c.getString(i)
                                else -> JSONObject().put("blob", android.util.Base64.encodeToString(c.getBlob(i), android.util.Base64.NO_WRAP))
                            }
                            values.put(column, v)
                        }
                        val dateColumn = dated[table]
                        val date = if (dateColumn == null) "state" else if (dateColumn == "day")
                            java.time.LocalDate.parse(values.getString(dateColumn)).toString() else day(values.getLong(dateColumn))
                        val file = files.getOrPut(date) { File(directory, "$date.jsonl") }
                        val writer = writers.getOrPut(date) { java.io.FileOutputStream(file, true).bufferedWriter() }
                        writer.write(canonical(JSONObject().put("table", table).put("values", values)) + "\n")
                        if (writers.size > 16) {
                            val oldest = writers.entries.first(); oldest.value.close(); writers.remove(oldest.key)
                        }
                    }
                }
            }
            files.getOrPut("state") { File(directory, "state.jsonl").apply { writeText("") } }
            return db.version to files
        } } finally { writers.values.forEach { it.close() } }
    }
    fun insert(db: SupportSQLiteDatabase, rows: File, state: Boolean) {
        rows.bufferedReader().useLines { lines -> lines.forEach { line ->
            val row = JSONObject(line); val table = row.getString("table")
            require(table in if (state) global else dated.keys)
            val values = row.getJSONObject("values")
            val columns = mutableListOf<String>()
            val args = mutableListOf<Any?>()
            db.query("PRAGMA table_info(`$table`)").use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1)
                    if (values.has(name)) {
                        columns += name
                        val value = values.get(name)
                        args += when (value) {
                            JSONObject.NULL -> null
                            is JSONObject -> android.util.Base64.decode(value.getString("blob"), android.util.Base64.NO_WRAP)
                            else -> value
                        }
                    } else if (c.getInt(3) != 0 && c.isNull(4)) {
                        // 老分卷没有的新必填列：Room 未声明 SQL 默认值时使用类型零值。
                        columns += name
                        args += if (c.getString(2).contains("INT", true) || c.getString(2).contains("REAL", true)) 0 else ""
                    }
                }
            }
            require(values.keys().asSequence().all { key -> key in columns })
            db.execSQL("INSERT INTO `$table` (${columns.joinToString { "`$it`" }}) VALUES (${columns.joinToString { "?" }})", args.toTypedArray())
        } }
    }
    fun rebuild(context: Context, destination: File, version: Int, state: File, days: List<File>) {
        destination.parentFile!!.mkdirs()
        val room = CaptureDatabase.open(context, destination.absolutePath)
        try {
            val db = room.openHelper.writableDatabase
            if (version > db.version) throw BackupFailure("备份数据库版本比 App 新，请先升级 App")
            require(version > 0)
            // 跨天父子记录先全部插入，最后检查外键；此连接始终可写。
            db.execSQL("PRAGMA foreign_keys=OFF")
            db.beginTransaction()
            try {
                insert(db, state, true)
                days.forEach { insert(db, it, false) }
                listOf("lines_fts", "events_fts", "items_fts").forEach { db.execSQL("DELETE FROM $it") }
                app.resound.search.SearchIndex.install(db, backfill = true)
                db.query("PRAGMA foreign_key_check").use { require(!it.moveToFirst()) }
                db.query("PRAGMA integrity_check").use { require(it.moveToFirst() && it.getString(0) == "ok" && !it.moveToNext()) }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            db.query("PRAGMA wal_checkpoint(TRUNCATE)").use { require(it.moveToFirst() && it.getInt(0) == 0) }
        } finally { room.close() }
        listOf("-wal", "-shm", "-journal").forEach { File(destination.path + it).delete() }
    }
}
