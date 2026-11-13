package app.resound.data

suspend fun TranscribeDao.deleteFailed(id: Long): Boolean = transaction {
        val row = findSegment(id) ?: return@transaction false
        if (row.status !in setOf("Failed", "Quiet", "Abandoned")) return@transaction false
        val file = java.io.File(row.path)
        if (file.exists() && !file.delete()) return@transaction false
        deleteSamples(id)
        deleteExclusions(id)
        deleteAttempts(id)
        deleteLineItems(id)
        deleteExclusiveEvents(id)
        deleteSegmentRow(id) // 原话由外键级联删除；累计用量不回退。
        true
    }

suspend fun TranscribeDao.retryFailed() = retryFailed(System.currentTimeMillis())
suspend fun TranscribeDao.ignoreFailed() = ignoreFailed(System.currentTimeMillis())

suspend fun TranscribeDao.completeRedo(row: SegmentEntity, fresh: List<TranscriptLineEntity>, at: Long) = transaction {
        val old = linesForRedo(row.id)
        val used = mutableSetOf<Long>()
        val mapped = fresh.map { line ->
            val match = old.filter { it.id !in used }.maxByOrNull {
                (minOf(it.endAt, line.endAt) - maxOf(it.startAt, line.startAt)).coerceAtLeast(0)
            }
            match?.let { used += it.id }
            line.copy(id = match?.id ?: 0, eventId = match?.eventId, digestedAt = match?.digestedAt ?: at)
        }
        complete(row.copy(redoPending = false), mapped)
        val saved = linesForRedo(row.id)
        for (line in old) if (saved.none { it.id == line.id }) {
            val replacement = saved.maxByOrNull { (minOf(it.endAt, line.endAt) - maxOf(it.startAt, line.startAt)).coerceAtLeast(0) }?.id
            remapItemLine(line.id, replacement); remapHintLine(line.id, replacement)
        }
    }

suspend fun TranscribeDao.complete(row: SegmentEntity, lines: List<TranscriptLineEntity>) = transaction {
        if (findSegment(row.id) == null) return@transaction
        deleteLines(row.id)
        // 语音输入那条已经记下的话（带上下文），录音转写里不再重复记；重转时同样过一遍
        val voice = if (lines.isEmpty()) emptyList() else voiceInputs(lines.minOf { it.startAt } - app.resound.capture.VoiceInputDedup.MIC_AFTER_MS,
            lines.maxOf { it.startAt } + app.resound.capture.VoiceInputDedup.MIC_BEFORE_MS)
        insertLines(lines.filter { line -> voice.none { app.resound.capture.VoiceInputDedup.heardByMic(it.startAt, it.text, line.startAt, line.text, line.speakerName) } })
        update(row)
    }
