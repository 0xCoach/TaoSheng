package app.resound.context

import app.resound.envsound.EnvSoundRow
import app.resound.location.LocationPoint
import app.resound.location.Place
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

class Situation130Test {
    private val minute = 60_000L
    private val day = 24 * 60 * minute
    private fun point(at: Long, lat: Double = 30.0) = LocationPoint(at = at, lat = lat, lng = 120.0, accuracy = 10f, provider = "test")
    private fun lookup(points: List<LocationPoint> = emptyList(), rows: List<EnvSoundRow> = emptyList(), enabled: () -> Boolean = { true }) =
        LocalSituationLookup(enabled, { a, b -> points.filter { it.at in a until b } },
            { lat, _ -> Place(if (lat < 30.01) "Home书房" else "公司", "地址", "手动", lat < 30.01) },
            { a, b -> rows.filter { it.startAt < b && it.endAt > a } }, zone = { ZoneId.of("UTC") })

    @Test fun wholeIntervalUsesStayAndHome() = runBlocking {
        val source = lookup(listOf(point(0), point(60 * minute)))
        assertEquals(PlaceAt("Home书房", true, 0, 60 * minute), source.placeDuring(10 * minute, 20 * minute))
    }

    @Test fun choosesLongestOverlapRatherThanLongestFullStay() = runBlocking {
        val source = lookup(listOf(point(0), point(60 * minute), point(61 * minute, 30.02), point(90 * minute, 30.02)))
        assertEquals("公司", source.placeDuring(50 * minute, 90 * minute)?.name)
    }

    @Test fun mostlyOnRoadReturnsNullButExactlyOneThirdQualifies() = runBlocking {
        val source = lookup(listOf(point(0), point(10 * minute)))
        assertNull(source.placeDuring(0, 30 * minute + 1))
        assertNotNull(source.placeDuring(0, 30 * minute))
    }

    @Test fun emptyDisabledOrRevokedPermissionReturnsNothingEvenAfterCacheHit() = runBlocking {
        assertNull(lookup().placeDuring(0, minute))
        var enabled = true
        val source = lookup(listOf(point(0), point(60 * minute)), enabled = { enabled })
        assertNotNull(source.placeDuring(0, minute))
        enabled = false
        assertNull(source.placeDuring(0, minute))
        assertTrue(source.staysAt("Home", 0, minute).isEmpty())
        val denied = lookup(enabled = { throw SecurityException("permission revoked") })
        assertNull(denied.placeDuring(0, minute))
    }

    @Test fun environmentUsesClippedDurationExcludesWrongAndLimitsAcrossMidnight() = runBlocking {
        fun row(id: Long, a: Long, b: Long, tags: String, wrong: Boolean = false, special: String = "") =
            EnvSoundRow(id, a, b, "", "Keep", null, tags, special, "", false, false, reportedWrong = wrong)
        val source = lookup(rows = listOf(
            row(1, day - 100 * minute, day - minute, "Music"), // 查询内只占 1 分钟
            row(2, day - minute, day + 2 * minute, "Keyboard"), // 跨日只算一次，3 分钟
            row(3, day - 2 * minute, day + 2 * minute, "Rain", true),
            row(4, day + 2 * minute, day + 4 * minute, "Traffic", special = "DogBark"),
        ))
        assertEquals(listOf("键盘声", "附近有车流", "狗叫", "音乐"), source.envDuring(day - 2 * minute, day + 4 * minute, 10))
        assertEquals(listOf("键盘声", "附近有车流"), source.envDuring(day - 2 * minute, day + 4 * minute, 2))
        assertTrue(source.envDuring(0, day, 0).isEmpty())
    }

    @Test fun staysMatchCaseInsensitivelyInTimeOrderWithDayBoundedReadsAndInvalidation() = runBlocking {
        var revision = 0L
        var reads = 0
        var name = "Home书房"
        val source = LocalSituationLookup({ true }, { a, b ->
            assertTrue(b - a <= day)
            reads++
            listOf(point(a), point(a + 60 * minute))
        }, { _, _ -> Place(name, "", "手动") }, { _, _ -> emptyList() },
            placeRevision = { revision }, zone = { ZoneId.of("UTC") })
        val matches = source.staysAt("home", 10 * minute, day + 30 * minute)
        assertEquals(listOf(10 * minute, day), matches.map { it.start })
        assertEquals(day + 30 * minute, matches.last().end)
        assertEquals(2, reads)
        source.placeDuring(10 * minute, 20 * minute)
        assertEquals(2, reads)
        name = "新名字"; revision++
        assertEquals("新名字", source.placeDuring(10 * minute, 20 * minute)?.name)
        assertEquals(3, reads)
    }
}
