package app.resound.location

import android.content.Context
import android.location.Geocoder
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
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
import org.json.JSONArray
import org.json.JSONObject
import app.resound.location.Places.toPlace

/** 一个约 100 米格子的地名缓存；同一处只查一次。 */
/** 自动查到的按格子缓存；手动改的 source=手动，带坐标，方圆 150 米内都用它。 */
@Entity(tableName = "location_places")
data class PlaceRow(
    @PrimaryKey val cell: String,
    val name: String,
    val address: String,
    val source: String,
    val at: Long,
    val lat: Double? = null,
    val lng: Double? = null,
    @androidx.room.ColumnInfo(defaultValue = "0") val home: Boolean = false,
)

@Dao
interface PlaceDao {
    @Query("SELECT * FROM location_places WHERE cell = :cell") suspend fun get(cell: String): PlaceRow?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(row: PlaceRow)
    /** 只清自动查到的；手动改的保留。 */
    @Query("DELETE FROM location_places WHERE source != '手动'") suspend fun clear()
    @Query("SELECT * FROM location_places WHERE source = '手动' ORDER BY at DESC") fun manual(): kotlinx.coroutines.flow.Flow<List<PlaceRow>>
    @Query("SELECT * FROM location_places WHERE source = '手动'") suspend fun manualNow(): List<PlaceRow>
    @Query("UPDATE location_places SET home = 0 WHERE home = 1") suspend fun clearHome()
    @Query("DELETE FROM location_places WHERE cell = :cell") suspend fun delete(cell: String)
}

object PlaceSchema {
    /** 地名缓存表；addManual 再加坐标和“家”。 */
    fun create(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `location_places` (`cell` TEXT NOT NULL, `name` TEXT NOT NULL, `address` TEXT NOT NULL, `source` TEXT NOT NULL, `at` INTEGER NOT NULL, PRIMARY KEY(`cell`))")
    }
    fun addManual(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE location_places ADD COLUMN lat REAL")
        db.execSQL("ALTER TABLE location_places ADD COLUMN lng REAL")
        db.execSQL("ALTER TABLE location_places ADD COLUMN home INTEGER NOT NULL DEFAULT 0")
    }
}

data class Place(val name: String, val address: String, val source: String, val home: Boolean = false) {
    val manual: Boolean get() = source == Places.MANUAL
}

object Places {
    const val MANUAL = "手动"

    /** 离这个坐标最近、且在停留半径内的手动地点。 */
    fun nearestManual(rows: List<PlaceRow>, lat: Double, lng: Double): PlaceRow? = rows
        .filter { it.lat != null && it.lng != null }
        .map { it to Track.meters(it.lat!!, it.lng!!, lat, lng) }
        .filter { it.second <= Track.STAY_RADIUS_M }
        .minByOrNull { it.second }?.first

    fun PlaceRow.toPlace() = Place(name, address, source, home)
    /** 三位小数约 100 米：停留点在同一处时命中同一格。 */
    fun cell(lat: Double, lng: Double) = String.format(Locale.US, "%.3f,%.3f", lat, lng)

    /** 手机给的是 WGS-84，高德要 GCJ-02（国内偏移坐标）；境外不偏移。 */
    fun wgsToGcj(lat: Double, lng: Double): Pair<Double, Double> {
        if (lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271) return lat to lng
        val a = 6378245.0; val ee = 0.00669342162296594323
        val x = lng - 105.0; val y = lat - 35.0
        var dLat = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x)) +
            (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0 +
            (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0 +
            (160.0 * sin(y / 12.0 * PI) + 320 * sin(y * PI / 30.0)) * 2.0 / 3.0
        var dLng = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x)) +
            (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0 +
            (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0 +
            (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0
        val radLat = lat / 180.0 * PI
        var magic = sin(radLat); magic = 1 - ee * magic * magic
        val sqrtMagic = sqrt(magic)
        dLat = (dLat * 180.0) / ((a * (1 - ee)) / (magic * sqrtMagic) * PI)
        dLng = (dLng * 180.0) / (a / sqrtMagic * cos(radLat) * PI)
        return (lat + dLat) to (lng + dLng)
    }

    /** 解析高德逆地理编码：名字优先取所在区域（小区/园区/商场），其次最近的地点，再次街道门牌。 */
    fun parseAmap(body: String): Place? {
        val root = JSONObject(body)
        if (root.optString("status") != "1") throw IllegalStateException("高德：" + root.optString("info", "查询失败"))
        val re = root.optJSONObject("regeocode") ?: return null
        val address = re.opt("formatted_address").let { if (it is String) it else "" }
        val comp = re.optJSONObject("addressComponent")
        fun firstName(arr: JSONArray?): String? = arr?.takeIf { it.length() > 0 }?.optJSONObject(0)?.optString("name")?.takeIf { it.isNotBlank() }
        val street = comp?.optJSONObject("streetNumber")?.let { s ->
            listOf(s.opt("street"), s.opt("number")).filterIsInstance<String>().joinToString("")
        }?.takeIf { it.isNotBlank() }
        val district = comp?.opt("township")?.let { it as? String }
        val name = firstName(re.optJSONArray("aois")) ?: firstName(re.optJSONArray("pois")) ?: street ?: district ?: address
        if (name.isBlank()) return null
        return Place(name, address, "高德")
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

    suspend fun cached(lat: Double, lng: Double): Place? = with(Places) {
        nearestManual(dao.manualNow(), lat, lng)?.toPlace() ?: dao.get(cell(lat, lng))?.takeIf { it.source != MANUAL }?.toPlace()
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
        cached(lat, lng)?.let { return it }
        val cell = Places.cell(lat, lng)
        if (!settings.lookup.value) return null
        val place = withContext(Dispatchers.IO) {
            try {
                val key = amapKey()
                (if (key.isNotBlank()) amap(key, lat, lng) else system(lat, lng)).also { _lastError.value = null }
            } catch (e: Exception) {
                _lastError.value = e.message ?: "查地名失败"; null
            }
        } ?: return null
        dao.put(PlaceRow(cell, place.name, place.address, place.source, System.currentTimeMillis()))
        place
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
