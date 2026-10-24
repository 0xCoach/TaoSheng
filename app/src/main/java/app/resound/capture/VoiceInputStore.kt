package app.resound.capture

import androidx.room.withTransaction
import app.resound.data.*
import app.resound.transcribe.VoiceInputMetadata

/** 和原话共用同一事务/搜索索引/备份链路。无音频路径只用作幂等键，永不创建文件。 */
internal class VoiceInputStore(private val db: CaptureDatabase) {
    suspend fun save(record: InputRecord): Long? = db.withTransaction {
        val dao = db.voiceInput()
        val path = "voice-input:${record.token}"
        dao.byToken(path)?.let { return@withTransaction it.id }
        if (dao.nearby(record.at).any { VoiceInputText.similar(record.text, it.text) }) return@withTransaction null
        val recent = dao.recent(record.at).mapNotNull { row ->
            VoiceInputMetadata.read(row.voiceInputJson)?.let { row to it }
        }.filter { it.second.packageName == record.meta.packageName }
        // 原话只比较同应用上一条；上下文累计比较同聊天的已存行，避免第三段重新带回第一段。
        if (recent.firstOrNull()?.first?.text?.let { VoiceInputDedup.key(it) == VoiceInputDedup.key(record.text) } == true)
            return@withTransaction null
        // 中间停顿被拆成两次语音输入时，后一次是“前一句 + 接着说的”：更新上一条，不另存
        recent.firstOrNull()?.first?.takeIf { record.at - it.startAt <= 120_000 && VoiceInputDedup.continues(it.text, record.text) }?.let { prev ->
            if (dao.extend(prev.id, record.text) > 0) return@withTransaction prev.id
        }
        val sameChat = recent.filter { VoiceInputDedup.title(it.second.title) == VoiceInputDedup.title(record.meta.title) }
        val known = sameChat.flatMap { (row, meta) ->
            meta.before.orEmpty().lines() + row.text.lines() + row.text
        }.map(VoiceInputDedup::key).filter(String::isNotEmpty).toSet()
        val before = VoiceInputText.context(record.meta.before.orEmpty().lines().filter { VoiceInputDedup.key(it) !in known })
        val meta = record.meta.copy(before = before, after = null)
        val segment = dao.segment(SegmentEntity(startAt = record.at, endAt = record.at + 1,
            sourceId = "voice-input", path = path, sizeBytes = 0, status = "Done",
            completedAt = record.at, voiceprintProcessedAt = record.at))
        dao.line(TranscriptLineEntity(segmentId = segment, startAt = record.at, endAt = record.at + 1,
            text = record.text, speakerTag = null, speakerName = "我", speakerPersonId = 0,
            speakerManual = true, voiceInputJson = VoiceInputMetadata.json(meta).toString(),
            voiceInputPendingUntil = null))
    }
    suspend fun finish(token: String, after: String?) = db.withTransaction {
        val row = db.voiceInput().byToken("voice-input:$token") ?: return@withTransaction
        val meta = VoiceInputMetadata.read(row.voiceInputJson) ?: return@withTransaction
        db.voiceInput().finish(row.id, VoiceInputMetadata.json(meta.copy(after = after)).toString())
    }
}

/** 只统一 Unicode 形式和空白；保留标点、大小写和运算符，不合并相近含义。 */
internal object VoiceInputDedup {
    fun key(text: String) = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC)
        .trim().replace(Regex("\\s+"), " ")
    fun title(text: String?) = text?.let(::key)?.ifBlank { null }
    /** next 是不是在 prev 后面接着说的（识字可能把前半句个别字认得不一样，前半段相似也算） */
    fun continues(prev: String, next: String): Boolean {
        val a = VoiceInputText.normalized(prev); val b = VoiceInputText.normalized(next)
        if (a.isEmpty() || b.length <= a.length) return false
        return b.startsWith(a) || VoiceInputText.similar(a, b.take(a.length))
    }
}
