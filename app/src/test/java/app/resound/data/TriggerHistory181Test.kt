package app.resound.data

import android.app.Application
import android.content.Context
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import app.resound.data.history181.ImportRecordSchema as HistoricalImports
import app.resound.data.history181.PendingDigestSchema as HistoricalPending
import app.resound.data.history181.SearchIndex as HistoricalSearch
import app.resound.data.history181.ThingSchema as HistoricalThings
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
import java.security.MessageDigest

/** 旧库用冻结的历史回调，新库走生产 open()；不拿当前 install() 伪造历史。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class TriggerHistory181Test {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private inline fun <T> CaptureDatabase.use(block: (CaptureDatabase) -> T): T =
        try { block(this) } finally { close() }
    private val searchNames = setOf(
        "search_line_insert", "search_line_update", "search_line_delete",
        "search_event_insert", "search_event_update", "search_event_delete", "search_event_hidden",
        "search_item_insert", "search_item_update", "search_item_delete",
    )
    private val thingNames = setOf("things_line_deleted", "things_event_hidden", "things_event_deleted")
    private val allNames = searchNames + thingNames + setOf("import_segment_deleted", "digest_pending_source_deleted")
    private val history = JSONArray(javaClass.getResource("/trigger-audit-181/history.json")!!.readText())
    private val freshTriggers by lazy {
        val name = "trigger-181-fresh.db"
        context.deleteDatabase(name)
        try {
            CaptureDatabase.open(context, name).use { db ->
                triggers(db.openHelper.writableDatabase).also { assertEquals(allNames, it.keys) }
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun beforeSearchIntroductionBackfillsAllThreeIndicesAndKeepsThemLive() {
        upgraded(10) { sql -> exerciseSearch(sql) }
    }

    @Test fun historicalSearchSurvivesTheSeventeenToEighteenItemTableRebuild() {
        for (version in listOf(11, 17)) upgraded(version) { sql ->
            sql.execSQL("UPDATE memory_items SET content='变更票据' WHERE id=1")
            assertEquals(1L, scalar(sql, "SELECT count(*) FROM items_fts WHERE items_fts MATCH '变更'"))
            assertEquals(0L, scalar(sql, "SELECT count(*) FROM items_fts WHERE items_fts MATCH '历史'"))
        }
    }

    @Test fun eighteenAndPreImportInstallationsKeepSearchAndGainImportCleanup() {
        for (version in listOf(18, 25)) upgraded(version) { sql ->
            sql.execSQL("""INSERT INTO import_records(id,pendingId,fileName,kind,startAt,endAt,byChannel,status,
                ingestComplete,revision,summaryRequested,speakersReady)
                VALUES(1,'history','synthetic.ogg','Meeting',1000,2000,0,'Done',1,0,0,1)""")
            sql.execSQL("UPDATE segments SET importRecordId=1 WHERE id=1")
            sql.execSQL("INSERT INTO segments(id,startAt,endAt,sourceId,path,sizeBytes,status,importRecordId) VALUES(2,2000,3000,'import','synthetic-2',1,'Done',1)")
            sql.execSQL("DELETE FROM segments WHERE id=2")
            assertEquals(1L, scalar(sql, "SELECT count(*) FROM import_records WHERE revision=1 AND status='Transcribing' AND summaryRequested=1"))
            sql.execSQL("DELETE FROM segments WHERE id=1")
            assertEquals(0L, scalar(sql, "SELECT count(*) FROM import_records"))
        }
    }

    @Test fun preThingsInstallationGainsAllThreeEvidenceCleanupTriggers() {
        upgraded(26) { sql ->
            for ((id, deletion) in listOf(
                1 to "DELETE FROM transcript_lines WHERE id=1",
                2 to "UPDATE memory_events SET deleted=1 WHERE id=2",
                3 to "DELETE FROM memory_events WHERE id=3",
            )) {
                if (id != 1) seed(sql, id)
                for (status in listOf("Pending", "Accepted")) {
                    sql.execSQL("""INSERT INTO thing_records(name,action,owner,at,sourceLineId,sourceText,status,origin,
                        explicit,createdAt,aliasesJson) VALUES('钥匙','放置','我',1000,?,'合成原话',?,'Auto',1,1000,'[]')""",
                        arrayOf(id, status))
                }
                sql.execSQL(deletion)
                assertEquals(0L, scalar(sql, "SELECT count(*) FROM thing_records WHERE status='Pending'"))
                assertEquals(id.toLong(), scalar(sql, "SELECT count(*) FROM thing_records WHERE status='Accepted' AND sourceLineId IS NULL"))
            }
        }
    }

    @Test fun prePendingInstallationGainsExactSourceDeletionCleanup() {
        upgraded(28) { sql ->
            for (lineIds in listOf("1,11", "11")) {
                sql.execSQL("INSERT INTO digest_pending(id,lineIds,content,day,promptTokens,completionTokens) VALUES(?,?,'synthetic','2026-11-13',0,0)", arrayOf(lineIds, lineIds))
            }
            sql.execSQL("DELETE FROM transcript_lines WHERE id=1")
            assertEquals(1L, scalar(sql, "SELECT count(*) FROM digest_pending"))
            assertEquals(1L, scalar(sql, "SELECT count(*) FROM digest_pending WHERE lineIds='11'"))
        }
    }

    @Test fun laterHistoricalInstallsAndRepeatedOpensKeepAllDefinitionsAndIndexRows() {
        for (version in listOf(27, 29, 32)) upgraded(version) { sql ->
            if (version == 32) exerciseSearch(sql)
        }
    }

    @Test fun schemaOnlyControlsReproduceTheFixtureShortfallWithoutHistoricalCallbacks() {
        for (version in listOf(18, 27)) {
            val name = "trigger-181-schema-only-$version.db"
            try {
                createOld(name, version, faithful = false)
                CaptureDatabase.open(context, name).use { db ->
                    val sql = db.openHelper.writableDatabase
                    assertEquals(CaptureDatabase.VERSION, sql.version)
                    val missing = searchNames + if (version == 27) thingNames else emptySet()
                    assertEquals(allNames - missing, triggers(sql).keys)
                    report("schema-only", version, 0, sql)
                    seed(sql, 2)
                    assertEquals(0L, scalar(sql, "SELECT count(*) FROM lines_fts"))
                }
            } finally { context.deleteDatabase(name) }
        }
    }

    private fun upgraded(version: Int, verify: (SupportSQLiteDatabase) -> Unit) {
        val name = "trigger-181-history-$version.db"
        try {
            val oldTriggers = createOld(name, version, faithful = true)
            CaptureDatabase.open(context, name).use { db ->
                val sql = db.openHelper.writableDatabase
                assertEquals(CaptureDatabase.VERSION, sql.version) // 生产迁移链同时完成 Room schema 验证。
                report("historical", version, oldTriggers.size, sql)
                assertEquals("v$version 缺失 ${allNames - triggers(sql).keys}", freshTriggers, triggers(sql))
                for ((name, definition) in oldTriggers) assertEquals("v$version 的 $name", definition, triggers(sql)[name])
                assertHistoricalRows(sql)
            }
            // 第二次打开只走 onOpen，检验已建触发器和索引没有重复或丢失。
            CaptureDatabase.open(context, name).use { db ->
                val sql = db.openHelper.writableDatabase
                assertEquals(freshTriggers, triggers(sql))
                assertHistoricalRows(sql)
                verify(sql)
            }
        } finally { context.deleteDatabase(name) }
    }

    private fun createOld(name: String, version: Int, faithful: Boolean): Map<String, String> {
        context.deleteDatabase(name)
        val entry = (0 until history.length()).map(history::getJSONObject).single { it.getInt("version") == version }
        val bytes = File("schemas/app.resound.data.CaptureDatabase/$version.json").readBytes()
        assertEquals(entry.getString("schemaSha256"), MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
        val schema = JSONObject(bytes.toString(Charsets.UTF_8)).getJSONObject("database")
        val callbacks = if (faithful) callbacks(entry.getJSONArray("callbacks")) else emptyList()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                    override fun onConfigure(db: SupportSQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        val entities = schema.getJSONArray("entities")
                        repeat(entities.length()) { i ->
                            val entity = entities.getJSONObject(i)
                            fun expand(sql: String) = sql.replace("\u0024{TABLE_NAME}", entity.getString("tableName"))
                            db.execSQL(expand(entity.getString("createSql")))
                            entity.optJSONArray("indices")?.let { indices -> repeat(indices.length()) { j ->
                                db.execSQL(expand(indices.getJSONObject(j).getString("createSql")))
                            } }
                        }
                        schema.getJSONArray("setupQueries").let { queries -> repeat(queries.length()) { db.execSQL(queries.getString(it)) } }
                        callbacks.forEach { it.onCreate(db) }
                    }
                    override fun onOpen(db: SupportSQLiteDatabase) { callbacks.forEach { it.onOpen(db) } }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = error("旧库只负责建库")
                }).build(),
        )
        return helper.use {
            val sql = it.writableDatabase
            val expected = if (!faithful) emptySet() else buildSet {
                if (version >= 11) addAll(searchNames)
                if (version >= 26) add("import_segment_deleted")
                if (version >= 27) addAll(thingNames)
                if (version >= 29) add("digest_pending_source_deleted")
            }
            assertEquals("v$version 的历史触发器", expected, triggers(sql).keys)
            seed(sql, 1)
            triggers(sql)
        }
    }

    private fun callbacks(names: JSONArray): List<RoomDatabase.Callback> = (0 until names.length()).map {
        when (val name = names.getString(it)) {
            "SearchIndex" -> HistoricalSearch.callback
            "ImportRecordSchema" -> HistoricalImports.callback
            "ThingSchema" -> HistoricalThings.callback
            "PendingDigestSchema" -> HistoricalPending.callback
            else -> error("未知历史回调 $name")
        }
    }

    private fun seed(sql: SupportSQLiteDatabase, id: Int) {
        sql.execSQL("INSERT OR IGNORE INTO segments(id,startAt,endAt,sourceId,path,sizeBytes,status) VALUES(1,1000,2000,'mic','synthetic',1,'Done')")
        sql.execSQL("""INSERT INTO memory_events(id,startAt,endAt,scene,title,summary,pointsJson,peopleJson,important,deleted,createdAt,model)
            VALUES(?,1000,2000,'其他','历史票据','合成摘要','[]','[]',0,0,1000,'test')""", arrayOf(id))
        sql.execSQL("INSERT INTO transcript_lines(id,segmentId,startAt,endAt,text,eventId) VALUES(?,1,1000,2000,'历史票据',?)", arrayOf(id, id))
        sql.execSQL("INSERT INTO memory_items(id,eventId,category,content,status,createdAt) VALUES(?,?,'待办','历史票据','Pending',1000)", arrayOf(id, id))
    }

    private fun assertHistoricalRows(sql: SupportSQLiteDatabase) {
        for ((table, fts) in listOf("transcript_lines" to "lines_fts", "memory_events" to "events_fts", "memory_items" to "items_fts")) {
            assertEquals(1L, scalar(sql, "SELECT count(*) FROM $table WHERE id=1"))
            assertEquals(1L, scalar(sql, "SELECT count(*) FROM $fts"))
            assertEquals(1L, scalar(sql, "SELECT count(*) FROM $fts WHERE $fts MATCH '历史' AND rowid=1"))
        }
    }

    private fun exerciseSearch(sql: SupportSQLiteDatabase) {
        seed(sql, 2)
        for ((table, fts, column) in listOf(
            Triple("transcript_lines", "lines_fts", "text"), Triple("memory_events", "events_fts", "title"),
            Triple("memory_items", "items_fts", "content"),
        )) {
            assertEquals(2L, scalar(sql, "SELECT count(*) FROM $fts WHERE $fts MATCH '历史'"))
            sql.execSQL("UPDATE $table SET $column='变更票据' WHERE id=2")
            assertEquals(1L, scalar(sql, "SELECT count(*) FROM $fts WHERE $fts MATCH '变更' AND rowid=2"))
            assertEquals(0L, scalar(sql, "SELECT count(*) FROM $fts WHERE $fts MATCH '历史' AND rowid=2"))
        }
        sql.execSQL("DELETE FROM transcript_lines WHERE id=2")
        sql.execSQL("DELETE FROM memory_items WHERE id=2")
        sql.execSQL("DELETE FROM memory_events WHERE id=2")
        for (fts in listOf("lines_fts", "events_fts", "items_fts")) assertEquals(0L, scalar(sql, "SELECT count(*) FROM $fts WHERE rowid=2"))
    }

    private fun triggers(sql: SupportSQLiteDatabase): Map<String, String> =
        sql.query("SELECT name,sql FROM sqlite_master WHERE type='trigger' ORDER BY name").use { cursor ->
            buildMap { while (cursor.moveToNext()) put(cursor.getString(0), cursor.getString(1)) }
        }

    private fun scalar(sql: SupportSQLiteDatabase, query: String): Long =
        sql.query(query).use { it.moveToFirst(); it.getLong(0) }

    private fun report(kind: String, version: Int, before: Int, sql: SupportSQLiteDatabase) {
        val names = triggers(sql).keys
        println("TRIGGER_AUDIT_181\t$kind\t$version\t$before\t${names.size}\t${(allNames - names).sorted().joinToString(",").ifEmpty { "无" }}")
    }
}
