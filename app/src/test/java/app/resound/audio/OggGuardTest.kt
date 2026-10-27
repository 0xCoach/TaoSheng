package app.resound.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class OggGuardTest {
    @Test fun switchesToAacOnlyAfterCrashDuringOggWrite() {
        val now = 1_000L
        // 写 OGG 时进程原生崩溃：改用 AAC 7 天
        assertEquals(now + OggGuard.PAUSE_MS, OggGuard.decide(leftover = true, nativeCrash = true, now = now, current = 0))
        // 安卓 10 查不到原因，只凭标记
        assertEquals(now + OggGuard.PAUSE_MS, OggGuard.decide(leftover = true, nativeCrash = null, now = now, current = 0))
        // 标记留下但上次不是崩溃（例如升级时被关）：不切换
        assertEquals(0L, OggGuard.decide(leftover = true, nativeCrash = false, now = now, current = 0))
        // 没有标记：维持原来的到期时间
        assertEquals(500L, OggGuard.decide(leftover = false, nativeCrash = true, now = now, current = 500))
    }
}
