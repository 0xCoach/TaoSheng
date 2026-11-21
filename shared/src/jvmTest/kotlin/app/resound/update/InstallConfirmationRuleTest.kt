package app.resound.update

import org.junit.Assert.assertEquals
import org.junit.Test

class InstallConfirmationRuleTest {
    @Test fun pendingUsesVisibleActivityOrNotification() {
        assertEquals(InstallAction.OPEN_CONFIRMATION, InstallConfirmationRule.action(InstallSignal.PENDING, true))
        assertEquals(InstallAction.NOTIFY, InstallConfirmationRule.action(InstallSignal.PENDING, false))
    }

    @Test fun successCompletesInEitherLocation() = checkBoth(InstallSignal.SUCCESS, InstallAction.COMPLETE)
    @Test fun failureOffersInstallerInEitherLocation() = checkBoth(InstallSignal.FAILURE, InstallAction.OFFER_SYSTEM_INSTALLER)
    @Test fun timeoutOffersInstallerInEitherLocation() = checkBoth(InstallSignal.TIMEOUT, InstallAction.OFFER_SYSTEM_INSTALLER)
    @Test fun deadlineIsThirtySecondsFromCommit() {
        assertEquals(1L, InstallConfirmationRule.remainingMs(100, 30_099))
        assertEquals(0L, InstallConfirmationRule.remainingMs(100, 30_100))
    }

    private fun checkBoth(signal: InstallSignal, expected: InstallAction) {
        for (foreground in listOf(true, false)) assertEquals(expected, InstallConfirmationRule.action(signal, foreground))
    }
}
