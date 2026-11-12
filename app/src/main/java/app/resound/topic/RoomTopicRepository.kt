package app.resound.topic
import app.resound.topic.core.TopicRulesRepository
import app.resound.platform.NowMillis
import app.resound.platform.ZoneProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.datetime.toKotlinLocalDate
import java.time.LocalDate
import java.time.ZoneId
internal class RoomTopicRepository private constructor(private val core: TopicRulesRepository) : TopicRepository by core {
 constructor(db: app.resound.data.CaptureDatabase, scope: CoroutineScope,
  now: () -> Long = System::currentTimeMillis, zone: () -> ZoneId = ZoneId::systemDefault,
  quiet: (Long,Long) -> Boolean = { _,_ -> false }, recordingEnabled: () -> Boolean = { true }
 ) : this(TopicRulesRepository(RoomTopicStore(db),scope,NowMillis(now),ZoneProvider { zone().id },quiet,recordingEnabled))
 var onChanged: suspend () -> Unit
  get() = core.onChanged
  set(value) { core.onChanged = value }
 var onEnded: suspend (Long) -> Unit
  get() = core.onEnded
  set(value) { core.onEnded = value }
 var onReminder: (Long,TopicReminder) -> Unit
  get() = core.onReminder
  set(value) { core.onReminder = value }
 fun observeDay(date: LocalDate) = core.observeDay(date.toKotlinLocalDate())
 suspend fun tick() = core.tick()
 suspend fun releaseGenerated(g: TopicGroup) = core.releaseGenerated(g.snapshot()).android()
}
internal fun TopicRow.retainedIds(): List<Long> = org.json.JSONArray(retainedItemIds).let { a -> (0 until a.length()).map { a.getLong(it) } }
