package app.resound.platform

/** 当前时刻（毫秒）。业务规则从这里取时间。 */
fun interface NowMillis { fun now(): Long }

/** 当前时区 ID，如 Asia/Shanghai。 */
fun interface ZoneProvider { fun zoneId(): String }

/** 不得记录密钥、原话全文。 */
interface Logger {
    fun info(tag: String, message: String)
    fun warn(tag: String, message: String, error: Throwable? = null)
}

/** 简单键值存储；putString 的 null 表示删除。 */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
    fun getLong(key: String, default: Long): Long
    fun putLong(key: String, value: Long)
    fun getBoolean(key: String, default: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
}

fun interface KeyValueStores {
    fun open(group: String): GroupedKeyValueStore
}

interface GroupedKeyValueStore {
    fun contains(key: String): Boolean
    fun getString(key: String, default: String?): String?
    fun getStrings(key: String): List<String>
    fun getLong(key: String, default: Long): Long
    fun getInt(key: String, default: Int): Int
    fun getBoolean(key: String, default: Boolean): Boolean

    /** 同组原子提交。编辑回调抛错或返回提交失败时，保留原值。 */
    fun commit(edit: KeyValueEdit.() -> Unit): Boolean
}

interface KeyValueEdit {
    fun putString(key: String, value: String?)
    fun putStrings(key: String, value: List<String>)
    fun putLong(key: String, value: Long)
    fun putInt(key: String, value: Int)
    fun putBoolean(key: String, value: Boolean)
}

/** 缺少密钥时返回空串。平台不可把密钥或密文写进日志和异常。 */
interface SecretVault {
    fun read(group: String, key: String): String
    fun write(group: String, key: String, value: String)
    fun remove(group: String, key: String)
    fun has(group: String, key: String): Boolean
}

/** 不透明编号或路径字符串，由平台解释。 */
data class FileRef(val id: String)

/** 文件读写插口。size 单位为字节；具体路径和 I/O 由平台处理。 */
interface FileStore {
    suspend fun exists(ref: FileRef): Boolean
    suspend fun size(ref: FileRef): Long
    suspend fun readBytes(ref: FileRef): ByteArray
    suspend fun writeBytes(ref: FileRef, bytes: ByteArray)
    suspend fun delete(ref: FileRef): Boolean
}

/** 业务规则的后台调度器，由装配层注入。 */
interface AppDispatchers {
    val io: kotlinx.coroutines.CoroutineDispatcher
    val default: kotlinx.coroutines.CoroutineDispatcher
}
