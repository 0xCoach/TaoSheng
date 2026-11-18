package app.resound.diagnostics

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 仅限本进程的主库。SQLite 驱动的同步回调可能来自不同连接。 */
internal class DatabaseQueryStats(private val now: () -> Long = System::currentTimeMillis) {
    data class Query(val count: Long = 0, val nanos: Long = 0, val rows: Long = 0)
    data class Bucket(val queries: Map<String, Query> = emptyMap(), val tables: Map<String, Long> = emptyMap())
    private val buckets = MutableStateFlow(emptyMap<Long, Bucket>())
    private val window = 600_000L

    private inline fun record(crossinline change: (Bucket) -> Bucket) {
        val key = now() / window
        buckets.update { old ->
            val current = if (key in old) old else old.filterKeys { it in key-5..key }
            current + (key to change(current[key] ?: Bucket()))
        }
    }

    fun query(name: String, nanos: Long, rows: Long) = record { b ->
        val q = b.queries[name] ?: Query()
        b.copy(queries = b.queries + (name to Query(q.count + 1, q.nanos + nanos, q.rows + rows)))
    }

    fun invalidated(tables: Set<String>) = record { b ->
        b.copy(tables = b.tables + tables.associateWith { (b.tables[it] ?: 0) + 1 })
    }

    fun current() = buckets.value[now() / window] ?: Bucket()

    fun dump(): String = buildString {
        val end = now() / window
        val snapshot = buckets.value
        val format = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault())
        for (key in end-5..end) {
            appendLine(format.format(Instant.ofEpochMilli(key * window)))
            val bucket = snapshot[key]
            if (bucket == null) { appendLine("  暂无记录"); continue }
            bucket.queries.entries.sortedByDescending { it.value.nanos }.take(10).forEach { (name, q) ->
                appendLine("  $name  ${q.count} 次  ${q.nanos / 1_000_000} 毫秒  ${q.rows} 行")
            }
            appendLine("  失效通知 " + bucket.tables.entries.sortedByDescending { it.value }
                .joinToString { "${it.key}=${it.value}" }.ifEmpty { "无" })
        }
    }
}

/** 在 Room 真正 prepare 时归因。既不订阅业务 Flow，也不接触绑定值和结果列。 */
internal class QueryStatsDriver(
    private val delegate: SQLiteDriver, private val stats: DatabaseQueryStats,
) : SQLiteDriver by delegate {
    override fun open(fileName: String): SQLiteConnection {
        val connection = delegate.open(fileName)
        return object : SQLiteConnection by connection {
            override fun prepare(sql: String): SQLiteStatement {
                if (!sql.startsWith("SELECT", ignoreCase = true) && !sql.startsWith("WITH", ignoreCase = true))
                    return connection.prepare(sql)
                val name = source(sql) ?: return connection.prepare(sql)
                val begin = System.nanoTime()
                val stmt = connection.prepare(sql)
                return object : SQLiteStatement by stmt {
                    private var rows = 0L
                    private var started = begin
                    private var elapsed = 0L
                    override fun step(): Boolean {
                        if (started == 0L) started = System.nanoTime()
                        return stmt.step().also { if (it) rows++ }
                    }
                    // @Relation 会 reset 父游标，再执行子查询，最后二次遍历父游标。
                    override fun reset() {
                        stmt.reset()
                        if (started != 0L) elapsed += System.nanoTime() - started
                        started = 0L
                    }
                    override fun close() {
                        try { stmt.close() } finally {
                            if (started != 0L) elapsed += System.nanoTime() - started
                            stats.query(name, elapsed, rows)
                        }
                    }
                }
            }
        }
    }

    private fun source(sql: String): String? {
        // 唯一 SQL 的热点直接命名。相同 SQL 的 observe/all 等由真实 DAO 栈帧区分。
        if (sql == "SELECT * FROM import_records ORDER BY startAt, id") return "ImportRecordDao.observe"
        if (sql.startsWith("SELECT * FROM room_table_modification_log", true) ||
            sql.contains("FROM room_table_modification_log", true)) return null
        val frame = Throwable().stackTrace.firstOrNull {
            it.className.startsWith("app.resound.") && "_Impl" in it.className &&
                !it.className.contains("CaptureDatabase_Impl")
        } ?: return null
        val dao = frame.className.substringAfterLast('.').substringBefore("_Impl")
        val method = frame.methodName.substringBefore('$')
        val label = if (method.startsWith("__fetchRelationship")) "关联行" else method
        return "$dao.$label"
    }
}
