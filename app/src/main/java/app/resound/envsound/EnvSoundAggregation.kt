package app.resound.envsound

import app.resound.data.SegmentEntity
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.datetime.toKotlinLocalDate

internal fun Iterable<Enum<*>>.encoded() = joinToString(",") { it.name }
internal fun EnvSoundRow.view() = view { java.io.File(it.id).isFile }
internal fun hourlySounds(date: LocalDate, segments: List<SegmentEntity>, rows: List<EnvSoundRow>, zone: ZoneId) =
    hourlySoundRows(date.toKotlinLocalDate(), segments, rows, kotlinx.datetime.TimeZone.of(zone.id))
