package app.resound.backup

import android.content.Context
import androidx.sqlite.*
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.room.PooledConnection
import androidx.room.useWriterConnection
import androidx.room.immediateTransaction
import kotlinx.coroutines.runBlocking
import app.resound.data.*
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId

/** 分卷只保存数据，不执行备份中的 SQL；列名、表名始终由当前 App 决定。 */
internal object DayVolumeRows {
    val dated = linkedMapOf(
        "topics" to "createdAt", "topic_spans" to "startAt", "topic_marks" to "at",
        "import_records" to "startAt", "segments" to "startAt", "transcript_lines" to "startAt", "memory_events" to "startAt",
        "thing_records" to "at", "memory_items" to "createdAt", "skill_cards" to "at", "reviews" to "generatedAt",
        "ask_conversations" to "createdAt", "ask_messages" to "at",
        "asr_attempts" to "at", "asr_usage" to "day", "llm_usage_v2" to "day", "skill_runs" to "at",
        "app_power" to "at", "battery" to "at", "heartbeats" to "at", "watchdog" to "at", "env_sounds" to "startAt",
        // 足迹（PR #19）：定位点按天分卷
        "location_points" to "at",
    )
    val global = setOf("import_fingerprints", "folder_observations", "device_transfers", "things", "thing_watch_state", "voiceprint_people", "voiceprint_samples", "voiceprint_exclusions", "location_places")
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
        try { AndroidSQLiteDriver().open(source.path).use { db ->
            (dated.keys + global).sorted().forEach { table ->
                val keys = mutableListOf<Pair<Int, String>>()
                db.prepare("PRAGMA table_info(`$table`)").use { c ->
                    while (c.step()) if (c.getLong(5).toInt() > 0) keys += c.getLong(5).toInt() to c.getText(1)
                }
                require(keys.isNotEmpty())
                val order = keys.sortedBy { it.first }.joinToString { "`${it.second}`" }
                db.prepare("SELECT * FROM `$table` ORDER BY $order").use { c ->
                    val columns = c.columnNames.sorted()
                    while (c.step()) {
                        val values = JSONObject()
                        for (column in columns) {
                            val i = c.columnNames.indexOf(column).also { require(it >= 0) }
                            val v: Any = when (c.getColumnType(i)) {
                                SQLITE_DATA_NULL -> JSONObject.NULL
                                SQLITE_DATA_INTEGER -> c.getLong(i)
                                SQLITE_DATA_FLOAT -> c.getDouble(i)
                                SQLITE_DATA_TEXT -> c.getText(i)
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
            return db.userVersion to files
        } } finally { writers.values.forEach { it.close() } }
    }
    suspend fun insert(db: PooledConnection, rows: File, state: Boolean) {
        rows.bufferedReader().useLines { lines -> lines.forEach { line ->
            val row = JSONObject(line)
            val originalTable = row.getString("table")
            val table = if (originalTable == "llm_usage") "llm_usage_v2" else originalTable
            require(table in if (state) global else dated.keys)
            val values = row.getJSONObject("values")
            if (originalTable == "llm_usage") values.put("purpose", "Other")
            val columns = mutableListOf<String>()
            val args = mutableListOf<Any?>()
            db.usePrepared("PRAGMA table_info(`$table`)") { c ->
                while (c.step()) {
                    val name = c.getText(1)
                    if (values.has(name)) {
                        columns += name
                        val value = values.get(name)
                        args += when (value) {
                            JSONObject.NULL -> null
                            is JSONObject -> android.util.Base64.decode(value.getString("blob"), android.util.Base64.NO_WRAP)
                            else -> value
                        }
                    } else if (c.getLong(3).toInt() != 0 && c.isNull(4)) {
                        // 老分卷没有的新必填列：Room 未声明 SQL 默认值时使用类型零值。
                        columns += name
                        args += if (c.getText(2).contains("INT", true) || c.getText(2).contains("REAL", true)) 0 else ""
                    }
                }
            }
            require(values.keys().asSequence().all { key -> key in columns })
            db.executeSql("INSERT INTO `$table` (${columns.joinToString { "`$it`" }}) VALUES (${columns.joinToString { "?" }})", args.toTypedArray())
        } }
    }
    fun rebuild(context: Context, destination: File, version: Int, state: File, days: List<File>) {
        destination.parentFile!!.mkdirs()
        val room = CaptureDatabase.open(context, destination.absolutePath)
        try {
            runBlocking { room.useWriterConnection { db ->
                val current = db.usePrepared("PRAGMA user_version") { check(it.step()); it.getLong(0).toInt() }
                if (version > current) throw BackupFailure("备份数据库版本比 App 新，请先升级 App")
                require(version > 0)
                // 跨天父子记录先全部插入，最后检查外键；开关与事务始终使用同一 writer。
                db.executeSql("PRAGMA foreign_keys=OFF")
                try {
                    db.immediateTransaction {
                        insert(this, state, true)
                        days.forEach { insert(this, it, false) }
                        listOf("lines_fts", "events_fts", "items_fts").forEach { executeSql("DELETE FROM $it") }
                        val statements = mutableListOf<String>()
                        app.resound.search.SearchIndex.install({ statements += it }, backfill = true)
                        statements.forEach { executeSql(it) }
                        usePrepared("PRAGMA foreign_key_check") { require(!it.step()) }
                        usePrepared("PRAGMA integrity_check") { require(it.step() && it.getText(0) == "ok" && !it.step()) }
                    }
                } finally { db.executeSql("PRAGMA foreign_keys=ON") }
                db.usePrepared("PRAGMA wal_checkpoint(TRUNCATE)") { require(it.step() && it.getLong(0) == 0L) }
            } }
        } finally { room.close() }
        listOf("-wal", "-shm", "-journal", ".lck").forEach { File(destination.path + it).delete() }
    }
}
