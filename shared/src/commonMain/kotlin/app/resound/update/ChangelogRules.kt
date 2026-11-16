package app.resound.update

/**
 * 更新日志（打包进安装包的 CHANGELOG.md）的纯规则：拆成一版一段，
 * 决定更新后第一次打开要不要提示“这次更新了什么”。安卓、iOS 共用。
 */
object ChangelogRules {
    data class Entry(val version: String, val date: String?, val title: String, val body: String) {
        val preview get() = "-preview." in version
    }

    /** “## 0.4.2 · 2026-11-16” 起一段，到下一个“## ”为止；标题取正文第一行非空文字 */
    fun parse(markdown: String): List<Entry> {
        val out = mutableListOf<Entry>()
        var head: String? = null
        val body = StringBuilder()
        fun flush() {
            val h = head ?: return
            val (version, date) = h.split("·", limit = 2).map { it.trim() }.let { it[0] to it.getOrNull(1)?.takeIf(String::isNotBlank) }
            val text = body.toString().trim()
            val title = text.lineSequence().map { it.trim().trimStart('#', '-', '*', ' ') }.firstOrNull { it.isNotBlank() && !it.startsWith("【") }.orEmpty()
            if (version.isNotBlank()) out += Entry(version, date, title, text)
        }
        for (line in markdown.lineSequence()) {
            if (line.startsWith("## ")) { flush(); head = line.removePrefix("## "); body.clear() }
            else if (head != null) body.appendLine(line)
        }
        flush()
        return out
    }

    /** 给用户看的列表：只列正式版；当前装的是预览版时把它那一段也列上 */
    fun visible(entries: List<Entry>, current: String): List<Entry> =
        entries.filter { !it.preview || it.version == current }

    fun entry(entries: List<Entry>, version: String): Entry? = entries.firstOrNull { it.version == version }

    /**
     * 更新后第一次打开才提示：全新安装不提示；看过这一版不再提示；日志里没有这一版也不提示。
     * [lastSeen] 为 null 表示从没记过（老版本升级上来，或全新安装，由 [freshInstall] 区分）。
     */
    fun shouldShowWhatsNew(lastSeen: String?, current: String, freshInstall: Boolean, entries: List<Entry>): Boolean =
        !freshInstall && lastSeen != current && entry(entries, current) != null
}
