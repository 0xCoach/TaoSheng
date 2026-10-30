package app.resound.importer

import androidx.room.withTransaction
import app.resound.data.*
import app.resound.digest.LlmException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

internal class ImportKindEngine(
    private val db: CaptureDatabase,
    private val summary: ImportSummary,
    private val prepareSpeakers: suspend (ImportRecordRow) -> String? = { null },
    private val audioIdle: suspend (suspend () -> Unit) -> Unit = { it() },
    private val afterReady: suspend () -> Unit = {},
) {
    private val gate = Mutex()
    private val dao = db.imports()

    suspend fun run() = gate.withLock {
        for (original in dao.pending()) {
            var row = dao.get(original.id) ?: continue
            val segments = dao.segments(row.id)
            if (segments.any { it.status in setOf("Pending", "Running") }) continue
            val failures = segments.filter { it.status !in setOf("Done", "Empty", "SkippedPlayback", "SkippedEnv") }
            if (failures.isNotEmpty()) {
                if (row.status != "Failed") dao.update(row.copy(status = "Failed", error = "还有 ${failures.size} 段未转写成功，请先重试失败段"))
                continue
            }
            if (segments.any { it.status == "Done" && it.voiceprintProcessedAt == null }) continue
            try {
                audioIdle {
                    row = dao.get(row.id) ?: return@audioIdle
                    if (!row.speakersReady) {
                        val warning = prepareSpeakers(row)
                        db.withTransaction {
                            if (dao.get(row.id)?.revision == row.revision)
                                dao.update(row.copy(speakersReady = true, error = warning))
                        }
                    }
                }
                row = dao.get(row.id) ?: continue
                if (!row.speakersReady || !row.summaryRequested) continue
                val lines = dao.lines(row.id)
                val claimed = db.withTransaction {
                    val current = dao.get(row.id)
                    if (current?.revision != row.revision) false
                    else { dao.update(current.copy(status = "Summarizing")); true }
                }
                if (!claimed) continue
                val result = if (row.kind == ImportKind.Normal.name) null else summary.generate(row, lines, dao.todos(row.id))
                db.withTransaction {
                    val current = dao.get(row.id) ?: return@withTransaction
                    // 删除/改类型/重新转写期间，旧网络结果不得复活已删除的文字。
                    if (current.revision != row.revision) return@withTransaction
                    if (dao.lines(row.id).map { it.copy(eventId = null, digestedAt = null) } != lines.map { it.copy(eventId = null, digestedAt = null) }) {
                        dao.update(current.copy(status = "Transcribing", revision = current.revision + 1))
                        return@withTransaction
                    }
                    current.cardId?.let { dao.deleteCard(it) }
                    val cardId = result?.card?.let { db.skills().save(it) }
                    result?.todos?.forEach { todo ->
                        val source = dao.lines(row.id).firstOrNull { it.id == todo.lineId && it.speakerName == "我" } ?: return@forEach
                        if (dao.todos(row.id).none { it.lineId == todo.lineId && ImportTodoMerge.same(it.content, todo.content) }) {
                            db.memory().insertItems(listOf(MemoryItemEntity(eventId = source.eventId, category = "待办", kind = "我要做",
                                content = todo.content, dueText = null, dueAt = null, who = "我", lineId = source.id,
                                status = "Pending", createdAt = System.currentTimeMillis())))
                        }
                    }
                    ImportTodoMerge.merge(db, row.id)
                    dao.update(current.copy(cardId = cardId, status = "Done", summaryRequested = false))
                }
                try { afterReady() } catch (e: CancellationException) { throw e } catch (_: Exception) { /* 已保存纪要不因后续调度失败回滚。 */ }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                db.withTransaction {
                    val current = dao.get(row.id)
                    if (current?.revision == row.revision) dao.update(current.copy(status = "Failed", summaryRequested = false,
                        error = (e as? LlmException)?.type?.human ?: "纪要生成失败，请重试"))
                }
            }
        }
    }

    suspend fun change(id: Long, kind: ImportKind) = gate.withLock {
        audioIdle {
            db.withTransaction {
                val old = dao.get(id) ?: return@withTransaction
                val segments = dao.segments(id)
                old.cardId?.let { dao.deleteCard(it) }
                val byChannel = kind == ImportKind.Call && segments.any { it.importChannel != null }
                val separate = ImportKindPolicy.separate(kind, byChannel)
                val count = ImportKindPolicy.speakers(kind, byChannel)
                val needsAudio = segments.filter { (separate && (it.separateSpeakers != true || it.speakerCount != count)) ||
                    (byChannel && !old.byChannel) }
                val missing = needsAudio.any { !File(it.path).isFile }
                if (!missing) for (s in segments) {
                    val redo = s in needsAudio && s.status in setOf("Done", "Empty")
                    db.transcribe().update(s.copy(separateSpeakers = separate, speakerCount = count,
                        status = if (redo) "Pending" else s.status, redoPending = redo || s.redoPending,
                        attempts = if (redo) 0 else s.attempts, completedAt = if (redo) null else s.completedAt,
                        voiceprintProcessedAt = if (redo) null else if (!separate && !byChannel) System.currentTimeMillis() else s.voiceprintProcessedAt))
                    if (!separate && !byChannel) db.voiceprint().lines(s.id).forEach {
                        dao.line(it.copy(speakerTag = null, speakerName = null, speakerScore = null, speakerPersonId = null, speakerManual = false))
                    }
                }
                dao.update(old.copy(kind = kind.name, byChannel = byChannel, cardId = null, revision = old.revision + 1,
                    speakersReady = !missing && !byChannel && kind != ImportKind.Call,
                    status = if (missing) "Failed" else "Transcribing", summaryRequested = !missing,
                    error = if (missing) "原声已清理，无法按新类型重新分人；请重新导入录音" else null))
            }
        }
    }

    suspend fun regenerate(id: Long) = gate.withLock {
        db.withTransaction {
            val row = dao.get(id) ?: return@withTransaction
            // 无原声的改类型必须显式重新导入，不能把旧分人结果冒充新类型。
            if (row.error?.startsWith("原声已清理") == true) return@withTransaction
            row.cardId?.let { dao.deleteCard(it) }
            dao.update(row.copy(cardId = null, revision = row.revision + 1, status = "Transcribing", error = null, summaryRequested = true))
        }
    }
}

internal object ImportTodoMerge {
    private fun normalize(text: String) = text.filter { it.isLetterOrDigit() }.removePrefix("我要").removePrefix("我")
    fun same(a: String, b: String) = normalize(a).isNotEmpty() && normalize(a) == normalize(b)
    suspend fun merge(db: CaptureDatabase, id: Long) {
        db.imports().attachTodoEvents(id)
        val rows = db.imports().todos(id).filter { !it.deleted && it.mergedInto == null && it.status != "Ignored" }
        val roots = mutableListOf<MemoryItemEntity>()
        for (row in rows.sortedWith(compareBy<MemoryItemEntity> { if (it.status == "Pending") 1 else 0 }.thenBy { it.id })) {
            val match = roots.firstOrNull { same(it.content, row.content) && it.who == row.who }
            if (match != null && !row.noMerge && row.editedAt == null) db.memory().mergeInto(row.id, match.id)
            else roots += row
        }
    }
    suspend fun all(db: CaptureDatabase) = db.withTransaction {
        db.imports().ids().forEach { merge(db, it) }
    }
}
