package app.resound.data

import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteStatement
import kotlinx.coroutines.runBlocking

/** 现有同步 IO 边界（问问、备份）的适配；事务内必须调用挂起 readSql，保留协程连接上下文。 */
internal fun <T> RoomDatabase.readSqlBlocking(
    sql: String, arguments: Array<out Any?> = emptyArray(), read: (SQLiteStatement) -> T,
): T = runBlocking { readSql(sql, arguments, read) }
