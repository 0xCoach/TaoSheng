package app.resound.onboarding

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import app.resound.backup.BackupArchive
import app.resound.backup.BackupSettings
import app.resound.capture.CaptureNotifications
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class Onboarding132Test {
    private val context get() = RuntimeEnvironment.getApplication() as Context
    private val day = LocalOnboarding.DAY

    private inner class Fixture(existing: Long? = null) : AutoCloseable {
        val prefs = context.getSharedPreferences("onboarding", 0)
        val backup = context.getSharedPreferences("backup", 0)
        var at = 10 * day
        val earliest = MutableStateFlow(existing)
        val multiple = MutableStateFlow<Long?>(null)
        val needsSpeakers = MutableStateFlow(true)
        val clock = MutableStateFlow(at)
        private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var subject = create()
        private fun create() = LocalOnboarding(prefs, backup, earliest, multiple, needsSpeakers, clock, scope) { at }
        fun restart() { scope.cancel(); scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined); subject = create() }
        fun tick(value: Long) { at = value; clock.value = value }
        override fun close() { scope.cancel() }
    }

    @Test fun firstLiveTranscriptIsObservedOnceAndSurvivesDeletionAndRestart() {
        Fixture().use { f ->
            assertNull(f.subject.firstTranscriptAt.value)
            f.earliest.value = 3 * day // 较早录下的句子现在才完成转写。
            assertEquals(10 * day, f.subject.firstTranscriptAt.value)
            f.tick(11 * day)
            f.earliest.value = day
            f.earliest.value = null
            f.restart()
            assertEquals(10 * day, f.subject.firstTranscriptAt.value)
        }
    }

    @Test fun existingTranscriptUsesItsOriginalTimeAndFinishedAfterTrialHasZeroWait() {
        Fixture(2 * day).use { f ->
            assertEquals(2 * day, f.subject.firstTranscriptAt.value)
            assertEquals(listOf(OnboardingTip.Backup), f.subject.tips.value)
            assertFalse(f.prefs.contains(LocalOnboarding.ELAPSED))
            f.subject.wizardFinished()
            assertEquals(0L, f.prefs.getLong(LocalOnboarding.ELAPSED, -1))
        }
    }

    @Test fun interruptionWithoutNotificationPermissionOrdersTipsAndDismissalIsPermanent() {
        Fixture(day).use { f ->
            f.multiple.value = f.at
            shadowOf(context.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(false)
            CaptureNotifications.interrupted(context)
            assertEquals(OnboardingTip.entries, f.subject.tips.value)
            OnboardingTip.entries.forEach { f.subject.dismiss(it) }
            assertTrue(f.subject.tips.value.isEmpty())
            f.restart()
            CaptureNotifications.interrupted(context)
            f.multiple.value = f.at + 1
            f.tick(f.at + 1)
            assertTrue(f.subject.tips.value.isEmpty())
        }
    }

    @Test fun speakersDisappearWhenEnabledOrEvidenceDeletedOrTwoDaysOld() {
        Fixture().use { f ->
            assertTrue(f.subject.tips.value.isEmpty())
            f.multiple.value = f.at
            assertEquals(listOf(OnboardingTip.Speakers), f.subject.tips.value)
            f.needsSpeakers.value = false // 已启用且模型就绪，或切到了云端。
            assertTrue(f.subject.tips.value.isEmpty())
            f.needsSpeakers.value = true
            assertEquals(listOf(OnboardingTip.Speakers), f.subject.tips.value)
            f.multiple.value = null
            assertTrue(f.subject.tips.value.isEmpty())
            f.multiple.value = f.at
            f.tick(f.at + 2 * day)
            assertTrue(f.subject.tips.value.isEmpty())
        }
    }

    @Test fun backupRequiresMoreThanThreeDaysAndEitherSuccessRemainsRemembered() {
        for (key in listOf("localLastAt", "lastAt")) {
            context.getSharedPreferences("onboarding", 0).edit().clear().commit()
            context.getSharedPreferences("backup", 0).edit().clear().commit()
            Fixture(7 * day).use { f ->
                assertTrue(f.subject.tips.value.isEmpty())
                f.tick(f.at + 1)
                assertEquals(listOf(OnboardingTip.Backup), f.subject.tips.value)
                f.backup.edit().putLong(key, f.at).commit()
                assertTrue(f.subject.tips.value.isEmpty())
                f.backup.edit().clear().commit()
                f.restart()
                assertTrue(f.subject.tips.value.isEmpty())
            }
        }
    }

    @Test fun countersAndElapsedPersistWithoutContentOrBackupInclusion() {
        Fixture().use { f ->
            repeat(3) { f.subject.record("wizard_shown") }
            f.subject.record("私人句子 sk-secret")
            f.subject.wizardFinished()
            f.tick(f.at + 2400)
            f.subject.wizardFinished() // 重复完成不能重置计时起点。
            f.earliest.value = f.at - 1000
            f.restart()
            f.subject.record("wizard_shown")
            val diagnostic = LocalOnboarding.diagnostics(f.prefs)
            assertTrue(diagnostic.contains("wizard_shown count=4"))
            assertTrue(diagnostic.contains("other count=1"))
            assertTrue(diagnostic.contains("${LocalOnboarding.ELAPSED}=2400"))
            assertFalse(f.prefs.all.toString().contains("私人"))
            assertFalse(f.prefs.all.toString().contains("sk-secret"))
            assertFalse("onboarding" in BackupSettings.prefs)
            assertFalse(BackupArchive.allowed("shared_prefs/onboarding.xml"))
        }
    }
}
