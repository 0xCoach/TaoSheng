package app.resound.ui

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import app.resound.ui.theme.*
import app.resound.ui.today.SwipeRow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

/** 合成行验证手势，不删除设备记录。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [35], qualifiers = "w393dp-h852dp-xhdpi")
class SwipeDelete094Test {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private fun swipe(id: Int) {
        val bounds = compose.onNodeWithText("事件$id").fetchSemanticsNode().boundsInRoot
        val start = Offset(bounds.left + 20f, bounds.center.y)
        compose.onRoot().performTouchInput { down(start) }
        for (step in 1..12) {
            compose.onRoot().performTouchInput { moveTo(start + Offset(bounds.width * 0.55f * step / 12f, 0f), 20) }
            compose.mainClock.advanceTimeBy(32)
            compose.waitForIdle()
        }
        compose.onRoot().performTouchInput { up() }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(32)
        compose.waitForIdle()
    }

    @Test fun successiveRowsCanEachBeDeletedWithTwoSwipes() {
        var rows by mutableStateOf(listOf(1, 2, 3))
        val deleted = mutableListOf<Int>()
        compose.setContent { ResoundTheme(ThemeMode.Light) {
            Column { rows.forEach { id ->
                SwipeRow(id, "重点", {}, { deleted += id; rows = rows.filterNot { it == id } }) {
                    Text("事件$id", Modifier.fillMaxWidth().height(80.dp))
                }
            } }
        } }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        for (id in 1..3) {
            swipe(id)
            compose.onNodeWithText("再右滑一次删除").assertExists("事件$id 应进入删除确认")
            swipe(id)
            compose.onNodeWithText("事件$id").assertDoesNotExist()
        }
        compose.runOnIdle { assertEquals(listOf(1, 2, 3), deleted) }
    }

    @Test fun expiredConfirmationCanBeArmedAgainAfterRowIdentityChanges() {
        var id by mutableIntStateOf(1)
        var deleted = 0
        compose.setContent { ResoundTheme(ThemeMode.Light) {
            SwipeRow(id, "重点", {}, { deleted = id }) {
                Text("事件$id", Modifier.fillMaxWidth().height(80.dp))
            }
        } }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { id = 2 }
        compose.mainClock.advanceTimeUntil(1000) { compose.onAllNodesWithText("事件2").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        swipe(2)
        compose.onNodeWithText("再右滑一次删除").assertExists()
        compose.mainClock.advanceTimeBy(3200)
        compose.onNodeWithText("再右滑一次删除").assertDoesNotExist()
        swipe(2)
        compose.onNodeWithText("再右滑一次删除").assertExists()
        swipe(2)
        compose.runOnIdle { assertEquals(2, deleted) }
    }

    @Test fun quickConsecutiveSwipesDoNotLoseDragDistance() {
        var deleted = 0
        compose.setContent { ResoundTheme(ThemeMode.Light) {
            SwipeRow(1, "重点", {}, { deleted++ }) {
                Text("事件1", Modifier.fillMaxWidth().height(80.dp))
            }
        } }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        repeat(2) {
            compose.onNodeWithText("事件1").performTouchInput {
                swipe(Offset(20f, centerY), Offset(width * 0.65f, centerY), 120)
            }
            compose.mainClock.advanceTimeBy(32)
            compose.waitForIdle()
        }
        compose.runOnIdle { assertEquals(1, deleted) }
    }

    @Test fun deletingRowShowsStageAndIgnoresFurtherSwipes() {
        var deleted = 0
        var busy by mutableStateOf<String?>(null)
        compose.setContent { ResoundTheme(ThemeMode.Light) {
            SwipeRow(1, "重点", {}, { deleted++; busy = "等待数据处理…" }, busyLabel = busy) {
                Text("事件1", Modifier.fillMaxWidth().height(80.dp))
            }
        } }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        repeat(4) { swipe(1) }
        compose.onNodeWithText("等待数据处理…").assertExists()
        compose.onNodeWithText("再右滑一次删除").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, deleted) }
    }
}
