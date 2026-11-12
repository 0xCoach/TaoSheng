package app.resound.data

import androidx.room.RoomDatabase
import androidx.sqlite.*
import kotlinx.coroutines.runBlocking

/** 既有同步测试的断言适配器。所有 SQL 都走 Room 的新驱动连接；结果在作用域内读完。 */
internal val RoomDatabase.testSql: Driver175TestSql get() = Driver175TestSql(this)

internal class Driver175TestSql(private val database: RoomDatabase) {
    val version: Int get() = query("PRAGMA user_version").use { check(it.moveToFirst()); it.getInt(0) }
    val path: String get() = query("PRAGMA database_list").use { check(it.moveToFirst()); it.getString(2) }
    fun execSQL(sql: String, arguments: Array<out Any?> = emptyArray()) =
        runBlocking { database.writeSql(sql, arguments) }
    fun query(sql: String, arguments: Array<out Any?>? = null): Driver175TestRows =
        database.readSqlBlocking(sql, arguments ?: emptyArray()) { statement ->
            val columns = statement.columnNames
            val rows = buildList {
                while (statement.step()) add((columns.indices).map { index ->
                    when (statement.getColumnType(index)) {
                        SQLITE_DATA_NULL -> null
                        SQLITE_DATA_INTEGER -> statement.getLong(index)
                        SQLITE_DATA_FLOAT -> statement.getDouble(index)
                        SQLITE_DATA_BLOB -> statement.getBlob(index)
                        else -> statement.getText(index)
                    }
                })
            }
            Driver175TestRows(columns, rows)
        }
}

internal class Driver175TestRows(val columnNames: List<String>, private val rows: List<List<Any?>>) : AutoCloseable {
    private var position = -1
    val count get() = rows.size
    val columnCount get() = columnNames.size
    fun moveToFirst(): Boolean { position = 0; return rows.isNotEmpty() }
    fun moveToNext(): Boolean { position++; return position < rows.size }
    fun isNull(index: Int) = rows[position][index] == null
    fun getLong(index: Int) = (rows[position][index] as? Number)?.toLong() ?: 0L
    fun getInt(index: Int) = getLong(index).toInt()
    fun getDouble(index: Int) = (rows[position][index] as? Number)?.toDouble() ?: 0.0
    fun getString(index: Int) = checkNotNull(rows[position][index]).toString()
    fun getBlob(index: Int) = rows[position][index] as ByteArray
    fun getColumnIndexOrThrow(name: String) = columnNames.indexOf(name).also { require(it >= 0) { name } }
    override fun close() = Unit
}
