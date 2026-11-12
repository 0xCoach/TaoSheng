package app.resound.digest

object DigestBatches {
        const val MAX_LINES = 600
        const val MAX_FORMAT_FAILURES = 3
        const val PROBE_SYSTEM = "你是格式检测助手，只输出 JSON。"
        const val PROBE_USER = "请原样返回这个 JSON：{\"ok\":true}"
        const val MAX_BATCH_CHARS = 40_000
        /** 每句除正文外的编号、时间、说话人（如“[123] 12:34:56 说话人1：”）约 20 字，留点余量 */
        const val LINE_OVERHEAD_CHARS = 30

        fun <T> batches(lines: List<T>, text: (T) -> String): List<List<T>> {
            val chunks = mutableListOf<MutableList<T>>()
            var chars = 0
            for (line in lines) {
                val size = text(line).length + LINE_OVERHEAD_CHARS
                if (size > MAX_BATCH_CHARS) throw DigestBatchLimitException()
                if (chunks.isEmpty() || chunks.last().size >= MAX_LINES || chars + size > MAX_BATCH_CHARS) {
                    chunks += mutableListOf<T>(); chars = 0
                }
                chunks.last() += line; chars += size
            }
            return chunks
        }

        /** 按时间间隔分段 */
        fun <T> sessions(lines: List<T>, gapMillis: Long, start: (T) -> Long, end: (T) -> Long): List<List<T>> {
            val out = mutableListOf<MutableList<T>>()
            for (line in lines) {
                val current = out.lastOrNull()
                if (current != null && start(line) - current.maxOf { end(it) } <= gapMillis) current += line
                else out += mutableListOf(line)
            }
            return out
        }
}
