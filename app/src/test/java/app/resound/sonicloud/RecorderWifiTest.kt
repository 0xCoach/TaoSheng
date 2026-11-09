/*
 * [INPUT]: 快传状态机与可控的热点/SDK 故障。
 * [OUTPUT]: 就绪条件、取消隔离、路由恢复和故障释放的关键回归。
 * [POS]: 不依赖无线硬件的生命周期测试；系统连接弹窗由真机验收。
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 */
package app.resound.sonicloud

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class RecorderWifiTest {
    private class Link : RecorderWifiNetwork {
        lateinit var available: () -> Unit
        lateinit var lost: () -> Unit
        var bound = false
        var released = false
        var ssid: String? = null
        override fun request(ssid: String, available: () -> Unit, lost: () -> Unit) {
            this.ssid = ssid; this.available = available; this.lost = lost
        }
        override fun bind() { bound = true }
        override fun restore() { bound = false }
        override fun release() { restore(); released = true }
    }

    private class Fixture(scope: CoroutineScope, failConnect: Boolean = false) {
        val link = Link()
        val calls = mutableListOf<RecorderWifiCommand>()
        val errors = mutableListOf<String>()
        val pending = ArrayDeque<suspend () -> Unit>()
        val wifi = RecorderWifi(scope, { pending.addLast(it) }, link, {
            calls += it
            if (failConnect && it == RecorderWifiCommand.Connect) error("连接失败")
        }, { errors += it })
        suspend fun drain() { while (pending.isNotEmpty()) pending.removeFirst().invoke() }
        suspend fun join() { wifi.open(); wifi.event(0, "Soni-Test"); link.available(); drain() }
    }

    @Test fun hotspotAndPhoneAssociationAreNotTcpReadiness() = runBlocking {
        val f = Fixture(this)
        try {
            f.wifi.open(); f.wifi.event(4, "Soni-Test")
            assertEquals(RecorderWifiPhase.Opening, f.wifi.status.value.phase)
            f.wifi.event(0, "Soni-Test")
            assertEquals("Soni-Test", f.link.ssid)
            assertFalse(f.wifi.ready)
            f.link.available(); f.drain()
            assertTrue(f.link.bound)
            assertFalse(f.wifi.ready)
            f.wifi.event(2, null)
            assertTrue(f.wifi.ready)
            assertFalse(f.link.bound)
            assertFalse(f.link.released)
        } finally { f.wifi.stop() }
        assertTrue(f.link.released)
    }

    @Test fun cancelledRequestCannotConnectEvenAfterNextAttemptStarts() = runBlocking {
        val f = Fixture(this)
        f.wifi.open(); f.wifi.event(0, "First")
        val old = f.link.available
        f.wifi.stop()
        try {
            f.wifi.open(); f.wifi.event(0, "Second")
            old(); f.drain()
            assertEquals(RecorderWifiPhase.Joining, f.wifi.status.value.phase)
            assertFalse(f.calls.contains(RecorderWifiCommand.Connect))
            f.link.available(); f.drain()
            assertEquals(1, f.calls.count { it == RecorderWifiCommand.Connect })
        } finally { f.wifi.stop() }
    }

    @Test fun tcpFailureReleasesTemporaryRoutingAndReportsOnce() = runBlocking {
        val f = Fixture(this, failConnect = true)
        f.join()
        assertFalse(f.link.bound)
        assertTrue(f.link.released)
        assertFalse(f.wifi.active)
        assertEquals(listOf("连接失败"), f.errors)
        f.link.lost(); f.drain()
        assertEquals(1, f.errors.size)
    }

    @Test fun networkLossAfterReadyAbortsInsteadOfSilentlyFallingBack() = runBlocking {
        val f = Fixture(this)
        f.join(); f.wifi.event(2, null)
        f.link.lost(); f.drain()
        assertFalse(f.wifi.ready)
        assertTrue(f.link.released)
        assertEquals(1, f.errors.size)
        assertEquals(RecorderWifiCommand.Close, f.calls.last())
    }

    @Test fun invalidSsidNeverRequestsAnUnrelatedNetwork() = runBlocking {
        val f = Fixture(this)
        f.wifi.open(); f.wifi.event(0, "x".repeat(33))
        assertNull(f.link.ssid)
        assertFalse(f.wifi.active)
        assertTrue(f.link.released)
        assertEquals(1, f.errors.size)
    }

    @Test fun cancellingDuringTcpConnectionRestoresRouting() = runBlocking {
        val f = Fixture(this)
        f.join()
        assertTrue(f.link.bound)
        f.wifi.stop()
        val closeCount = f.calls.count { it == RecorderWifiCommand.Close }
        f.wifi.event(2, null)
        assertEquals(closeCount + 1, f.calls.count { it == RecorderWifiCommand.Close })
        assertFalse(f.link.bound)
        assertTrue(f.link.released)
        assertFalse(f.wifi.ready)
        assertTrue(f.errors.isEmpty())
    }
}
