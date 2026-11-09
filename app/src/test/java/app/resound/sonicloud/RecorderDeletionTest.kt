/*
 * [INPUT]: 删除状态机、模拟 SDK 回执与完整目录。
 * [OUTPUT]: 逐个复核、失败停队列、超时隔离及停止后续删除的回归。
 * [POS]: 不触碰真实录音卡文件的清理安全测试。
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 */
package app.resound.sonicloud

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class RecorderDeletionTest {
    private val a = RecorderFile("a.opus", 40, 1)
    private val b = RecorderFile("b.opus", 80, 2)
    private class Fixture(scope: CoroutineScope, failSend: Boolean = false) {
        val sent = mutableListOf<String>()
        var refreshes = 0
        val errors = mutableListOf<String>()
        val pending = ArrayDeque<suspend () -> Unit>()
        val deletion = RecorderDeletion(scope, { pending.addLast(it) },
            { if (failSend) error("发送失败"); sent += it }, { refreshes++ }, { errors += it })
    }

    @Test fun serialDeletionRequiresAckAndAbsenceFromCompleteDirectory() = runBlocking {
        val f = Fixture(this)
        try {
            f.deletion.start(listOf(a, b, a))
            assertEquals(listOf(a.name), f.sent)
            f.deletion.listed(emptyList())
            assertEquals(0, f.deletion.status.value.completed)
            f.deletion.acknowledged(true); f.deletion.acknowledged(true)
            assertEquals(1, f.refreshes)
            assertEquals(0, f.deletion.status.value.completed)
            f.deletion.listed(listOf(b))
            assertEquals(listOf(a.name, b.name), f.sent)
            assertEquals(1, f.deletion.status.value.completed)
            f.deletion.acknowledged(true); f.deletion.listed(emptyList())
            assertFalse(f.deletion.active)
            assertEquals(2, f.deletion.status.value.completed)
            assertTrue(f.errors.isEmpty())
        } finally { f.deletion.abort("测试结束") }
    }

    @Test fun successAckWithFileStillPresentStopsWithoutCountingSuccess() = runBlocking {
        val f = Fixture(this)
        f.deletion.start(listOf(a, b))
        f.deletion.acknowledged(true); f.deletion.listed(listOf(a, b))
        assertFalse(f.deletion.active)
        assertEquals(0, f.deletion.status.value.completed)
        assertEquals(listOf(a.name), f.sent)
        assertTrue(f.deletion.status.value.needsReconnect)
    }

    @Test fun cardRejectionAndSendFailureNeverAdvanceQueue() = runBlocking {
        val f = Fixture(this)
        f.deletion.start(listOf(a, b)); f.deletion.acknowledged(false)
        assertEquals(listOf(a.name), f.sent)
        assertEquals(1, f.errors.size)
        val broken = Fixture(this, failSend = true)
        broken.deletion.start(listOf(a, b))
        assertFalse(broken.deletion.active)
        assertEquals(0, broken.deletion.status.value.completed)
        assertEquals(1, broken.errors.size)
    }

    @Test fun timeoutOrDisconnectIgnoresLateAcksAndRequiresReconnect() = runBlocking {
        val f = Fixture(this)
        f.deletion.start(listOf(a, b)); f.deletion.timeout()
        f.deletion.acknowledged(true); f.deletion.listed(emptyList())
        assertEquals(0, f.deletion.status.value.completed)
        assertEquals(listOf(a.name), f.sent)
        assertTrue(runCatching { f.deletion.start(listOf(b)) }.isFailure)
        f.deletion.reset()
        f.deletion.start(listOf(b)); f.deletion.abort("连接断开")
        f.deletion.acknowledged(true)
        assertFalse(f.deletion.active)
        assertTrue(f.deletion.status.value.needsReconnect)
    }

    @Test fun stopRemainingStillVerifiesAlreadySentDeletion() = runBlocking {
        val f = Fixture(this)
        f.deletion.start(listOf(a, b)); f.deletion.stopRemaining()
        assertTrue(f.deletion.active)
        f.deletion.acknowledged(true); f.deletion.listed(listOf(b))
        assertEquals(listOf(a.name), f.sent)
        assertEquals(1, f.deletion.status.value.completed)
        assertEquals(2, f.deletion.status.value.total)
        assertFalse(f.deletion.active)
        assertTrue(f.deletion.status.value.stopping)
        assertTrue(f.errors.isEmpty())
    }
}
