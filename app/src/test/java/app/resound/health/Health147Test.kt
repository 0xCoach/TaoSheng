package app.resound.health

import app.resound.quick.ListenState
import org.junit.Assert.*
import org.junit.Test

class Health147Test {
    private val healthy = HealthInputs(now = 100 * DAY + 12 * 60 * 60_000L, today = 100,
        microphoneGranted = true, notificationsGranted = true, batteryUnrestricted = true,
        listen = ListenState.Listening, inQuietPeriod = false, transcriptReady = true, transcriptFailures = 0,
        modelReady = true, providerName = "DeepSeek", balance = 20.0, balanceAvailable = true,
        balanceCurrency = "CNY", balanceThreshold = 10.0, cloudBackupEnabled = true, cloudBackupFailed = false,
        localBackupEnabled = false, localBackupFailed = false, nextBackupAt = 101 * DAY,
        interruptionsToday = 0, lastInterruptionDay = 97)

    @Test fun healthyReportHasFactsAndUnknownHistoryStaysUnknown() {
        val report = HealthRules.report(healthy)
        assertTrue(report.issues.isEmpty())
        assertEquals(HealthFacts(3, 0, 101 * DAY), report.facts)
        assertEquals(healthy.now, report.checkedAt)
        val unknown = HealthRules.report(healthy.copy(lastInterruptionDay = null, cloudBackupEnabled = false))
        assertNull(unknown.facts.stableDays)
        assertNull(unknown.facts.nextBackupAt)
    }

    @Test fun batteryBeforeLowBalanceAndFormatsMoney() {
        val report = HealthRules.report(healthy.copy(batteryUnrestricted = false, balance = 1.2))
        assertEquals(listOf("battery", "balance-low"), report.issues.map { it.id })
        assertEquals(listOf(HealthSeverity.Critical, HealthSeverity.Warning), report.issues.map { it.severity })
        assertEquals("剩 ¥1.20", report.issues.last().detail)
    }

    @Test fun criticalProblemsStayAheadAndTruncateAtThree() {
        val report = HealthRules.report(healthy.copy(microphoneGranted = false, listen = ListenState.Stopped,
            balance = 0.0, transcriptReady = false, transcriptFailures = 4, cloudBackupFailed = true))
        assertEquals(listOf("permissions", "listening", "balance-empty"), report.issues.map { it.id })
        assertTrue(report.issues.all { it.severity == HealthSeverity.Critical })
        assertEquals(HealthFix.TopUp, report.issues.last().fix)
        // 授权已恢复但控制器仍为 NoPermission 时，也要提醒真正启动录音。
        assertEquals("listening", HealthRules.report(healthy.copy(listen = ListenState.Unavailable)).issues.single().id)
    }

    @Test fun abnormalStopAndRecoveryAreOneInterruptionToday() {
        val at = 100 * DAY
        val events = listOf(HealthWatchdogEvent(at, "ServiceStopped", "输入异常；等待手动恢复，保留未正常停止标记"),
            HealthWatchdogEvent(at + 1000, "Killed", null), HealthWatchdogEvent(at + 1100, "Killed", null),
            HealthWatchdogEvent(at + 1200, "Recovered", null), HealthWatchdogEvent(at + 1201, "ServiceStarted", null))
        val interruptions = HealthWatchdogRules.interruptions(events, healthy.now, false)
        assertEquals(listOf(at), interruptions)
        assertEquals(HealthFacts(0, 1, 101 * DAY), HealthRules.report(healthy.copy(
            interruptionsToday = interruptions.size, lastInterruptionDay = interruptions.last() / DAY)).facts)
    }

    @Test fun userPauseQuietCallAndNormalStopAreExcludedButLongBusyCounts() {
        val base = 100 * DAY
        fun event(minute: Int, type: String, detail: String? = null) = HealthWatchdogEvent(base + minute * 60_000, type, detail)
        val ordinary = listOf(event(0, "Paused", "暂停至"), event(1, "Resumed"), event(2, "Paused", "停录时段：睡眠"),
            event(3, "Resumed"), event(4, "MicBusy", "通话中"), event(20, "MicReleased"), event(21, "ServiceStopped", "用户正常停止"))
        assertTrue(HealthWatchdogRules.interruptions(ordinary, healthy.now, false).isEmpty())
        val otherApp = ordinary + listOf(event(30, "MicBusy", "别的应用在用麦克风"), event(35, "MicReleased"),
            event(40, "MicBusy", "别的应用在用麦克风"), event(46, "MicReleased"))
        assertEquals(listOf(base + 40 * 60_000), HealthWatchdogRules.interruptions(otherApp, healthy.now, false))
        assertTrue(HealthRules.report(healthy.copy(listen = ListenState.Paused)).issues.isEmpty())
        assertTrue(HealthRules.report(healthy.copy(listen = ListenState.Stopped, inQuietPeriod = true)).issues.isEmpty())
        assertTrue(HealthRules.report(healthy.copy(listen = ListenState.Stopped, manualPausedUntil = healthy.now + 60_000)).issues.isEmpty())
    }

    companion object { const val DAY = 86_400_000L }
}
