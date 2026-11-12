package app.resound.topic

import app.resound.platform.NowMillis
import app.resound.platform.ZoneProvider
import app.resound.skills.core.MemoryTopicStore160
import app.resound.topic.core.TopicRulesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** 卡 179：专题记录可删除 */
class TopicDelete179Test {
    private fun run(block: suspend (TopicRulesRepository, MemoryTopicStore160, (Long) -> Unit) -> Unit) = runBlocking {
        var now = 1_000_000L
        val store = MemoryTopicStore160()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try { block(TopicRulesRepository(store, scope, NowMillis { now }, ZoneProvider { "Asia/Shanghai" }), store) { now += it } }
        finally { scope.coroutineContext[Job]!!.cancel() }
    }

    @Test fun endedTopicIsRemovedWithSpansAndMarks() = run { repo, store, advance ->
        val id = repo.start(TopicKind.Meeting, "周会", 15).topicId!!
        advance(5_000); repo.mark(); advance(5_000); repo.end()
        repo.delete(id)
        assertNull(store.get(id))
        assertTrue(store.all().isEmpty())
    }

    @Test fun runningTopicCannotBeDeleted() = run { repo, store, _ ->
        val id = repo.start(TopicKind.Meeting, null, 15).topicId!!
        val error = runCatching { repo.delete(id) }.exceptionOrNull()
        assertTrue(error is TopicException)
        assertNotNull(store.get(id))
    }

    @Test fun cancelledTopicCanBeDeleted() = run { repo, store, advance ->
        val id = repo.start(TopicKind.Normal, null, 15).topicId!!
        advance(5_000); repo.cancel(id)
        repo.delete(id)
        assertNull(store.get(id))
    }
}
