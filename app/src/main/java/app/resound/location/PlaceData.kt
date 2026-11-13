package app.resound.location

import androidx.room.*
import androidx.sqlite.execSQL

/** 一个约 100 米格子的地名缓存；同一处只查一次。 */
/** 自动查到的按格子缓存；手动改的 source=手动，带坐标，方圆 150 米内都用它。 */
@Entity(tableName = "location_places")
data class PlaceRow(
    @PrimaryKey val cell: String,
    val name: String,
    val address: String,
    val source: String,
    val at: Long,
    val lat: Double? = null,
    val lng: Double? = null,
    @androidx.room.ColumnInfo(defaultValue = "0") val home: Boolean = false,
)

@Dao
interface PlaceDao {
    @Query("SELECT * FROM location_places WHERE cell = :cell") suspend fun get(cell: String): PlaceRow?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(row: PlaceRow)
    /** 只清自动查到的；手动改的保留。 */
    @Query("DELETE FROM location_places WHERE source != '手动'") suspend fun clear()
    @Query("SELECT * FROM location_places WHERE source = '手动' ORDER BY at DESC") fun manual(): kotlinx.coroutines.flow.Flow<List<PlaceRow>>
    @Query("SELECT * FROM location_places WHERE source = '手动'") suspend fun manualNow(): List<PlaceRow>
    @Query("UPDATE location_places SET home = 0 WHERE home = 1") suspend fun clearHome()
    @Query("DELETE FROM location_places WHERE cell = :cell") suspend fun delete(cell: String)
}

object PlaceSchema {
    /** 地名缓存表；addManual 再加坐标和“家”。 */
    fun create(db: androidx.sqlite.SQLiteConnection) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `location_places` (`cell` TEXT NOT NULL, `name` TEXT NOT NULL, `address` TEXT NOT NULL, `source` TEXT NOT NULL, `at` INTEGER NOT NULL, PRIMARY KEY(`cell`))")
    }
    fun addManual(db: androidx.sqlite.SQLiteConnection) {
        db.execSQL("ALTER TABLE location_places ADD COLUMN lat REAL")
        db.execSQL("ALTER TABLE location_places ADD COLUMN lng REAL")
        db.execSQL("ALTER TABLE location_places ADD COLUMN home INTEGER NOT NULL DEFAULT 0")
    }
}
