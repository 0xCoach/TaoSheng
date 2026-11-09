package app.resound.devices

import androidx.room.*

@Entity(tableName = "device_transfers")
internal data class DeviceTransfer(
    @PrimaryKey val key: String, val device: String, val fileId: String, val name: String,
    val size: Long?, val startedAt: Long?, val duration: Long?, val format: String,
    val fromMillis: Long, val toMillis: Long, val phoneStart: Long,
    val received: Long = 0, val imported: Boolean = false, val deleted: Boolean = false,
)

@Dao
internal interface DeviceSyncStore {
    @Query("SELECT * FROM device_transfers WHERE device = :device")
    suspend fun rows(device: String): List<DeviceTransfer>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(row: DeviceTransfer)
}

/** 约定中尚未包含的元数据。修改时间必须是手机时间；校验算法由插件明确提供。 */
internal interface DeviceFileDetails {
    fun modifiedAt(file: DeviceFile): Long? = null
    fun checksumAlgorithm(file: DeviceFile): String? = null
    fun validSize(file: DeviceFile, local: java.io.File): Boolean = file.bytes == local.length()
}
