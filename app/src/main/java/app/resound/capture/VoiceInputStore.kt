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
        // 原话只比较同应用上一条；上下文累计比较同聊天的已存上下文。
        if (recent.firstOrNull()?.first?.text?.let { VoiceInputDedup.key(it) == VoiceInputDedup.key(record.text) } == true)
            return@withTransaction null
        // 中间停顿被拆成两次语音输入时，后一次是“前一句 + 接着说的”：更新上一条，不另存
        recent.firstOrNull()?.first?.takeIf { record.at - it.startAt <= 120_000 && VoiceInputDedup.continues(it.text, record.text) }?.let { prev ->
            if (dao.extend(prev.id, record.text) > 0) return@withTransaction prev.id
        }
        val sameChat = recent.filter { VoiceInputDedup.title(it.second.title) == VoiceInputDedup.title(record.meta.title) }
        // 只用此前保存的上下文去重；单独采集的语音原话不能充当“已存上下文”。
        val known = sameChat.flatMap { (_, meta) -> meta.before.orEmpty().lines() }
            .filter(String::isNotBlank).map(VoiceInputDedup::contextKey).toSet()
        val before = VoiceInputText.context(record.meta.before.orEmpty().lines().filter { line ->
            val (speaker, text) = VoiceInputDedup.contextKey(line)
            known.none { (oldSpeaker, oldText) ->
                speaker == oldSpeaker && (text == oldText ||
                    (oldText.length >= 6 && VoiceInputDedup.sameAs(oldText, text)))
            }
        })
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
    /** 说话人与正文分开比较，双方说相同内容不互相去重；旧的无前缀记录保持独立。 */
    fun contextKey(line: String): Pair<String, String> {
        val split = line.indexOf('：')
        return if (split in 1..80) key(line.substring(0, split)) to key(line.substring(split + 1))
        else "" to key(line)
    }
    /** 两段是不是同一句（识字误差内）：按顺序对得上的字占较长一段的 85% 以上 */
    fun sameAs(a: String, b: String): Boolean {
        val x = VoiceInputText.normalized(a); val y = VoiceInputText.normalized(b)
        if (x.isEmpty() || y.isEmpty()) return false
        return lcs(x, y) >= maxOf(x.length, y.length) * 0.85
    }
    /** next 是不是在 prev 后面接着说的（还没发送、输入框越说越长）：prev 的字按顺序大部分出现在 next 开头一段里 */
    fun continues(prev: String, next: String): Boolean {
        val a = VoiceInputText.normalized(prev); val b = VoiceInputText.normalized(next)
        if (a.isEmpty() || b.length <= a.length) return false
        if (b.startsWith(a)) return true
        return a.length >= 4 && lcs(a, b.take(a.length + a.length / 4 + 2)) >= a.length * 0.75
    }
    private fun lcs(x: String, y: String): Int {
        if (x.length > 3000 || y.length > 3000) return 0
        var prev = IntArray(y.length + 1)
        for (i in x.indices) {
            val cur = IntArray(y.length + 1)
            for (j in y.indices) cur[j + 1] = if (x[i] == y[j]) prev[j] + 1 else maxOf(prev[j + 1], cur[j])
            prev = cur
        }
        return prev[y.length]
    }
}
