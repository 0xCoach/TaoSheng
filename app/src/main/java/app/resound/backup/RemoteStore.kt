package app.resound.backup

import java.io.File
import java.time.LocalDate

/** 云端备份统一入口；目录结构、历史恢复点格式由上层保持不变。 */
internal interface RemoteStore {
    fun metadata(name: String, bytes: Long = 0): RemoteBackup? = BackupNames.metadata(name, bytes)
    fun ensureDirectory()
    fun backups(): List<RemoteBackup>
    fun list(): List<String> = backups().asReversed().map { it.name }
    fun upload(file: File, name: String)
    fun download(name: String, destination: File)
    fun delete(name: String)
    fun retain(count: Int): Int
    fun volumes(): VolumeStore
    fun uploadDiary(date: LocalDate, markdown: String)
    fun testWrite()
}
