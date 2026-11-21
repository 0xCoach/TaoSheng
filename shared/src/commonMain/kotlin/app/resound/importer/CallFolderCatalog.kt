package app.resound.importer

internal object CallFolderCatalog {
    private const val note = "没打过电话时文件夹可能还没有，先打一通测试电话。"
    private val huawei = listOf("打开“电话”，点右上角“⋮”→“设置”", "打开“通话自动录音”", "选择“所有通话”")
    private val color = listOf("打开“电话”，进入“设置”", "进入“通话录音”", "打开“自动录音”，选择“所有通话”")
    val all = listOf(
        CallFolderGuide("小米 / 红米", listOf("打开“电话”，点右上角或拨号盘上的“设置”", "进入“通话录音”", "打开“自动录音所有通话”"), listOf("MIUI/sound_recorder/call_rec"), note=note),
        CallFolderGuide("华为", huawei, listOf("Sounds/CallRecord", "record"), note=note),
        CallFolderGuide("荣耀", huawei, listOf("Sounds/CallRecord", "Recordings/CallRecord", "record"), note=note),
        CallFolderGuide("OPPO / 一加 / 真我", color, listOf("Music/Recordings/Call Recordings", "Recordings/Call Recordings", "Recordings"), note=note),
        CallFolderGuide("vivo / iQOO", color, listOf("Recordings/Record/Call", "Record/Call", "录音/通话录音"), note=note),
        CallFolderGuide("三星", listOf("打开“电话”，点右上角“⋮”→“设置”", "进入“录制通话”", "打开“自动录制通话”，选择“所有通话”"), listOf("Recordings/Call", "Call"), note=note),
        CallFolderGuide("通用", listOf("打开“电话”，进入“设置”", "找到“通话录音”或“录制通话”", "打开“自动录音所有通话”"), emptyList(), note=note),
    )
    fun guide(manufacturer: String, brand: String): CallFolderGuide {
        val names = setOf(manufacturer.lowercase(),brand.lowercase())
        val groups = listOf(setOf("xiaomi","redmi","poco"), setOf("huawei"), setOf("honor"), setOf("oppo","oneplus","realme"), setOf("vivo","iqoo"), setOf("samsung"))
        val index = groups.indexOfFirst { brand.lowercase() in it }.takeIf { it >= 0 } ?: groups.indexOfFirst { manufacturer.lowercase() in it }
        val found = index.takeIf { it >= 0 }?.let(all::get) ?: all.last()
        return if ("oneplus" in names) found.copy(note="老款一加的通话录音放在系统保护的位置，声声不息读不到；升级到 ColorOS 后可用。$note") else found
    }
    fun matches(path: String): Boolean {
        val p = path.lowercase().replace('\\','/').trimEnd('/')
        return all.flatMap { it.folders }.any { p.endsWith(it.lowercase()) } ||
            listOf("call_rec","callrecord","call recordings","通话录音").any(p::contains) || p.endsWith("/call")
    }
}
