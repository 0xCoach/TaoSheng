package app.resound.platform

import android.content.SharedPreferences
import android.util.Log
import java.io.File
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object SystemNow : NowMillis {
    override fun now(): Long = System.currentTimeMillis()
}

object SystemZone : ZoneProvider {
    override fun zoneId(): String = ZoneId.systemDefault().id
}

object AndroidLogger : Logger {
    override fun info(tag: String, message: String) { Log.i(tag, message) }
    override fun warn(tag: String, message: String, error: Throwable?) { Log.w(tag, message, error) }
}

class PrefsKeyValueStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putString(key: String, value: String?) { prefs.edit().putString(key, value).apply() }
    override fun getLong(key: String, default: Long): Long = prefs.getLong(key, default)
    override fun putLong(key: String, value: Long) { prefs.edit().putLong(key, value).apply() }
    override fun getBoolean(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)
    override fun putBoolean(key: String, value: Boolean) { prefs.edit().putBoolean(key, value).apply() }
}

/** id 必须为绝对路径；不创建父目录，不吞掉读写异常，不递归删除。 */
class LocalFileStore(private val dispatchers: AppDispatchers) : FileStore {
    private fun file(ref: FileRef): File = File(ref.id).also { require(it.isAbsolute) { "文件引用须为绝对路径" } }
    override suspend fun exists(ref: FileRef): Boolean = withContext(dispatchers.io) { file(ref).exists() }
    override suspend fun size(ref: FileRef): Long = withContext(dispatchers.io) { file(ref).length() }
    override suspend fun readBytes(ref: FileRef): ByteArray = withContext(dispatchers.io) { file(ref).readBytes() }
    override suspend fun writeBytes(ref: FileRef, bytes: ByteArray) = withContext(dispatchers.io) { file(ref).writeBytes(bytes) }
    override suspend fun delete(ref: FileRef): Boolean = withContext(dispatchers.io) { file(ref).delete() }
}

object AndroidDispatchers : AppDispatchers {
    override val io = Dispatchers.IO
    override val default = Dispatchers.Default
}

/** 安卓装配容器，仅暴露共用插口。 */
data class PlatformServices(
    val now: NowMillis,
    val zone: ZoneProvider,
    val logger: Logger,
    val keyValueStore: KeyValueStore,
    val files: FileStore,
    val dispatchers: AppDispatchers,
)
