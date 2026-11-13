package app.resound.location

import androidx.room.*
import androidx.sqlite.execSQL
import kotlinx.coroutines.flow.Flow

/** 一个定位点；只存手机本地。 */
@Entity(tableName = "location_points", indices = [Index("at")])
data class LocationPoint(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    val lat: Double,
    val lng: Double,
    val accuracy: Float,
    val provider: String,
)

@Dao
interface LocationDao {
    @Insert suspend fun insert(point: LocationPoint): Long
    @Query("SELECT * FROM location_points WHERE at >= :from AND at < :to ORDER BY at")
    fun between(from: Long, to: Long): Flow<List<LocationPoint>>
    @Query("SELECT * FROM location_points WHERE at >= :from AND at < :to ORDER BY at")
    suspend fun betweenNow(from: Long, to: Long): List<LocationPoint>
    @Query("SELECT * FROM location_points ORDER BY at DESC LIMIT 1")
    suspend fun latest(): LocationPoint?
    @Query("SELECT * FROM location_points ORDER BY at DESC LIMIT 1")
    fun latestFlow(): Flow<LocationPoint?>
    @Query("DELETE FROM location_points WHERE at >= :from AND at < :to")
    suspend fun deleteBetween(from: Long, to: Long): Int
}

object LocationSchema {
    fun create(db: androidx.sqlite.SQLiteConnection) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `location_points` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `at` INTEGER NOT NULL, `lat` REAL NOT NULL, `lng` REAL NOT NULL, `accuracy` REAL NOT NULL, `provider` TEXT NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_location_points_at` ON `location_points` (`at`)")
    }
}
