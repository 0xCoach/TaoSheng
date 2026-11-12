package app.resound.data

import androidx.room.RoomDatabase
import androidx.room.immediateTransaction
import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.SQLiteConnection
import androidx.room.PooledConnection
import kotlinx.coroutines.runBlocking

/** 参数从 1 开始绑定，读取列从 0 开始；语句只能在连接作用域内使用。 */
internal fun SQLiteStatement.bindArguments(arguments: Array<out Any?>) {
    arguments.forEachIndexed { index, value ->
        val parameter = index + 1
        when (value) {
            null -> bindNull(parameter)
            is ByteArray -> bindBlob(parameter, value)
            is String -> bindText(parameter, value)
            is Boolean -> bindLong(parameter, if (value) 1 else 0)
            is Float -> bindDouble(parameter, value.toDouble())
            is Double -> bindDouble(parameter, value)
            is Number -> bindLong(parameter, value.toLong())
            else -> error("不支持的数据库绑定类型：${value::class}")
        }
    }
}

internal val SQLiteStatement.columnNames: List<String>
    get() = (0 until getColumnCount()).map(::getColumnName)

internal suspend fun <T> RoomDatabase.readSql(
    sql: String, arguments: Array<out Any?> = emptyArray(), read: (SQLiteStatement) -> T,
): T = useReaderConnection { connection ->
    connection.usePrepared(sql) { statement -> statement.bindArguments(arguments); read(statement) }
}

/** 现有同步 IO 边界（问问、备份）的适配；事务内必须调用挂起 readSql，保留协程连接上下文。 */
internal fun <T> RoomDatabase.readSqlBlocking(
    sql: String, arguments: Array<out Any?> = emptyArray(), read: (SQLiteStatement) -> T,
): T = runBlocking { readSql(sql, arguments, read) }

internal suspend fun RoomDatabase.writeSql(sql: String, arguments: Array<out Any?> = emptyArray()) {
    useWriterConnection { connection ->
        connection.usePrepared(sql) { statement -> statement.bindArguments(arguments); statement.step() }
    }
}

internal suspend fun <T> RoomDatabase.writeTransaction(block: suspend () -> T): T =
    useWriterConnection { connection -> connection.immediateTransaction { block() } }

internal var SQLiteConnection.userVersion: Int
    get() = prepare("PRAGMA user_version").use { check(it.step()); it.getLong(0).toInt() }
    set(value) { prepare("PRAGMA user_version=$value").use { it.step() } }

internal suspend fun PooledConnection.executeSql(sql: String, arguments: Array<out Any?> = emptyArray()) {
    usePrepared(sql) { it.bindArguments(arguments); it.step() }
}

/** 检查点在 writer 上、事务外执行；不能把检查点放在复制主库/WAL 的事务内。 */
internal suspend fun RoomDatabase.checkpoint(mode: String) {
    require(mode == "FULL" || mode == "TRUNCATE")
    useWriterConnection { connection ->
        connection.usePrepared("PRAGMA wal_checkpoint($mode)") {
            check(it.step() && it.getLong(0) == 0L) { "checkpoint_busy" }
        }
    }
}
