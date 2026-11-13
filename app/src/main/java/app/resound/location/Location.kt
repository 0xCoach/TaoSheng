package app.resound.location

import androidx.sqlite.execSQL
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import app.resound.digest.SecretStore
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

object Track {
    private val rules = TrackRules(Math::toRadians)
    const val STAY_RADIUS_M = TrackRules.STAY_RADIUS_M
    const val STAY_MIN_MS = TrackRules.STAY_MIN_MS
    const val MAX_ACCURACY_M = TrackRules.MAX_ACCURACY_M
    fun meters(aLat: Double, aLng: Double, bLat: Double, bLng: Double) = rules.meters(aLat, aLng, bLat, bLng)
    fun day(all: List<LocationPoint>) = rules.day(all)
    fun stays(points: List<LocationPoint>) = rules.stays(points)
    fun distance(points: List<LocationPoint>) = rules.distance(points)
}

/** 开关与权限状态。记录跟随收音前台服务进行。 */
class LocationSettings(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("location", Context.MODE_PRIVATE)
    private val _enabled = MutableStateFlow(prefs.getBoolean("enabled", false))
    val enabled: StateFlow<Boolean> = _enabled
    fun setEnabled(on: Boolean) { prefs.edit().putBoolean("enabled", on).apply(); _enabled.value = on }
    fun hasForeground() = granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)
    fun hasBackground() = granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    /** 手机“位置信息”总开关；关着时有权限也拿不到任何位置。 */
    fun systemEnabled() = runCatching { androidx.core.location.LocationManagerCompat.isLocationEnabled(app.getSystemService(android.location.LocationManager::class.java)) }.getOrDefault(true)
    /** 开关打开且至少有前台定位权限时，收音服务才带上定位类型。 */
    fun wanted() = _enabled.value && hasForeground()
    private fun granted(p: String) = ContextCompat.checkSelfPermission(app, p) == PackageManager.PERMISSION_GRANTED

    /** 显示地名：开着时会把停留处坐标发给高德或系统地理服务查地址。 */
    private val _lookup = MutableStateFlow(prefs.getBoolean("lookup", true))
    val lookup: StateFlow<Boolean> = _lookup
    fun setLookup(on: Boolean) { prefs.edit().putBoolean("lookup", on).apply(); _lookup.value = on }

    /** 高德 Web 服务 Key：系统安全区加密保存，不回显。 */
    private val secrets by lazy { SecretStore(app, "location-secrets", "resound-location-aes-v1") }
    private val _hasAmapKey = MutableStateFlow(app.getSharedPreferences("location-secrets", Context.MODE_PRIVATE).contains("amap"))
    val hasAmapKey: StateFlow<Boolean> = _hasAmapKey
    fun amapKey(): String = if (_hasAmapKey.value) runCatching { secrets.read("amap") }.getOrDefault("") else ""
    fun setAmapKey(key: String?) {
        if (key.isNullOrBlank()) secrets.remove("amap") else secrets.write("amap", key.trim())
        _hasAmapKey.value = !key.isNullOrBlank()
    }
}

/** 系统定位回调 → 过滤 → 入库。不依赖谷歌服务，小米国行可用。 */
class LocationTracker(context: Context, private val dao: LocationDao, private val scope: CoroutineScope) {
    private val app = context.applicationContext
    private val manager = app.getSystemService(LocationManager::class.java)
    private var running = false
    private var lastSaved: LocationPoint? = null
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError
    private fun save(loc: android.location.Location) {
        val point = LocationPoint(at = loc.time.takeIf { it > 0 } ?: System.currentTimeMillis(), lat = loc.latitude, lng = loc.longitude,
            accuracy = if (loc.hasAccuracy()) loc.accuracy else 999f, provider = loc.provider ?: "?")
        synchronized(this) {
            if (!keep(lastSaved, point)) return
            lastSaved = point
        }
        scope.launch(Dispatchers.IO) { runCatching { dao.insert(point) } }
    }
    private val listener = LocationListener { save(it) }
    private var lastFixAsk = 0L

    /**
     * 立刻要一次位置（给完权限、打开开关、打开系统定位后调用），不等第一次 5 分钟回调。
     * 先用手机 2 分钟内的缓存位置，再向系统要一次最新的；30 秒内不重复要。
     */
    @SuppressLint("MissingPermission")
    fun fixNow(force: Boolean = false) {
        val granted = listOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { ContextCompat.checkSelfPermission(app, it) == PackageManager.PERMISSION_GRANTED }
        if (!granted || !androidx.core.location.LocationManagerCompat.isLocationEnabled(manager)) return
        val now = android.os.SystemClock.elapsedRealtime()
        synchronized(this) { if (!force && now - lastFixAsk < 30_000) return; lastFixAsk = now }
        if (lastSaved == null) scope.launch(Dispatchers.IO) { lastSaved = lastSaved ?: runCatching { dao.latest() }.getOrNull() }
        val providers = manager.getProviders(true)
        try {
            providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
                .filter { System.currentTimeMillis() - it.time < 2 * 60_000 }.minByOrNull { if (it.hasAccuracy()) it.accuracy else 999f }
                ?.let(::save)
            val provider = when {
                Build.VERSION.SDK_INT >= 31 && LocationManager.FUSED_PROVIDER in providers -> LocationManager.FUSED_PROVIDER
                LocationManager.NETWORK_PROVIDER in providers -> LocationManager.NETWORK_PROVIDER
                LocationManager.GPS_PROVIDER in providers -> LocationManager.GPS_PROVIDER
                else -> return
            }
            androidx.core.location.LocationManagerCompat.getCurrentLocation(manager, provider, null as android.os.CancellationSignal?,
                ContextCompat.getMainExecutor(app)) { loc -> loc?.let(::save) }
        } catch (_: SecurityException) { }
    }

    @SuppressLint("MissingPermission")
    @Synchronized fun start() {
        if (running) return
        val providers = manager.allProviders
        val use = if (Build.VERSION.SDK_INT >= 31 && LocationManager.FUSED_PROVIDER in providers) listOf(LocationManager.FUSED_PROVIDER)
            else listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER).filter { it in providers }
        try {
            for (p in use) manager.requestLocationUpdates(p, INTERVAL_MS, 0f, listener, Looper.getMainLooper())
            running = use.isNotEmpty()
            _lastError.value = if (use.isEmpty()) "手机没有可用的定位来源" else null
            if (running) fixNow(force = true)
        } catch (e: SecurityException) {
            manager.removeUpdates(listener); running = false
            _lastError.value = "没有定位权限"
        }
    }

    @Synchronized fun stop() {
        if (!running) return
        manager.removeUpdates(listener); running = false
    }

    val isRunning: Boolean get() = running

    companion object {
        const val INTERVAL_MS = 5 * 60_000L
        /** 去重用的最小移动距离；向系统请求时不设距离门槛，原地不动也每 5 分钟记一点，才能算出停留。 */
        const val MIN_DISTANCE_M = 50f

        /** 两个来源会同时回调：一分钟内且没走出精度范围、精度也没更好，就丢掉。 */
        fun keep(last: LocationPoint?, next: LocationPoint): Boolean {
            if (next.accuracy > Track.MAX_ACCURACY_M) return false
            if (last == null) return true
            if (next.at < last.at) return false
            val moved = Track.meters(last.lat, last.lng, next.lat, next.lng)
            if (next.at - last.at < 60_000 && moved < maxOf(next.accuracy.toDouble(), MIN_DISTANCE_M.toDouble()) && next.accuracy >= last.accuracy) return false
            return true
        }
    }
}

class LocationRepository(val settings: LocationSettings, val tracker: LocationTracker, private val dao: LocationDao, val places: PlaceResolver) {
    private fun range(date: LocalDate): Pair<Long, Long> {
        val zone = ZoneId.systemDefault()
        return date.atStartOfDay(zone).toInstant().toEpochMilli() to date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    }
    fun day(date: LocalDate): Flow<List<LocationPoint>> = range(date).let { (a, b) -> dao.between(a, b) }
    suspend fun clear(date: LocalDate): Int = range(date).let { (a, b) -> dao.deleteBetween(a, b) }
    fun latest(): Flow<LocationPoint?> = dao.latestFlow()

    companion object {
        fun create(context: Context, database: app.resound.data.CaptureDatabase, scope: CoroutineScope): LocationRepository {
            val dao = database.location()
            val settings = LocationSettings(context)
            return LocationRepository(settings, LocationTracker(context, dao, scope), dao, PlaceResolver(context, database.places(), settings))
        }
    }
}
