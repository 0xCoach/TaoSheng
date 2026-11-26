package app.resound.location

import android.content.Context
import android.location.Geocoder
import app.resound.location.Places.toPlace
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

object Places {
    const val MANUAL = MANUAL_PLACE_SOURCE

    /** 离这个坐标最近、且在停留半径内的手动地点。 */
    fun nearestManual(rows: List<PlaceRow>, lat: Double, lng: Double): PlaceRow? {
        var closest: PlaceRow? = null
        var best = Double.POSITIVE_INFINITY
        for (row in rows) {
            val latitude = row.lat ?: continue
            val longitude = row.lng ?: continue
            val distance = Track.meters(latitude, longitude, lat, lng)
            if (distance <= Track.STAY_RADIUS_M && distance < best) {
                closest = row
                best = distance
            }
        }
        return closest
    }

    fun PlaceRow.toPlace() = Place(name, address, source, home)
    /** 三位小数约 100 米：停留点在同一处时命中同一格。 */
    fun cell(lat: Double, lng: Double) = String.format(Locale.US, "%.3f,%.3f", lat, lng)

    /** 手机给的是 WGS-84，高德要 GCJ-02（国内偏移坐标）；境外不偏移。 */
    fun wgsToGcj(lat: Double, lng: Double): Pair<Double, Double> {
        if (lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271) return lat to lng
        val semiMajorAxis = 6_378_245.0
        val eccentricitySquared = 0.00669342162296594323
        val x = lng - 105.0
        val y = lat - 35.0
        val rootX = sqrt(abs(x))
        val wave = (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        val north = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * rootX + wave +
            (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0 +
            (160.0 * sin(y / 12.0 * PI) + 320 * sin(y * PI / 30.0)) * 2.0 / 3.0
        val east = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * rootX + wave +
            (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0 +
            (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0
        val radLat = lat / 180.0 * PI
        val latSine = sin(radLat)
        val scale = 1 - eccentricitySquared * latSine * latSine
        val meridian = (semiMajorAxis * (1 - eccentricitySquared)) / (scale * sqrt(scale))
        val parallel = semiMajorAxis / sqrt(scale) * cos(radLat)
        return (lat + north * 180.0 / (meridian * PI)) to (lng + east * 180.0 / (parallel * PI))
    }

    /** 解析高德逆地理编码：名字优先取所在区域（小区/园区/商场），其次最近的地点，再次街道门牌。 */
    fun parseAmap(body: String): Place? {
        val result = JSONObject(body)
        check(result.optString("status") == "1") { "高德：" + result.optString("info", "查询失败") }
        val location = result.optJSONObject("regeocode") ?: return null
        val address = (location.opt("formatted_address") as? String).orEmpty()
        val component = location.optJSONObject("addressComponent")
        val door = component?.optJSONObject("streetNumber")
        val doorName = door?.let { (it.opt("street") as? String).orEmpty() + (it.opt("number") as? String).orEmpty() }
        val neighborhood = sequenceOf("aois", "pois").firstNotNullOfOrNull { group ->
            location.optJSONArray(group)?.optJSONObject(0)?.optString("name")?.takeIf(String::isNotBlank)
        }
        val district = component?.opt("township") as? String
        val label = neighborhood ?: doorName?.takeIf(String::isNotBlank) ?: district ?: address
        return label.takeIf(String::isNotBlank)?.let { Place(it, address, "高德") }
    }
}

/**
 * 查地名：先查本地缓存；填了高德 Key 用高德（国内最准），否则用手机系统的地理编码。
 * 坐标会发给高德或系统地理服务；关掉“显示地名”就完全不查。
 */
class PlaceResolver(
    context: Context,
    private val dao: PlaceDao,
    private val settings: LocationSettings,
    private val http: OkHttpClient = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build(),
    private val amapBase: String = "https://restapi.amap.com/v3/geocode/regeo",
    private val amapKey: () -> String = settings::amapKey,
) {
    private val app = context.applicationContext
    private val lock = Mutex()
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    suspend fun cached(lat: Double, lng: Double): Place? {
        val named = Places.nearestManual(dao.manualNow(), lat, lng)
        if (named != null) return named.toPlace()
        val row = dao.get(Places.cell(lat, lng))
        return row?.takeUnless { it.source == Places.MANUAL }?.toPlace()
    }

    val manual = dao.manual()

    /** 手动起名：存精确坐标，方圆 150 米内都用这个名字；标成家时其他手动地点取消家。 */
    suspend fun setManual(lat: Double, lng: Double, name: String, address: String, home: Boolean, replacing: PlaceRow? = null) {
        replacing?.let { dao.delete(it.cell) }
        if (home) dao.clearHome()
        dao.put(PlaceRow("m:" + String.format(Locale.US, "%.6f,%.6f", lat, lng), name.trim(), address.trim(), Places.MANUAL,
            System.currentTimeMillis(), lat, lng, home))
    }
    suspend fun removeManual(row: PlaceRow) = dao.delete(row.cell)

    suspend fun resolve(lat: Double, lng: Double): Place? = lock.withLock {
        val stored = cached(lat, lng)
        if (stored != null || !settings.lookup.value) return@withLock stored

        val found = withContext(Dispatchers.IO) {
            try {
                val credential = amapKey()
                val resolved = if (credential.isBlank()) system(lat, lng) else amap(credential, lat, lng)
                _lastError.value = null
                resolved
            } catch (error: Exception) {
                _lastError.value = error.message ?: "查地名失败"
                null
            }
        }
        if (found != null) {
            dao.put(PlaceRow(Places.cell(lat, lng), found.name, found.address, found.source, System.currentTimeMillis()))
        }
        found
    }

    suspend fun clearCache() = dao.clear()

    private fun amap(key: String, lat: Double, lng: Double): Place? {
        val (gLat, gLng) = Places.wgsToGcj(lat, lng)
        val url = amapBase.toHttpUrl().newBuilder()
            .addQueryParameter("key", key)
            .addQueryParameter("location", String.format(Locale.US, "%.6f,%.6f", gLng, gLat))
            .addQueryParameter("extensions", "all").addQueryParameter("radius", "200").addQueryParameter("roadlevel", "0")
            .build()
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("高德：HTTP ${r.code}")
            return Places.parseAmap(r.body?.string().orEmpty())
        }
    }

    @Suppress("DEPRECATION")
    private fun system(lat: Double, lng: Double): Place? {
        if (!Geocoder.isPresent()) throw IllegalStateException("手机没有系统地理编码服务，请填写高德 Key")
        val a = Geocoder(app, Locale.CHINA).getFromLocation(lat, lng, 1)?.firstOrNull() ?: return null
        val address = (0..a.maxAddressLineIndex).joinToString(" ") { a.getAddressLine(it) }.ifBlank {
            listOfNotNull(a.adminArea, a.locality, a.subLocality, a.thoroughfare, a.subThoroughfare).joinToString("")
        }
        val name = a.featureName?.takeIf { it.isNotBlank() && it != a.subThoroughfare } ?: a.thoroughfare ?: a.subLocality ?: address
        if (name.isBlank()) return null
        return Place(name, address, "系统")
    }
}
