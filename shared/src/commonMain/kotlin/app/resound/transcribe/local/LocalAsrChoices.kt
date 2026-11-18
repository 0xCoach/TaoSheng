package app.resound.transcribe.local

enum class LocalBackend(val title: String) { Npu("NPU 识别"), Cpu("CPU 识别") }

enum class LocalAsrLanguage(val code: String, val title: String) {
    Auto("auto", "自动识别"), Chinese("zh", "中文（普通话）"), Cantonese("yue", "粤语"),
    English("en", "英语"), Japanese("ja", "日语"), Korean("ko", "韩语");

    companion object {
        fun fromCode(code: String?) = entries.firstOrNull { it.code == code } ?: Auto

        /**
         * 按 BCP-47 语言标签把“自动识别”解析成具体语言；映射不到返回 null（调用方退回模型自检）。
         * zh-HK/zh-MO 及 Hant-HK/Hant-MO 按粤语处理，其余中文按普通话。
         */
        fun fromLocaleTag(tag: String): LocalAsrLanguage? {
            val t = tag.lowercase().replace('_', '-')
            if (t.startsWith("yue")) return Cantonese
            if (t.startsWith("zh")) {
                val region = t.split('-').drop(1).firstOrNull { it.length == 2 || it == "hant" }
                return if (region in setOf("hk", "mo") || t.contains("hant-hk") || t.contains("hant-mo")) Cantonese else Chinese
            }
            if (t.startsWith("ja")) return Japanese
            if (t.startsWith("ko")) return Korean
            if (t.startsWith("en")) return English
            return null
        }
    }
}
