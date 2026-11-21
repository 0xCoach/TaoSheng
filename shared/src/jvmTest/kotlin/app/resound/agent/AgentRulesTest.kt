package app.resound.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRulesTest {
    @Test fun taskIdsAreUnique() {
        assertNotEquals(AgentRules.newTaskId(1000L), AgentRules.newTaskId(1000L))
        assertTrue(AgentRules.newTaskId(1000L).startsWith("task-"))
    }
    @Test fun retryBackoffGrowsThenStops() {
        assertEquals(60_000L, AgentRules.nextRetryDelay(1))
        assertEquals(300_000L, AgentRules.nextRetryDelay(2))
        assertNull(AgentRules.nextRetryDelay(6))
    }
    @Test fun statusMachineBlocksIllegalJumps() {
        assertTrue(AgentTaskStatus.canMove(AgentTaskStatus.Pending, AgentTaskStatus.Confirmed))
        assertTrue(AgentTaskStatus.canMove(AgentTaskStatus.Confirmed, AgentTaskStatus.Running))
        assertTrue(AgentTaskStatus.canMove(AgentTaskStatus.Running, AgentTaskStatus.Succeeded))
        assertFalse(AgentTaskStatus.canMove(AgentTaskStatus.Pending, AgentTaskStatus.Succeeded))
        assertFalse(AgentTaskStatus.canMove(AgentTaskStatus.Succeeded, AgentTaskStatus.Running))
    }
    @Test fun payloadRoundTripKeepsIdAndContent() {
        val p = AgentTaskPayload(taskId = "task-x", type = "deep_research", title = "t", content = "正文", createdAt = 1L)
        val parsed = AgentRules.parsePayload(AgentRules.payloadJson(p))
        assertEquals("task-x", parsed?.taskId)
        assertEquals("deep_research", parsed?.type)
        assertEquals("正文", parsed?.content)
    }
    @Test fun remoteAckParsesCommonFieldNames() {
        val (id, st) = AgentRules.remoteAck("""{"task_id":"r1","status":"done"}""")
        assertEquals("r1", id)
        assertEquals(AgentTaskStatus.Succeeded, AgentRules.mapRemoteStatus(st))
        val (none, noneSt) = AgentRules.remoteAck("not json")
        assertNull(none); assertNull(AgentRules.mapRemoteStatus(noneSt))
    }
    @Test fun endpointUrlRejectsPlainHttpOnPublicHost() {
        assertTrue(AgentRules.endpointUrlValid("https://agent.example.com/hook").first)
        assertFalse(AgentRules.endpointUrlValid("http://agent.example.com/hook").first)
        assertTrue(AgentRules.endpointUrlValid("http://192.168.1.5:8080/hook").first)
        assertFalse(AgentRules.endpointUrlValid("ftp://x").first)
    }
}
