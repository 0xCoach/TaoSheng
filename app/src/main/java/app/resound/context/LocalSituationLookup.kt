package app.resound.context

import app.resound.data.*
import app.resound.envsound.EnvSoundRow
import app.resound.location.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicLong

/** 安卓权限、数值转换、调度器和数据库失效版本接入。 */
class LocalSituationLookup internal constructor(
    available: () -> Boolean,
    points: suspend (Long, Long) -> List<LocationPoint>,
    cachedPlace: suspend (Double, Double) -> Place?,
    sounds: suspend (Long, Long) -> List<EnvSoundRow>,
    placeRevision: () -> Long = { 0 }, soundRevision: () -> Long = { 0 },
    zone: () -> ZoneId, io: kotlinx.coroutines.CoroutineDispatcher,
    pointBounds: suspend () -> Pair<Long, Long>? = { null },
) : SituationLookup by SituationStore(available, points, cachedPlace, sounds, placeRevision, soundRevision,
    app.resound.platform.ZoneProvider { zone().id }, io, { it is SecurityException }, Math::toRadians, pointBounds) {
    companion object {
        fun create(database: CaptureDatabase, location: LocationRepository, scope: CoroutineScope, zone: app.resound.platform.ZoneProvider, io: kotlinx.coroutines.CoroutineDispatcher): LocalSituationLookup {
            val places = AtomicLong()
            val sounds = AtomicLong()
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                database.invalidationTracker.createFlow("location_points", "location_places", "env_sounds").collect { tables ->
                    if (tables.any { it == "location_points" || it == "location_places" }) places.incrementAndGet()
                    if ("env_sounds" in tables) sounds.incrementAndGet()
                }
            }
            return LocalSituationLookup(location.settings::wanted, database.location()::betweenNow,
                location.places::cached, database.envSound()::between, places::get, sounds::get, zone = { ZoneId.of(zone.zoneId()) }, io = io,
                pointBounds = {
                    database.readSql("SELECT MIN(at), MAX(at) FROM location_points") {
                        if (!it.step() || it.isNull(0)) null else it.getLong(0) to it.getLong(1)
                    }
                })
        }

    }
}
