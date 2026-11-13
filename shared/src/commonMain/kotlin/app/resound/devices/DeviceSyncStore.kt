package app.resound.devices

import androidx.room.*

@Entity(tableName = "device_transfers")
data class DeviceTransfer(
    @PrimaryKey val key: String, val device: String, val fileId: String, val name: String,
    val size: Long?, val startedAt: Long?, val duration: Long?, val format: String,
    val fromMillis: Long, val toMillis: Long, val phoneStart: Long,
    val received: Long = 0, val imported: Boolean = false, val deleted: Boolean = false,
)

@Dao
interface DeviceSyncStore {
    @Query("SELECT * FROM device_transfers WHERE device = :device")
    suspend fun rows(device: String): List<DeviceTransfer>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(row: DeviceTransfer)
}
