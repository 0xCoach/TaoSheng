package app.resound.topic

import android.content.Context
import app.resound.topic.core.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** 卡 226：问题记录里列出专题每块状态和失败原因，不带原话、要点和纪要。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = android.app.Application::class)
class TopicDiagnostics226Test {
    private val context get() = RuntimeEnvironment.getApplication() as Context
    private val dir get() = File(context.noBackupFilesDir, "topic-checkpoints")
    private val minute = 60_000L
    private fun write(id: Long, value: TopicCheckpoint, modified: Long) {
        check(dir.isDirectory || dir.mkdirs())
        File(dir, "$id.json").apply { writeText(Json.encodeToString(TopicCheckpoint.serializer(), value)); setLastModified(modified) }
    }

    @Test fun listsBlockStatesAndReasonsWithoutContent() {
        dir.deleteRecursively()
        val now = 1_795_420_000_000L
        val notes = listOf(
            TopicBlockNote(0, listOf(TopicBlockRange(now - 30 * minute, now - 20 * minute)), TopicBlockState.Done, text = "私密要点甲"),
            TopicBlockNote(1, listOf(TopicBlockRange(now - 20 * minute, now - 10 * minute)), TopicBlockState.Failed, reason = "模型响应超时，稍后重试"),
            TopicBlockNote(2, listOf(TopicBlockRange(now - 10 * minute, now)), TopicBlockState.Waiting, reason = "还有 1 段未成功转写"))
        write(11, TopicCheckpoint(format = 3, entries = mapOf("k" to "私密原话乙"), summary = "私密纪要丙", progress = TopicProgress(
            phase = "整理纪要", blocksTotal = 3, blocksDone = 1, blocksFailed = 1, waitingTranscribe = 1,
            notes = mapOf("整理纪要:0" to "私密要点甲"), blockNotes = notes, lastProgressAt = now - minute)), now)
        write(7, TopicCheckpoint(format = 3), now - 5 * 86_400_000L)

        val out = TopicCheckpointFiles.diagnostics(context, now)
        assertTrue(out, out.contains("专题 id=11"))
        assertTrue(out, out.contains("块=1/3 失败=1 等转写=1"))
        assertTrue(out, out.contains("块2 ") && out.contains("Failed 原因=模型响应超时，稍后重试"))
        assertTrue(out, out.contains("Waiting 原因=还有 1 段未成功转写"))
        assertFalse("3 天前的专题不列", out.contains("id=7"))
        listOf("私密要点甲", "私密原话乙", "私密纪要丙").forEach { assertFalse(out, out.contains(it)) }
    }

    @Test fun emptyAndCorruptFilesAreSafe() {
        dir.deleteRecursively()
        assertEquals("暂无", TopicCheckpointFiles.diagnostics(context))
        check(dir.mkdirs()); File(dir, "5.json").writeText("{坏")
        assertTrue(TopicCheckpointFiles.diagnostics(context).contains("id=5 检查点读不出"))
    }
}
