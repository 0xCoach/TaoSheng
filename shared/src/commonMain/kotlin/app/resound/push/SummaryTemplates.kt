package app.resound.push

object SummaryTemplates {
    fun merge(fresh: Map<String, String>, templates: Map<String, String>, oldCounts: Map<String, Int>, counts: Map<String, Int>): Map<String, String> {
        val result = templates.toMutableMap()
        fun section(text: String, name: String): String = text.substringAfter("<!-- resound:$name:start -->", "").substringBefore("<!-- resound:$name:end -->")
        fresh.filterKeys { it.startsWith("daily/") || it.startsWith("weekly/") }.forEach { (path, content) ->
            var merged = result[path]
            if (merged != null && path.startsWith("daily/")) {
                for (name in listOf("reviews", "cards")) {
                    val start = "<!-- resound:$name:start -->"; val end = "<!-- resound:$name:end -->"
                    merged = merged!!.substringBefore(start) + start + section(content, name) + end + merged.substringAfter(end, "")
                }
                val date = path.substringAfterLast('/').take(10)
                val count = Regex("(?m)^条数: ([0-9]+)$").find(merged!!)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val oldSummaryCount = oldCounts[path] ?: 0
                val summaryCount = counts[date] ?: 0
                result[path] = merged.replace(Regex("(?m)^条数: [0-9]+$"), "条数: ${(count - oldSummaryCount + summaryCount).coerceAtLeast(0)}")
            } else result[path] = content
        }
        if ("README.md" !in result) result["README.md"] = fresh.getValue("README.md")
        return result
    }
}
