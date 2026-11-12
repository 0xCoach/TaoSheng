package app.resound.privacy

/*
 * 隐私开关与界面之间的约定。界面（Claude）只依赖这里；底层（Codex）实现。
 * 删除都是彻底删除：录音文件、原话、由它们整理出的事件和条目一起删，备份里下次也不再有。
 */

/** 按时间范围删除前的预估：多少段录音、共多长 */
data class ForgetPreview(val segments: Int, val audioMillis: Long)

/** 删除的结果，给界面提示用 */
data class ForgetResult(val segments: Int, val lines: Int, val events: Int, val items: Int)

interface PrivacyActions {
    /**
     * 删除最近 minutes 分钟（按录音开始时间）的录音和原话。
     * 事件：全部原话都在这段时间内的整件删除；跨过这段时间的只删这些原话，事件保留。
     * 这段时间内提取出、且依据的原话被删掉的条目一起删除。正在录的那一段也立即结束并删除。
     */
    suspend fun deleteRecent(minutes: Int): ForgetResult

    /** 按时间范围（录音开始时刻在 [fromMillis, untilMillis] 内）彻底删除，规则同 [deleteRecent]；范围含现在时正在录的那段也结束并删除 */
    suspend fun deleteRange(fromMillis: Long, untilMillis: Long): ForgetResult
    suspend fun previewRange(fromMillis: Long, untilMillis: Long): ForgetPreview

    /** “这段别记”：彻底删除一件事，连同它的原话、录音（若还在）和条目 */
    suspend fun forgetEvent(eventId: Long): ForgetResult

    /** 彻底删除一段录音及其原话；原话所在事件若因此空了，事件也删除 */
    suspend fun forgetSegment(segmentId: Long): ForgetResult
}
