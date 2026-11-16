package app.resound.screentime

/** 标题与 App 名脱敏（设计 eng「标题脱敏」）：换行类字符与【】｜《》换成空格，再按码点截断。 */
object ScreenTimeText {
    /** 回车、换行、制表符与【】｜《》各换成一个空格，去首尾空白；按 Unicode 码点截到
     *  [maxCodePoints]（代理对算一个、不拆开），被截断时末尾加「…」（不计入上限）。 */
    fun clean(value: String, maxCodePoints: Int): String {
        val flat = buildString {
            for (c in value) append(
                when (c) {
                    '\r', '\n', '\t', '【', '】', '｜', '《', '》' -> ' '
                    else -> c
                },
            )
        }.trim()
        var end = 0
        var points = 0
        while (end < flat.length && points < maxCodePoints) {
            val high = flat[end].code in 0xD800..0xDBFF
            val nextLow = end + 1 < flat.length && flat[end + 1].code in 0xDC00..0xDFFF
            end += if (high && nextLow) 2 else 1
            points++
        }
        return if (end < flat.length) flat.substring(0, end) + "…" else flat
    }
}
