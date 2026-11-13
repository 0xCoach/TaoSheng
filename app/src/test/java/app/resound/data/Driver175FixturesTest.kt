package app.resound.data

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.PooledConnection
import androidx.room.useReaderConnection
import androidx.sqlite.*
import app.resound.ask.*
import app.resound.backup.BackupArchive
import app.resound.backup.BackupRestore
import app.resound.search.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.channels.Channel
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

/** 卡 175 第二段：逐字重放旧实现夹具；只读标准答案，实际结果另写 build/driver175-replay。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class Driver175FixturesTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val fixtures = File("src/test/resources/driver175")
    private val at = 1794448800000L
    private fun schema(version: Int) = JSONObject(File("schemas/app.resound.data.CaptureDatabase/$version.json").readText()).getJSONObject("database")
    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
    private fun quote(name: String) = "\"${name.replace("\"", "\"\"")}\""

    internal fun golden(name: String, actual: String) {
        val file = File(fixtures, name)
        val replay = File("build/driver175-replay", name)
        replay.parentFile!!.mkdirs()
        replay.writeText(actual)
        if (name == "fresh-install.txt" || name.startsWith("migrations/")) {
            fun triggers(text: String) = text.lineSequence().filter { it.startsWith("[\"trigger\",") }
                .map { JSONArray(it).getString(1) }.toSet()
            val current = triggers(File(fixtures, "fresh-install.txt").readText())
            val expected = triggers(file.readText())
            val observed = triggers(actual)
            File(replay.path + ".triggers.txt").writeText(
                "expected=${expected.size}\nactual=${observed.size}\ncurrent=${current.size}\n" +
                    "missing=" + (current - observed).sorted().joinToString(",") + "\n")
            assertEquals("$name 手写触发器集合", expected, observed)
        }
        assertTrue("缺少旧夹具 $name；第二段禁止重录标准答案", file.isFile)
        Index183FixtureDelta.assertSame(name, file.readText(), actual)
    }

    /** 对象键排序、数组保序；文本不截断，整数不转 double，BLOB 使用十六进制。 */
    private fun canonical(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }

    // 只折叠 SQL 引号以外的空白，保留触发器字符串常量中的换行/空格。
    private fun normalizeSql(sql: String): String {
        val tokens = Regex("'(''|[^'])*'|\"(\"\"|[^\"])*\"|`[^`]*`|\\s+|[^\\s'\"`]+")
        return tokens.findAll(sql).joinToString("") { if (it.value.first().isWhitespace()) " " else it.value }.trim()
    }

    private fun rows(statement: SQLiteStatement, sort: Boolean = false): String {
        val columns = (0 until statement.getColumnCount()).map(statement::getColumnName)
        val values = buildList {
            while (statement.step()) add(canonical(JSONArray().apply {
                repeat(statement.getColumnCount()) { i -> put(when (statement.getColumnType(i)) {
                    SQLITE_DATA_NULL -> JSONObject.NULL
                    SQLITE_DATA_INTEGER -> JSONObject().put("integer", statement.getLong(i))
                    SQLITE_DATA_FLOAT -> JSONObject().put("real", statement.getDouble(i))
                    SQLITE_DATA_BLOB -> JSONObject().put("blob", statement.getBlob(i).joinToString("") { "%02x".format(it) })
                    else -> JSONObject().put("text", statement.getText(i))
                }) }
            }))
        }
        return "columns=" + canonical(JSONArray(columns)) + "\n" + (if (sort) values.sorted() else values).joinToString("\n") + "\n"
    }

    internal suspend fun snapshot(db: PooledConnection): String = buildString {
        val version = db.usePrepared("PRAGMA user_version") { check(it.step()); it.getLong(0) }
        assertEquals(CaptureDatabase.VERSION.toLong(), version)
        append("user_version=$version\n")
        val tables = mutableListOf<Pair<String, String?>>()
        db.usePrepared("SELECT type,name,tbl_name,sql FROM sqlite_master ORDER BY type,name") { stmt ->
            while (stmt.step()) {
                val sql = if (stmt.isNull(3)) null else stmt.getText(3)
                append(canonical(JSONArray(listOf(stmt.getText(0), stmt.getText(1), stmt.getText(2), sql?.let(::normalizeSql))))).append('\n')
                if (stmt.getText(0) == "table") tables += stmt.getText(1) to sql
            }
        }
        for ((table, sql) in tables.sortedBy { it.first }) {
            append("TABLE ").append(table).append('\n')
            val columns = if (sql?.startsWith("CREATE VIRTUAL TABLE", true) == true) "rowid,*" else "*"
            append(db.usePrepared("SELECT $columns FROM ${quote(table)}") { rows(it, sort = true) })
        }
    }

    private suspend fun healthy(db: PooledConnection) {
        db.usePrepared("PRAGMA integrity_check") { assertTrue(it.step()); assertEquals("ok", it.getText(0)) }
        db.usePrepared("PRAGMA foreign_key_check") { assertFalse("合成数据违反外键", it.step()) }
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
        // 只重放原有32条路径；33→34由Index183MigrationTest单独覆盖，不新录旧夹具。
        for (version in 1..32) {
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
                    db.useReaderConnection { sql ->
                        sql.usePrepared("PRAGMA user_version") { assertTrue(it.step()); assertEquals(CaptureDatabase.VERSION.toLong(), it.getLong(0)) }
                        healthy(sql)
                        golden("migrations/from-${version.toString().padStart(2, '0')}.txt", snapshot(sql))
                    }
                } finally { db.close() }
            } finally { context.deleteDatabase(name) }
        }
    }

    @Test fun freshInstallIncludesAllCallbacks() = runBlocking(Dispatchers.IO) {
        val name = "driver175-new.db"; context.deleteDatabase(name)
        val db = CaptureDatabase.open(context, name)
        try {
            db.useReaderConnection { sql ->
                healthy(sql)
                golden("fresh-install.txt", snapshot(sql))
                golden("engine.txt", sql.usePrepared("SELECT sqlite_version() AS sqlite_version") { rows(it) } + sql.usePrepared("PRAGMA compile_options") { rows(it, true) })
            }
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun writerTransactionCommitsNestedSeed() = runBlocking(Dispatchers.IO) {
        val name = "driver175-transaction-commit.db"; context.deleteDatabase(name)
        val db = CaptureDatabase.open(context, name)
        try {
            db.writeTransaction { db.writeTransaction { querySeed(db) } }
            db.useReaderConnection { healthy(it); golden("queries-seed.txt", snapshot(it)) }
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun cancelledWriterRollsBackSeed() = runBlocking(Dispatchers.IO) {
        val name = "driver175-transaction-cancel.db"; context.deleteDatabase(name)
        val db = CaptureDatabase.open(context, name)
        try {
            try {
                db.writeTransaction {
                    db.writeTransaction { querySeed(db) }
                    throw kotlinx.coroutines.CancellationException("合成取消")
                }
                fail("应当传播取消")
            } catch (_: kotlinx.coroutines.CancellationException) { }
            db.useReaderConnection { healthy(it); golden("fresh-install.txt", snapshot(it)) }
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun writerNotifiesActiveTableFlow() = runBlocking(Dispatchers.IO) {
        val name = "driver175-notifications.db"; context.deleteDatabase(name)
        val db = CaptureDatabase.open(context, name)
        val changes = Channel<Set<String>>(Channel.UNLIMITED)
        val observer = launch {
            db.invalidationTracker.createFlow("segments", "memory_events", "memory_items").collect { changes.send(it) }
        }
        try {
            assertEquals(setOf("segments", "memory_events", "memory_items"), withTimeout(5000) { changes.receive() })
            db.writeTransaction { querySeed(db) }
            assertEquals(setOf("segments", "memory_events", "memory_items"), withTimeout(5000) { changes.receive() })
            db.writeSql("UPDATE segments SET status='Done' WHERE id=1")
            assertTrue(withTimeout(5000) { changes.receive() }.contains("segments"))
            db.useReaderConnection { golden("queries-seed.txt", snapshot(it)) }
        } finally { observer.cancelAndJoin(); changes.close(); db.close(); context.deleteDatabase(name) }
    }

    /** 验证另一条驱动连接能读到 Room 当前合成 WAL/FTS，前后快照不变。 */
    @Test fun androidDriverReadsExistingSyntheticWalAndFts() = runBlocking(Dispatchers.IO) {
        val name = "driver175-wal-probe.db"; context.deleteDatabase(name)
        val db = CaptureDatabase.open(context, name)
        try {
            querySeed(db)
            db.readSql("PRAGMA journal_mode") { assertTrue(it.step()); assertEquals("wal", it.getText(0)) }
            assertTrue(File(context.getDatabasePath(name).path + "-wal").length() > 0)
            val before = db.useReaderConnection { snapshot(it) }
            val connection = androidx.sqlite.driver.AndroidSQLiteDriver().open(context.getDatabasePath(name).path)
            try {
                connection.prepare("SELECT rowid FROM lines_fts WHERE lines_fts MATCH ? ORDER BY rowid").use { stmt ->
                    stmt.bindText(1, SearchTokens.terms(listOf("中文报销，A12!")).single())
                    val ids = buildList { while (stmt.step()) add(stmt.getLong(0)) }
                    assertEquals(listOf(1L, 2L, 3L), ids)
                }
            } finally { connection.close() }
            assertEquals(before, db.useReaderConnection { snapshot(it) })
        } finally { db.close(); context.deleteDatabase(name) }
    }

    private suspend fun querySeed(db: CaptureDatabase) {
        for (id in 1..3) {
            db.writeSql("INSERT INTO segments(id,startAt,endAt,sourceId,path,sizeBytes,status) VALUES(?,?,?,'synthetic',?,1,'Done')", arrayOf<Any?>(id, at + id, at + 1000 + id, "synthetic-$id"))
            db.writeSql("INSERT INTO memory_events(id,startAt,endAt,scene,title,summary,pointsJson,peopleJson,important,deleted,createdAt,model) VALUES(?,?,?,'其他',?,'中文报销，发票 A12!','[\"交通票据\"]','[]',0,?,?, 'synthetic')", arrayOf<Any?>(id, at + id, at + 1000 + id, if (id == 3) "隐藏事件" else "交通发票$id", if (id == 3) 1 else 0, at + id))
            db.writeSql("INSERT INTO memory_items(id,eventId,category,content,status,createdAt) VALUES(?,?,'灵感',?,'Pending',?)", arrayOf<Any?>(id, id, "交通发票$id，中文报销", at + id))
            db.writeSql("INSERT INTO transcript_lines(id,segmentId,startAt,endAt,text,eventId,speakerName) VALUES(?,?,?,?,?,?,?)", arrayOf<Any?>(id, id, at + id, at + id + 1000, "交通发票$id，中文报销 A12!", id, if (id == 1) null else "合成人物"))
            db.writeSql("INSERT INTO embeddings(kind,refId,model,dim,vector,updatedAt) VALUES('item',?,'synthetic',2,?,?)", arrayOf(id, Vectors.encode(floatArrayOf(1f, 0f)), at))
            db.writeSql("INSERT INTO embeddings(kind,refId,model,dim,vector,updatedAt) VALUES('event',?,'synthetic',2,?,?)", arrayOf(id, Vectors.encode(floatArrayOf(1f, 0f)), at))
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
            .setDriver(object : SQLiteDriver {
                private val driver = androidx.sqlite.driver.AndroidSQLiteDriver()
                override val hasConnectionPool = driver.hasConnectionPool
                override fun open(fileName: String): SQLiteConnection {
                    val connection = driver.open(fileName)
                    return object : SQLiteConnection by connection {
                        override fun prepare(sql: String): SQLiteStatement {
                            val statement = connection.prepare(sql)
                            return object : SQLiteStatement by statement {
                                private val arguments = mutableMapOf<Int, Any?>()
                                private var started = false
                                override fun bindNull(index: Int) { arguments[index] = null; statement.bindNull(index) }
                                override fun bindLong(index: Int, value: Long) { arguments[index] = value; statement.bindLong(index, value) }
                                override fun bindDouble(index: Int, value: Double) { arguments[index] = value; statement.bindDouble(index, value) }
                                override fun bindText(index: Int, value: String) { arguments[index] = value; statement.bindText(index, value) }
                                override fun bindBlob(index: Int, value: ByteArray) { arguments[index] = value.copyOf(); statement.bindBlob(index, value) }
                                override fun clearBindings() { arguments.clear(); statement.clearBindings() }
                                override fun reset() { started = false; statement.reset() }
                                override fun step(): Boolean {
                                    if (!started && capturing && sql.trimStart().startsWith("SELECT", true)
                                        && !sql.contains("room_table_modification_log")) {
                                        val args = arguments.toSortedMap().values.toList()
                                        val resultRows = connection.prepare(sql).use {
                                            it.bindArguments(args.toTypedArray()); rows(it)
                                        }
                                        captured += Triple(sql, args, resultRows)
                                    }
                                    started = true
                                    return statement.step()
                                }
                            }
                        }
                    }
                }
            })
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
            db.useReaderConnection { golden("queries-seed.txt", snapshot(it)) }
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
            record("review.materialChanges", "SELECT 1") { db.review().materialChanges(androidx.room.RoomRawQuery("SELECT 1")).first() }
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
            record("ideas.merge", "model=synthetic") { IdeaMerger(db).merge("synthetic"); db.readSql("SELECT id,mergedInto FROM memory_items ORDER BY id") { rows(it) } }
            golden("queries.txt", out.toString())
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun backupArchiveAndRestore() = runBlocking(Dispatchers.IO) {
        context.deleteDatabase("capture.db")
        val root = File(context.cacheDir, "driver175-backup").apply { deleteRecursively(); mkdirs() }
        File(context.noBackupFilesDir, "backup-restore").deleteRecursively()
        val db = CaptureDatabase.open(context)
        try {
            // 备份用业务有效的搜索数据；迁移夹具另覆盖全部表。
            querySeed(db)
            db.useReaderConnection { healthy(it); golden("backup-before.txt", snapshot(it)) }
            val archive = File(root, "synthetic.zip")
            BackupArchive.create(context, db, File(root, "stage").apply { mkdirs() }, archive, at)
            val fixture = File(fixtures, "synthetic-backup.zip")
            assertTrue(fixture.isFile)
            // 新生成的备份也校验，固定旧备份永久保留，不能重录覆盖。
            val newVerified = File(root, "new-verified")
            BackupArchive.verify(archive, newVerified, CaptureDatabase.VERSION)
            val newBackup = CaptureDatabase.open(context, File(newVerified, BackupArchive.DATABASE).absolutePath)
            try {
                newBackup.useReaderConnection { healthy(it); golden("backup-before.txt", snapshot(it)) }
            } finally { newBackup.close() }
            val verified = File(root, "old-verified")
            BackupArchive.verify(fixture, verified, CaptureDatabase.VERSION)
            db.close()
            BackupRestore.stage(context, verified)
            BackupRestore.applyPending(context)
            val restored = CaptureDatabase.open(context)
            try {
                restored.useReaderConnection { healthy(it); golden("backup-restored.txt", snapshot(it)) }
            } finally { restored.close() }
        } finally { db.close(); context.deleteDatabase("capture.db"); root.deleteRecursively() }
    }

    @Test fun backupDuringRecordingWritesKeepsParentsAndSearchConsistent() = runBlocking(Dispatchers.IO) {
        val name = "driver175-recording-backup.db"; context.deleteDatabase(name)
        val db = CaptureDatabase.open(context, name)
        val root = File(context.cacheDir, "driver175-concurrent").apply { mkdirs() }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val requested = CompletableDeferred<Unit>()
        val writer = launch {
            db.writeTransaction {
                db.capture().segment(SegmentEntity(100, at, at + 1000, "mic", "synthetic-recording", 1, "Done"))
                entered.complete(Unit)
                release.await()
                db.writeSql("INSERT INTO transcript_lines(id,segmentId,startAt,endAt,text) VALUES(100,100,?,?,'并发录音')", arrayOf(at, at + 1000))
            }
        }
        try {
            withTimeout(5000) { entered.await() }
            val copy = File(root, "copy.db")
            val backup = async(Dispatchers.IO) { requested.complete(Unit); BackupArchive.snapshot(db, copy) }
            withTimeout(5000) { requested.await() }
            release.complete(Unit)
            withTimeout(5000) { writer.join(); backup.await() }
            androidx.sqlite.driver.AndroidSQLiteDriver().open(copy.path).use { connection ->
                connection.prepare("PRAGMA integrity_check").use { assertTrue(it.step()); assertEquals("ok", it.getText(0)); assertFalse(it.step()) }
                connection.prepare("PRAGMA foreign_key_check").use { assertFalse(it.step()) }
                connection.prepare("SELECT count(*) FROM segments s JOIN transcript_lines l ON l.segmentId=s.id WHERE s.id=100").use { assertTrue(it.step()); assertEquals(1L, it.getLong(0)) }
                connection.prepare("SELECT rowid FROM lines_fts WHERE lines_fts MATCH ?").use {
                    it.bindText(1, SearchTokens.terms(listOf("并发录音")).single())
                    assertTrue(it.step()); assertEquals(100L, it.getLong(0))
                }
            }
        } finally { release.complete(Unit); writer.cancelAndJoin(); db.close(); context.deleteDatabase(name); root.deleteRecursively() }
    }
}
