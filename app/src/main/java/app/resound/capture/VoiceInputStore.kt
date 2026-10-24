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
        val segment = dao.segment(SegmentEntity(startAt = record.at, endAt = record.at + 1,
            sourceId = "voice-input", path = path, sizeBytes = 0, status = "Done",
            completedAt = record.at, voiceprintProcessedAt = record.at))
        dao.line(TranscriptLineEntity(segmentId = segment, startAt = record.at, endAt = record.at + 1,
            text = record.text, speakerTag = null, speakerName = "我", speakerPersonId = 0,
            speakerManual = true, voiceInputJson = VoiceInputMetadata.json(record.meta).toString(),
            voiceInputPendingUntil = record.replyUntil))
    }
    suspend fun finish(token: String, after: String?) = db.withTransaction {
        val row = db.voiceInput().byToken("voice-input:$token") ?: return@withTransaction
        val meta = VoiceInputMetadata.read(row.voiceInputJson) ?: return@withTransaction
        db.voiceInput().finish(row.id, VoiceInputMetadata.json(meta.copy(after = after)).toString())
    }
}
