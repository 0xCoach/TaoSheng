package app.resound.ask

import app.resound.ask.core.*
import kotlinx.coroutines.*
import kotlinx.datetime.TimeZone
import org.junit.Assert.*
import org.junit.Test

class AskCancellation177Test {
    @Test fun suspendedProposalCanBeCancelledAndReleasesTheRead() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        var released = false
        val core = AskChangesCore(object : AskChangeStore {
            override suspend fun item(id: Long): AskChangeTarget? {
                entered.complete(Unit)
                try { awaitCancellation() } finally { released = true }
            }
            override suspend fun event(id: Long): AskChangeTarget? = error("不应查事件")
        }, TimeZone.UTC)
        val job = launch {
            core.propose("""{"operation":"item_status","id":42,"status":"Done"}""",
                listOf(AskSource(kind = "item", id = 42, at = 0, title = "合成待办", quote = "")))
            fail("取消后不能返回提案")
        }
        entered.await()
        assertFalse(job.isCompleted)
        job.cancelAndJoin()
        assertTrue(released)
    }
}
