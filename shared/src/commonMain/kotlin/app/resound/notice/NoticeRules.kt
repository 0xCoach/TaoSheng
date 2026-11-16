package app.resound.notice

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 远程通知（不升级也能触达用户）：魔搭 / GitHub 上放一个签名的 notices.json，
 * App 定时下载、验签后按规则在首页显示。只是下载公开文件，不上传任何东西、不带用户编号。
 * 这里是安卓、iOS 共用的纯规则：解析、筛选该给谁看、图片校验。
 */
@Serializable
data class NoticeFile(
    /** 固定为 "notices"：和外设说明卡用同一把签名钥匙，靠它区分，说明卡文件不能冒充通知 */
    val kind: String,
    /** 只增不减；App 只接受不比本机缓存旧的版本（防止回退到旧内容） */
    val version: Int,
    val issuedAt: String,
    val notices: List<Notice> = emptyList(),
)

@Serializable
data class Notice(
    /** 唯一编号；用户关掉后按它记住，不再显示 */
    val id: String,
    val title: String,
    val body: String = "",
    /** 图片（如二维码），和 notices.json 放在同一目录；sha256 写在签名文件里，下载后必须一致才显示 */
    val image: NoticeImage? = null,
    /** 按钮：点了用浏览器打开 */
    val link: NoticeLink? = null,
    /** 毫秒；不填表示马上 / 一直 */
    val startAt: Long? = null,
    val endAt: Long? = null,
    /** 只给这些版本号（versionCode）范围内的看 */
    val minVersionCode: Long? = null,
    val maxVersionCode: Long? = null,
    /** 只给这些品牌看（小写，如 huawei、xiaomi）；空表示所有手机 */
    val brands: List<String> = emptyList(),
    /** false 时没有“知道了”，到期自动消失（用于重要提醒） */
    val dismissible: Boolean = true,
    /** 越大越靠前 */
    val priority: Int = 0,
)

@Serializable
data class NoticeImage(
    val file: String,
    val sha256: String,
    /** 图片下方的小字，如“保存到相册后用微信扫一扫” */
    val caption: String? = null,
    /** 显示宽高比（宽/高）；不填按正方形 */
    val ratio: Float? = null,
)

@Serializable
data class NoticeLink(val label: String, val url: String)

object NoticeRules {
    const val MAX_FILE = 128 * 1024
    const val MAX_IMAGE = 1024 * 1024
    const val MAX_NOTICES = 20
    /** 首页最多同时显示几条 */
    const val MAX_SHOWN = 2

    private val json = Json { ignoreUnknownKeys = false }
    private val fileName = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}\\.(png|jpg|jpeg|webp)")
    private val hex = Regex("[0-9a-f]{64}")

    /** 严格解析：字段不认识、编号重复、图片名不安全、链接不是 https 都整份拒绝 */
    fun parse(bytes: ByteArray): NoticeFile {
        require(bytes.size <= MAX_FILE) { "too_large" }
        val file = json.decodeFromString(NoticeFile.serializer(), bytes.decodeToString())
        require(file.kind == "notices") { "wrong_kind" }
        require(file.version > 0) { "bad_version" }
        require(file.notices.size <= MAX_NOTICES) { "too_many" }
        require(file.notices.map { it.id }.distinct().size == file.notices.size) { "duplicate_id" }
        file.notices.forEach { n ->
            require(n.id.isNotBlank() && n.id.length <= 64 && n.title.isNotBlank() && n.title.length <= 60 && n.body.length <= 1000) { "bad_notice" }
            n.image?.let { require(fileName.matches(it.file) && hex.matches(it.sha256)) { "bad_image" } }
            n.link?.let { require(it.url.startsWith("https://") && it.label.isNotBlank() && it.label.length <= 12) { "bad_link" } }
        }
        return file
    }

    /** 新文件能不能替换本机缓存：版本更高；同版本只接受完全相同的内容 */
    fun accepts(cachedVersion: Int?, cachedBytes: ByteArray?, incoming: NoticeFile, incomingBytes: ByteArray): Boolean = when {
        cachedVersion == null -> true
        incoming.version > cachedVersion -> true
        incoming.version == cachedVersion -> cachedBytes?.contentEquals(incomingBytes) == true
        else -> false
    }

    /** 该给这台手机看的：在时间范围内、版本和品牌对得上、没被关掉；按优先级排，最多 [MAX_SHOWN] 条 */
    fun visible(file: NoticeFile?, now: Long, versionCode: Long, brand: String, dismissed: Set<String>): List<Notice> =
        file?.notices.orEmpty().filter { n ->
            (n.startAt == null || now >= n.startAt) && (n.endAt == null || now < n.endAt) &&
                (n.minVersionCode == null || versionCode >= n.minVersionCode) &&
                (n.maxVersionCode == null || versionCode <= n.maxVersionCode) &&
                (n.brands.isEmpty() || brand.lowercase() in n.brands.map { it.lowercase() }) &&
                !(n.dismissible && n.id in dismissed)
        }.sortedWith(compareByDescending<Notice> { it.priority }.thenByDescending { it.startAt ?: 0L }).take(MAX_SHOWN)
}
