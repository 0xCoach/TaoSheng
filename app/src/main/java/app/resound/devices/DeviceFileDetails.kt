package app.resound.devices



/** 约定中尚未包含的元数据。修改时间必须是手机时间；校验算法由插件明确提供。 */
internal interface DeviceFileDetails {
    fun modifiedAt(file: DeviceFile): Long? = null
    fun checksumAlgorithm(file: DeviceFile): String? = null
    fun validSize(file: DeviceFile, local: java.io.File): Boolean = file.bytes == local.length()
}
