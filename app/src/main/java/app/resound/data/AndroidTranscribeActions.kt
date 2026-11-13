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
