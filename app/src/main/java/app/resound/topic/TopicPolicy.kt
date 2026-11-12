package app.resound.topic
import app.resound.topic.core.TopicPolicy as Core
import app.resound.digest.snapshot
import kotlinx.datetime.toKotlinTimeZone
internal object TopicPolicy {
 const val LOOK_BACK = Core.LOOK_BACK
 const val MAX_DURATION = Core.MAX_DURATION
 const val QUIET = Core.QUIET
 const val CONFLICT = Core.CONFLICT
 const val GENERATION_ERROR = Core.GENERATION_ERROR
 fun activeAt(topic: Topic?, at: Long) = Core.activeAt(topic,at)
 fun overridesQuiet(topic: Topic?, at: Long) = Core.overridesQuiet(topic,at)
 fun mask(days: Set<java.time.DayOfWeek>) = Core.mask(days.map { kotlinx.datetime.DayOfWeek.valueOf(it.name) }.toSet())
 fun days(mask: Int?) = Core.days(mask)
 fun words(name: String?) = Core.words(name)
 fun next(start: Long,days: Set<java.time.DayOfWeek>,after: Long,zone: java.time.ZoneId) = Core.next(start,days.map { kotlinx.datetime.DayOfWeek.valueOf(it.name) }.toSet(),after,zone.toKotlinTimeZone())
 fun overlaps(a: Long,b: Long,c: Long,d: Long) = Core.overlaps(a,b,c,d)
 fun conflicts(start: Long,end: Long,repeat: Set<java.time.DayOfWeek>,groups: List<TopicGroup>,zone: java.time.ZoneId) = Core.conflicts(start,end,repeat.map { kotlinx.datetime.DayOfWeek.valueOf(it.name) }.toSet(),groups.map { it.snapshot() },zone.toKotlinTimeZone())
 fun changed(item: app.resound.data.MemoryItemEntity) = Core.changed(item.snapshot())
}
