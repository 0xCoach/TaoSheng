package app.resound.data

import android.app.Application
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import app.resound.ask.*
import app.resound.backup.BackupArchive
import app.resound.backup.BackupRestore
import app.resound.search.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.time.ZoneId

/** 卡 175 第一段：只调用现有实现。默认逐字对照；录制仅允许创建尚不存在的夹具。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class Driver175FixturesTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val fixtures = File("src/test/resources/driver175")
    private val recording get() = System.getenv("TAOSHENG_175_RECORD") == "1"
    private val at = 1794448800000L
    private fun schema(version: Int) = JSONObject(File("schemas/app.resound.data.CaptureDatabase/$version.json").readText()).getJSONObject("database")
    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
    private fun quote(name: String) = "\"${name.replace("\"", "\"\"")}\""

    private fun golden(name: String, actual: String) {
        val file = File(fixtures, name)
        if (recording && !file.exists()) { file.parentFile!!.mkdirs(); file.writeText(actual) }
        assertTrue("缺少夹具 $name；仅在旧实现上设置 TAOSHENG_175_RECORD=1 录制", file.isFile)
        assertEquals(name, file.readText(), actual)
    }

    /** 对象键排序、数组保序；文本不截断，整数不转 double，BLOB 使用十六进制。 */
    private fun canonical(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }

    private fun rows(cursor: Cursor, sort: Boolean = false): String = cursor.use { c ->
        val rows = buildList {
            while (c.moveToNext()) add(canonical(JSONArray().apply {
                repeat(c.columnCount) { i -> put(when (c.getType(i)) {
                    Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                    Cursor.FIELD_TYPE_INTEGER -> JSONObject().put("integer", c.getLong(i))
                    Cursor.FIELD_TYPE_FLOAT -> JSONObject().put("real", c.getDouble(i))
                    Cursor.FIELD_TYPE_BLOB -> JSONObject().put("blob", c.getBlob(i).joinToString("") { "%02x".format(it) })
                    else -> JSONObject().put("text", c.getString(i))
                }) }
            }))
        }
        "columns=" + canonical(JSONArray(c.columnNames.toList())) + "\n" + (if (sort) rows.sorted() else rows).joinToString("\n") + "\n"
    }

    // 只折叠 SQL 引号以外的空白，保留触发器字符串常量中的换行/空格。
    private fun normalizeSql(sql: String): String {
        val tokens = Regex("'(''|[^'])*'|\"(\"\"|[^\"])*\"|`[^`]*`|\\s+|[^\\s'\"`]+")
        return tokens.findAll(sql).joinToString("") { if (it.value.first().isWhitespace()) " " else it.value }.trim()
    }

    private fun snapshot(db: SupportSQLiteDatabase): String = buildString {
        append("user_version=${db.version}\n")
        val tables = mutableListOf<Pair<String, String?>>()
        db.query("SELECT type,name,tbl_name,sql FROM sqlite_master ORDER BY type,name").use { c ->
            while (c.moveToNext()) {
                val sql = if (c.isNull(3)) null else c.getString(3)
                append(canonical(JSONArray(listOf(c.getString(0), c.getString(1), c.getString(2), sql?.let(::normalizeSql))))).append('\n')
                if (c.getString(0) == "table") tables += c.getString(1) to sql
            }
        }
        for ((table, sql) in tables.sortedBy { it.first }) {
            append("TABLE ").append(table).append('\n')
            // 虚表的 rowid 是搜索关联键；影子表、sqlite_sequence、room_master_table 均不忽略。
            val columns = if (sql?.startsWith("CREATE VIRTUAL TABLE", true) == true) "rowid,*" else "*"
            append(rows(db.query("SELECT $columns FROM ${quote(table)}"), sort = true))
        }
    }

    private fun healthy(db: SupportSQLiteDatabase) {
        db.query("PRAGMA integrity_check").use { assertTrue(it.moveToFirst()); assertEquals("ok", it.getString(0)) }
        db.query("PRAGMA foreign_key_check").use { assertFalse("合成数据违反外键", it.moveToFirst()) }
    }

    /** 所有实体各三行：可空列 null/非 null，中文、引号/转义字符、长正文、BLOB、外键。 */
    private fun seed(entities: List<JSONObject>, exec: (String, Array<Any?>) -> Unit) {
        val remaining = entities.toMutableList()
        val done = mutableSetOf<String>()
        while (remaining.isNotEmpty()) {
            val entity = remaining.firstOrNull { e -> (e.optJSONArray("foreignKeys") ?: JSONArray()).objects().all { it.getString("table") in done } }
                ?: error("合成数据外键排序存在环")
            val table = entity.getString("tableName")
            val fields = entity.getJSONArray("fields").objects()
            val primary = entity.getJSONObject("primaryKey").getJSONArray("columnNames").let { a -> (0 until a.length()).map(a::getString).toSet() }
            val foreign = (entity.optJSONArray("foreignKeys") ?: JSONArray()).objects().flatMap { fk ->
                val columns = fk.getJSONArray("columns"); (0 until columns.length()).map(columns::getString)
            }.toSet()
            for (row in 1..3) {
                val values: Array<Any?> = fields.map<JSONObject, Any?> { f ->
                    val column = f.getString("columnName")
                    when {
                        !f.optBoolean("notNull") && column !in primary && row == 1 -> null
                        column in foreign -> row.toLong()
                        column == "status" && table == "segments" -> listOf("待转写", "Failed", "Done")[row - 1]
                        column == "status" && table == "memory_items" -> listOf("Pending", "Done", "Ignored")[row - 1]
                        column == "result" && table == "asr_attempts" -> "Failed"
                        column == "speakerName" -> if (row == 2) "我" else "合成人物"
                        column == "source" && table == "voiceprint_samples" -> "line"
                        column == "groupKey" -> "line:$row"
                        column == "lineIds" -> "$row"
                        f.getString("affinity") == "INTEGER" -> when {
                            column in primary || column.endsWith("Id") -> row.toLong()
                            column in setOf("deleted", "noMerge", "applied", "ignored", "imported") -> 0L
                            column.endsWith("At") || column == "at" -> at + row * 1000L
                            else -> row.toLong()
                        }
                        f.getString("affinity") == "REAL" -> row + 0.125
                        f.getString("affinity") == "BLOB" -> byteArrayOf(0, row.toByte(), -1, 42)
                        column.endsWith("Json") -> "[]"
                        column == "day" || column.endsWith("Date") -> "2026-11-${10 + row}"
                        column in setOf("text", "content", "summary", "brief") && row == 3 -> "长中文，标点 ' 引号\\n".repeat(64)
                        else -> "合成-$table-$column-$row"
                    }
                }.toTypedArray()
                exec("INSERT INTO ${quote(table)} (${fields.joinToString { quote(it.getString("columnName")) }}) VALUES (${fields.joinToString { "?" }})", values)
            }
            done += table; remaining.remove(entity)
        }
    }

    @Test fun migrationsFromEveryHistoricalSchema() = runBlocking(Dispatchers.IO) {
        val versions = File("schemas/app.resound.data.CaptureDatabase").listFiles()!!.mapNotNull { it.nameWithoutExtension.toIntOrNull() }.sorted()
        assertEquals((1..CaptureDatabase.VERSION).toList(), versions)
        for (version in versions.filter { it < CaptureDatabase.VERSION }) {
            val name = "driver175-v$version.db"
            context.deleteDatabase(name)
            val definition = schema(version)
            val entities = definition.getJSONArray("entities").objects()
            try {
                context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
                    old.enableWriteAheadLogging()
                    entities.forEach { e ->
                        fun sql(value: String) = value.replace("\u0024{TABLE_NAME}", e.getString("tableName"))
                        old.execSQL(sql(e.getString("createSql")))
                        (e.optJSONArray("indices") ?: JSONArray()).objects().forEach { old.execSQL(sql(it.getString("createSql"))) }
                    }
                    val setup = definition.getJSONArray("setupQueries")
                    repeat(setup.length()) { old.execSQL(setup.getString(it)) }
                    seed(entities) { sql, args -> old.execSQL(sql, args) }
                    old.version = version
                    // 逐表确认种子没有漏表；外键检查会在 Room 升级后再次执行。
                    for (e in entities) old.rawQuery("SELECT count(*) FROM ${quote(e.getString("tableName"))}", null).use {
                        assertTrue(it.moveToFirst()); assertEquals(3, it.getInt(0))
                    }
                    old.rawQuery("PRAGMA foreign_key_check", null).use { assertFalse(it.moveToFirst()) }
                }
                val db = CaptureDatabase.open(context, name) // 产品 open：完整 MIGRATIONS + 四组回调。
                try {
                    val sql = db.openHelper.writableDatabase
                    assertEquals(CaptureDatabase.VERSION, sql.version)
                    healthy(sql)
                    golden("migrations/from-${version.toString().padStart(2, '0')}.txt", snapshot(sql))
                } finally { db.close() }
            } finally { context.deleteDatabase(name) }
        }
    }

    @Test fun freshInstallIncludesAllCallbacks() = runBlocking(Dispatchers.IO) {
        val name = "driver175-new.db"; context.deleteDatabase(name)
        val db = CaptureDatabase.open(context, name)
        try {
            val sql = db.openHelper.writableDatabase
            healthy(sql)
            golden("fresh-install.txt", snapshot(sql))
            golden("engine.txt", rows(sql.query("SELECT sqlite_version() AS sqlite_version")) + rows(sql.query("PRAGMA compile_options"), true))
        } finally { db.close(); context.deleteDatabase(name) }
    }

    /** 仅调研底层驱动读已有 WAL/FTS 的能力；不设置 Room driver，也不切换产品实现。 */
    @Test fun androidDriverReadsExistingSyntheticWalAndFts() = runBlocking(Dispatchers.IO) {
        val name = "driver175-wal-probe.db"; context.deleteDatabase(name)
        val db = CaptureDatabase.open(context, name)
        try {
            querySeed(db)
            val sql = db.openHelper.writableDatabase
            sql.query("PRAGMA journal_mode").use { assertTrue(it.moveToFirst()); assertEquals("wal", it.getString(0)) }
            assertTrue(File(context.getDatabasePath(name).path + "-wal").length() > 0)
            val before = snapshot(sql)
            val connection = androidx.sqlite.driver.AndroidSQLiteDriver().open(context.getDatabasePath(name).path)
            try {
                connection.prepare("SELECT rowid FROM lines_fts WHERE lines_fts MATCH ? ORDER BY rowid").use { stmt ->
                    stmt.bindText(1, SearchTokens.terms(listOf("中文报销，A12!")).single())
                    val ids = buildList { while (stmt.step()) add(stmt.getLong(0)) }
                    assertEquals(listOf(1L, 2L, 3L), ids)
                }
            } finally { connection.close() }
            assertEquals(before, snapshot(sql))
        } finally { db.close(); context.deleteDatabase(name) }
    }

    private fun querySeed(db: CaptureDatabase) {
        val sql = db.openHelper.writableDatabase
        for (id in 1..3) {
            sql.execSQL("INSERT INTO segments(id,startAt,endAt,sourceId,path,sizeBytes,status) VALUES(?,?,?,'synthetic',?,1,'Done')", arrayOf(id, at + id, at + 1000 + id, "synthetic-$id"))
            sql.execSQL("INSERT INTO memory_events(id,startAt,endAt,scene,title,summary,pointsJson,peopleJson,important,deleted,createdAt,model) VALUES(?,?,?,'其他',?,'中文报销，发票 A12!','[\"交通票据\"]','[]',0,?,?, 'synthetic')", arrayOf(id, at + id, at + 1000 + id, if (id == 3) "隐藏事件" else "交通发票$id", if (id == 3) 1 else 0, at + id))
            sql.execSQL("INSERT INTO memory_items(id,eventId,category,content,status,createdAt) VALUES(?,?,'灵感',?,'Pending',?)", arrayOf(id, id, "交通发票$id，中文报销", at + id))
            sql.execSQL("INSERT INTO transcript_lines(id,segmentId,startAt,endAt,text,eventId,speakerName) VALUES(?,?,?,?,?,?,?)", arrayOf(id, id, at + id, at + id + 1000, "交通发票$id，中文报销 A12!", id, if (id == 1) null else "合成人物"))
            sql.execSQL("INSERT INTO embeddings(kind,refId,model,dim,vector,updatedAt) VALUES('item',?,'synthetic',2,?,?)", arrayOf(id, Vectors.encode(floatArrayOf(1f, 0f)), at))
            sql.execSQL("INSERT INTO embeddings(kind,refId,model,dim,vector,updatedAt) VALUES('event',?,'synthetic',2,?,?)", arrayOf(id, Vectors.encode(floatArrayOf(1f, 0f)), at))
        }
    }

    @Test fun dynamicQueriesAndFullTextSearch() = runBlocking(Dispatchers.IO) {
        val captured = mutableListOf<Triple<String, List<Any?>, String>>()
        var capturing = false
        lateinit var db: CaptureDatabase
        val name = "driver175-queries.db"; context.deleteDatabase(name)
        db = Room.databaseBuilder(context, CaptureDatabase::class.java, name)
            .addMigrations(*CaptureDatabase.MIGRATIONS)
            .addCallback(SearchIndex.callback).addCallback(app.resound.importer.ImportRecordSchema.callback)
            .addCallback(app.resound.things.ThingSchema.callback).addCallback(PendingDigestSchema.callback)
            .setQueryCallback({ sql, args ->
                if (capturing && sql.trimStart().startsWith("SELECT", true)) {
                    // 在产品 SELECT 执行前同步读取同一条 SQL；避免 IdeaMerger 写入后重查改变原始结果。
                    capturing = false
                    try { captured += Triple(sql, args.toList(), rows(db.query(SimpleSQLiteQuery(sql, args.toTypedArray())))) }
                    finally { capturing = true }
                }
            }, { it.run() })
            .build()
        val out = StringBuilder()
        suspend fun record(id: String, input: String, operation: suspend () -> Any?) {
            captured.clear(); capturing = true
            val result = try { operation() } finally { capturing = false }
            out.append("CASE $id\nINPUT $input\nRESULT ${canonical(result)}\n")
            for ((sql, args, resultRows) in captured.toList()) {
                out.append("SQL ${normalizeSql(sql)}\nARGS ${canonical(JSONArray(args))}\n")
                out.append(resultRows)
            }
        }
        try {
            querySeed(db)
            golden("queries-seed.txt", snapshot(db.openHelper.writableDatabase))
            val reader = AskContextReader(db)
            fun source(kind: String, id: Long = 1) = AskSource(kind, id, at, "合成", "合成")
            for (kind in listOf("line", "event", "item")) record("context.refresh.$kind", "$kind/1") { reader.refresh(source(kind))?.let(::JSONObject) }
            record("context.refresh.hidden", "event/3") { reader.refresh(source("event", 3)) }
            for (kind in listOf("event", "item")) record("context.record.$kind", "$kind/1") { reader.record(source(kind)).toString() }
            record("context.lines", "eventId=1") { reader.lines(1).toString() }
            val changes = AskChanges(db, { at })
            for (args in listOf("{\"operation\":\"item_status\",\"id\":1,\"status\":\"Done\"}", "{\"operation\":\"event_star\",\"id\":1,\"starred\":true}")) {
                record("changes.${JSONObject(args).getString("operation")}", args) { JSONObject(changes.propose(JSONObject(args), listOf(source("item"), source("event"))).encode()) }
            }
            record("review.materialChanges", "SELECT 1") { db.review().materialChanges(SimpleSQLiteQuery("SELECT 1")).first() }
            record("embedding.pending", "model=unbuilt") { EmbeddingStore(db).pending("unbuilt").toString() }
            val spec = JSONObject(File("src/main/assets/prompts/ask-sql.json").readText())
            val tools = AskTools(db, spec, ZoneId.of("UTC"))
            for (tool in listOf("search_lines", "search_events", "list_items")) {
                for ((label, words) in listOf("chinese" to listOf("发票"), "multi" to listOf("发票", "中文报销"), "punctuation" to listOf("中文，报销! A12"), "none" to listOf("完全不存在"))) {
                    val args = JSONObject().put("keywords", JSONArray(words))
                    record("fts.$tool.$label", canonical(args)) { tools.execute(tool, args, mutableListOf()) }
                }
            }
            val embedder = object : Embedder {
                override val model = "synthetic"
                override fun ready() = true
                override fun embed(text: String, query: Boolean) = floatArrayOf(1f, 0f)
            }
            val semantic = AskTools(db, spec, ZoneId.of("UTC"), { embedder })
            record("tools.semanticIds", "semantic_search/query=合成向量") { semantic.execute("semantic_search", JSONObject().put("query", "合成向量"), mutableListOf()) }
            record("ideas.merge", "model=synthetic") { IdeaMerger(db).merge("synthetic"); rows(db.query("SELECT id,mergedInto FROM memory_items ORDER BY id", null)) }
            golden("queries.txt", out.toString())
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun backupArchiveAndRestore() = runBlocking(Dispatchers.IO) {
        context.deleteDatabase("capture.db")
        val root = File(context.cacheDir, "driver175-backup").apply { deleteRecursively(); mkdirs() }
        File(context.noBackupFilesDir, "backup-restore").deleteRecursively()
        val db = CaptureDatabase.open(context)
        try {
            val sql = db.openHelper.writableDatabase
            // 备份用业务有效的搜索数据；迁移夹具另覆盖全部表。
            querySeed(db)
            healthy(sql)
            golden("backup-before.txt", snapshot(sql))
            val archive = File(root, "synthetic.zip")
            BackupArchive.create(context, db, File(root, "stage").apply { mkdirs() }, archive, at)
            val fixture = File(fixtures, "synthetic-backup.zip")
            if (recording && !fixture.exists()) archive.copyTo(fixture)
            assertTrue(fixture.isFile)
            // 新生成的备份也校验，固定旧备份永久保留，不能重录覆盖。
            BackupArchive.verify(archive, File(root, "new-verified"), CaptureDatabase.VERSION)
            val verified = File(root, "old-verified")
            BackupArchive.verify(fixture, verified, CaptureDatabase.VERSION)
            db.close()
            BackupRestore.stage(context, verified)
            BackupRestore.applyPending(context)
            val restored = CaptureDatabase.open(context)
            try {
                healthy(restored.openHelper.writableDatabase)
                golden("backup-restored.txt", snapshot(restored.openHelper.writableDatabase))
            } finally { restored.close() }
        } finally { db.close(); context.deleteDatabase("capture.db"); root.deleteRecursively() }
    }
}
