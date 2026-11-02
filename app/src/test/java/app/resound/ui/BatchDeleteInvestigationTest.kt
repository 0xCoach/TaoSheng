package app.resound.ui

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import app.resound.AppGraph
import app.resound.digest.*
import app.resound.privacy.*
import app.resound.ui.preview.*
import app.resound.ui.theme.*
import app.resound.ui.today.TodayScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*
import java.time.LocalDate

/** 仅使用合成数据检查逐件删除期间的列表重排；不接触设备数据库。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [35], qualifiers = "w393dp-h852dp-xhdpi")
class BatchDeleteInvestigationTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test fun deletingTwentyEventsThroughHomeSheetDoesNotCrash() = deleteFromHome(20, false)
    @Test fun deletingSixEventsAcrossTimeGroupsDoesNotCrash() = deleteFromHome(6, true)
    @Test fun failedDeletionStaysVisibleAndRetriesOnlyFailedRecord() = deleteFromHome(6, false, 3L)

    private fun deleteFromHome(count: Int, acrossGroups: Boolean, failedId: Long? = null) {
        val base = kotlinx.coroutines.runBlocking { FakeMemoryRepository().events(LocalDate.now()).first().first() }
        val beginning = if (acrossGroups) LocalDate.now().atTime(2, 0)
            .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() else base.startAt
        val step = if (acrossGroups) 3_600_000L else 60_000L
        val rows = MutableStateFlow((1L..count.toLong()).map { id ->
            base.copy(id = id, title = "测试事件$id", startAt = beginning + id * step,
                endAt = beginning + id * step + 20_000, items = emptyList())
        })
        val memory = object : MemoryRepository by FakeMemoryRepository() {
            override fun events(date: LocalDate): Flow<List<MemoryEvent>> = rows
            override fun pendingItems(): Flow<List<MemoryItem>> = flowOf(emptyList())
        }
        val deleted = mutableListOf<Long>()
        val attempted = mutableListOf<Long>()
        var failureInjected = false
        AppGraph.privacy = object : PrivacyActions {
            override suspend fun forgetEvent(eventId: Long): ForgetResult {
                delay(40)
                attempted += eventId
                if (eventId == failedId && !failureInjected) { failureInjected = true; throw IllegalStateException("synthetic delete failure") }
                rows.value = rows.value.filterNot { it.id == eventId }
                deleted += eventId
                return ForgetResult(1, 1, 1, 0)
            }
            override suspend fun deleteRecent(minutes: Int): ForgetResult = error("unused")
            override suspend fun deleteRange(fromMillis: Long, untilMillis: Long): ForgetResult = error("unused")
            override suspend fun previewRange(fromMillis: Long, untilMillis: Long): ForgetPreview = error("unused")
            override suspend fun forgetSegment(segmentId: Long): ForgetResult = error("unused")
        }
        val capture = FakeCaptureController().apply { stop() }
        val stats = FakeStatsRepository()
        val transcripts = FakeTranscriptRepository()
        val settings = FakeDigestSettings()
        compose.setContent { ResoundTheme(ThemeMode.Light) {
            TodayScreen(capture, stats, transcripts, memory, settings, ThemeMode.Light, {}, 0.dp)
        } }
        compose.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToNode(hasText("测试事件1"))
        compose.onNodeWithText("测试事件1").performScrollTo().performTouchInput { longClick() }
        compose.onNodeWithText("全选").performClick()
        compose.onNodeWithText("删除 $count 件").performClick()
        compose.onAllNodesWithText("删除 $count 件").onLast().performClick()
        compose.waitUntil(15_000) { attempted.size == count }
        compose.waitForIdle()
        assertEquals(count - if (failedId == null) 0 else 1, deleted.distinct().size)
        assertEquals(listOfNotNull(failedId), rows.value.map { it.id })
        if (failedId != null) {
            compose.onNodeWithText("5 件已删除，1 件失败，请重试。").assertExists()
            compose.onNodeWithText("删除 1 件事？").assertExists()
            compose.onAllNodesWithText("删除 1 件").onLast().performClick()
            compose.waitUntil(15_000) { rows.value.isEmpty() }
            compose.waitForIdle()
            assertEquals(count + 1, attempted.size)
            compose.onNodeWithText("删除 1 件事？").assertDoesNotExist()
        } else compose.onNodeWithText("删除 $count 件事？").assertDoesNotExist()
    }
}
