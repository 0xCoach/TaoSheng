package app.resound.topic
import app.resound.topic.core.TopicRulesRepository
import app.resound.platform.NowMillis
import app.resound.platform.ZoneProvider
import kotlinx.coroutines.CoroutineScope
class RoomTopicRepository private constructor(private val core: TopicRulesRepository) : TopicRepository by core {
 constructor(db: app.resound.data.CaptureDatabase, scope: CoroutineScope,
  now: NowMillis, zone: ZoneProvider,
  quiet: (Long,Long) -> Boolean = { _,_ -> false }, recordingEnabled: () -> Boolean = { true },
  checkpoints: TopicCheckpointStore = MemoryTopicCheckpoints()
 ) : this(TopicRulesRepository(RoomTopicStore(db),scope,now,zone,quiet,recordingEnabled,checkpoints))
 var onChanged: suspend () -> Unit
  get() = core.onChanged
  set(value) { core.onChanged = value }
 var onEnded: suspend (Long) -> Unit
  get() = core.onEnded
  set(value) { core.onEnded = value }
 var onReminder: (Long,TopicReminder) -> Unit
  get() = core.onReminder
  set(value) { core.onReminder = value }
 suspend fun tick() = core.tick()
 suspend fun releaseGenerated(g: TopicGroup) = core.releaseGenerated(g.snapshot()).android()
}
