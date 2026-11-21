package app.resound.update

enum class InstallSignal { PENDING, SUCCESS, CANCELLED, FAILURE, TIMEOUT, UNRESOLVED_RETURN }
enum class InstallAction { OPEN_CONFIRMATION, NOTIFY, COMPLETE, READY, OFFER_SYSTEM_INSTALLER }

object InstallConfirmationRule {
    const val TIMEOUT_MS = 30_000L

    fun action(signal: InstallSignal, foreground: Boolean): InstallAction = when (signal) {
        InstallSignal.PENDING -> if (foreground) InstallAction.OPEN_CONFIRMATION else InstallAction.NOTIFY
        InstallSignal.SUCCESS -> InstallAction.COMPLETE
        InstallSignal.CANCELLED -> InstallAction.READY
        InstallSignal.FAILURE, InstallSignal.TIMEOUT, InstallSignal.UNRESOLVED_RETURN -> InstallAction.OFFER_SYSTEM_INSTALLER
    }

    fun remainingMs(submittedAt: Long, now: Long): Long =
        (TIMEOUT_MS - (now - submittedAt).coerceAtLeast(0)).coerceIn(0, TIMEOUT_MS)
}
