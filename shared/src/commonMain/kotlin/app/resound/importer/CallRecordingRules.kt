package app.resound.importer

import kotlinx.datetime.TimeZone

/*
 * 卡 199 通话录音：平台无关的规则。签名由 Claude 定，Codex 实现（把 TODO 换成实现，签名不改）。
 * iOS 没有系统通话录音文件夹，这里只放纯规则，安卓和以后的其他平台共用。
 */

/** 加文件夹时，文件夹里已有的录音怎么处理 */
enum class FolderHistory(val title: String) {
    /** 已有的不导入也不列出，只收之后新出现的 */
    OnlyNew("只收新的"),
    /** 最近 7 天的转写；更早的只列出来不转写 */
    Days7("最近 7 天"),
    /** 最近 30 天的转写；更早的只列出来不转写 */
    Days30("最近 30 天"),
    /** 全部只列出来，用到再转写 */
    ListAll("列出全部"),
}

enum class CallDirection { Incoming, Outgoing }

/** 开始时间是从哪来的（排查用，界面不显示） */
enum class CallTimeSource {
    /** 文件名里有完整日期和时间 */
    FileName,
    /** 文件名里只有几点几分，日期取自文件时间 */
    FileNameTimeOnly,
    /** 文件名里没有可信的时间，用文件时间（修改时间减时长，或文件里的录制时间） */
    FileClock,
    /** 都没有 */
    Unknown,
}

/** 从通话录音文件名里认出的信息；认不出的字段为 null */
data class CallFileInfo(
    /** 规范化后的号码：手机号去掉 +86、空格、横线；座机保留区号如 "02112345678"；服务号如 "10086" */
    val number: String?,
    /** 文件名里写的对方名字（不含号码、时间、固定字样）；没有为 null */
    val name: String?,
    val startAt: Long?,
    val direction: CallDirection?,
    val timeSource: CallTimeSource,
)

object CallFileNames {
    /**
     * 解析通话录音文件名（含扩展名，如 "张三(13800138000)_20261117153012.mp3"）。
     * [fileEndAt]：文件修改时间（约等于挂断时间）；[recordedAt]：文件元数据里的录制开始时间；[durationMillis]：录音时长。
     * 规则见任务卡 199“文件名解析”。永不抛异常，最差返回全 null + Unknown。
     */
    fun parse(fileName: String, fileEndAt: Long?, recordedAt: Long?, durationMillis: Long?, zone: TimeZone): CallFileInfo = CallFileParser.parse(fileName, fileEndAt, recordedAt, durationMillis, zone)

    /** 搜索和备注用的号码规范化（与 [CallFileInfo.number] 同一规则）；不像号码返回 null */
    fun normalizeNumber(raw: String): String? = CallFileParser.number(raw)
}

/** 某个品牌的通话录音设置指引 */
data class CallFolderGuide(
    /** 给人看的品牌名，如“小米 / 红米” */
    val brand: String,
    /** 打开自动通话录音的步骤，每步一句 */
    val steps: List<String>,
    /** 通话录音可能所在的位置（相对内部存储根目录，按可能性排序），用来让文件选择器直接打开到那里 */
    val folders: List<String>,
    /** 这个品牌/系统涛声收不到时为 false（如老一加放在 Android/data 下） */
    val supported: Boolean = true,
    /** 补充说明（一句话）；没有为 null */
    val note: String? = null,
)

object CallRecordingFolders {
    /** 按 Build.MANUFACTURER / Build.BRAND（大小写不限）给出指引；认不出的品牌给通用指引（folders 为空） */
    fun guide(manufacturer: String, brand: String): CallFolderGuide = CallFolderCatalog.guide(manufacturer, brand)

    /** 全部品牌的指引（设置说明页“其他品牌”展开用），通用指引放最后 */
    val all: List<CallFolderGuide> get() = CallFolderCatalog.all

    /** 这个路径（SAF 文档 id 里冒号后的部分，或文件路径）看起来是不是通话录音文件夹 */
    fun looksLikeCallFolder(path: String): Boolean = CallFolderCatalog.matches(path)
}
