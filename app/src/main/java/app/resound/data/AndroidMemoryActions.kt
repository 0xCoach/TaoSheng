package app.resound.data

suspend fun MemoryDao.editEvent(id: Long, title: String, summary: String, points: String, at: Long) = transaction {
        val row = event(id) ?: return@transaction
        normalizeEventPoints(id, validPoints(row.pointsJson), row.originalPointsJson?.let(::validPoints))
        editEventRaw(id, title, summary, validPoints(points), at)
    }

suspend fun MemoryDao.restoreEvent(id: Long) = transaction {
        val row = event(id) ?: return@transaction
        normalizeEventPoints(id, validPoints(row.pointsJson), row.originalPointsJson?.let(::validPoints))
        restoreEventRaw(id)
    }

suspend fun MemoryDao.saveDigest(
        events: List<Pair<MemoryEventEntity, List<Long>>>,
        items: (eventIds: List<Long>) -> List<MemoryItemEntity>,
        allLineIds: List<Long>,
        at: Long,
        doneHints: List<Pair<Long, Long>> = emptyList(),
        hintCandidates: Set<Long> = emptySet(),
    ) = transaction {
        // 云端请求期间原话可能被隐私操作删除；整批旧结果作废，不复活事件和条目。
        if (refreshLines(allLineIds).size != allLineIds.distinct().size || topicLineCount(allLineIds) > 0) return@transaction
        // 必须在事务内重读：云端请求期间用户可能删除或编辑。
        val protected = protectedItems()
        val sourceEvents = refreshLines(allLineIds).associate { it.id to it.eventId }
        val oldEvents = sourceEvents.values.filterNotNull().toSet()
        val ids = events.map { (event, lineIds) ->
            insertEvent(event).also { id -> if (lineIds.isNotEmpty()) assignLines(lineIds, id) }
        }
        val rows = items(ids).filterNot { row -> protected.any { old ->
            (old.category == row.category || old.category in setOf("待办", "约定", "备忘") && row.category in setOf("待办", "约定", "备忘")) && (
                (row.lineId != null && row.lineId == old.lineId) ||
                old.content.trim() == row.content.trim() ||
                // 同一旧事件内换行或换措辞时保守保护；缺失行号则按本批旧事件保护。
                (old.eventId != null && sourceEvents[row.lineId] == old.eventId) || (row.lineId == null && old.eventId in oldEvents)
            )
        } }
        if (rows.isNotEmpty()) insertItems(rows)
        doneHints.filter { it.first in hintCandidates && it.second in allLineIds }.forEach { (id, line) ->
            val row = itemIncludingDeleted(id) ?: return@forEach
            val source = refreshLines(listOf(line)).singleOrNull() ?: return@forEach
            val dismissed = org.json.JSONArray(row.dismissedDoneLinesJson)
            if ((0 until dismissed.length()).none { dismissed.optLong(it) == line } && source.startAt > row.createdAt)
                writeDoneHint(id, line, source.startAt)
        }
        markDigested(allLineIds, at)
    }

suspend fun MemoryDao.dismissDoneHint(id: Long) = transaction {
        val row = itemIncludingDeleted(id) ?: return@transaction
        val line = row.doneHintLineId ?: return@transaction
        val dismissed = org.json.JSONArray(row.dismissedDoneLinesJson).put(line)
        clearDoneHint(id, dismissed.toString())
    }

suspend fun MemoryDao.updateItem(id: Long, content: String, dueAt: Long?, hasTime: Boolean, who: String?, at: Long = System.currentTimeMillis()) {
        require(content.isNotBlank())
        val date = dueAt?.let { java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()) }
        val normalized = if (hasTime) dueAt else date?.toLocalDate()?.atStartOfDay(date.zone)?.toInstant()?.toEpochMilli()
        val text = date?.format(java.time.format.DateTimeFormatter.ofPattern(if (hasTime) "yyyy-MM-dd HH:mm" else "yyyy-MM-dd"))
        updateItemRow(id, content.trim(), normalized, text, who?.trim()?.takeIf { it.isNotEmpty() }, at)
    }

private fun validPoints(text: String): String = try { org.json.JSONArray(text).toString() } catch (_: org.json.JSONException) { "[]" }

suspend fun MemoryDao.setItemsStatus(ids: List<Long>, status: String) = setItemsStatus(ids, status, System.currentTimeMillis())

suspend fun MemoryDao.setItemStatus(id: Long, status: String) = setItemStatus(id, status, System.currentTimeMillis())

suspend fun MemoryDao.setStarred(id: Long, starred: Boolean) = setStarred(id, starred, System.currentTimeMillis())
