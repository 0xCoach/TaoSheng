package app.resound.digest

import app.resound.data.MemoryDao

/** Room 适配，DAO/实体/SQL 保持原样。 */
internal class RoomDigestWindowStore(private val dao: MemoryDao) : DigestWindowStore {
    override suspend fun voiceprintPending(segmentIds: List<Long>) = dao.voiceprintPending(segmentIds)
    override suspend fun unfinishedSegments(start: Long, end: Long) = dao.unfinishedSegments(start, end)
}
