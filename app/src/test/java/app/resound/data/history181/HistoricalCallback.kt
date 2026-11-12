package app.resound.data.history181

import android.database.sqlite.SQLiteDatabase

/** 只供合成历史库首次建库/打开使用；升级及再次打开走生产 Room 新驱动。 */
open class HistoricalCallback {
    open fun onCreate(db: SQLiteDatabase) = Unit
    open fun onOpen(db: SQLiteDatabase) = Unit
}
