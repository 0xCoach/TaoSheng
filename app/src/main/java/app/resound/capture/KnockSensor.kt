package app.resound.capture

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * 敲敲传感器：加速度计 → KnockDetector；检测命中回调 onKnock。
 * 首选线性加速度计（系统融合已去重力，敲击是干净尖峰）；没有则退回裸加速度计。
 * 采样用 FASTEST：敲击尖峰只有十几毫秒，50Hz 会把峰顶漏在中间——本机实测 50Hz 下
 * 增量被压到贴阈值（~9），是"偶尔不灵"的主因；高采样抓住真实峰顶后裕量很大。
 * 注意（如实）：进程活着且 CPU 醒着才收得到事件；息屏+Doze 下非唤醒传感器可能延迟/暂停——
 * 敲敲启动属"尽力而为"，录音中/暂停中的敲敲暂停-恢复是可靠的（服务前台常驻）。
 */
class KnockSensor(
    context: Context,
    private val onKnock: (latencyMs: Long) -> Unit,
) : SensorEventListener {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = manager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        ?: manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    /** 线性加速度静放≈0，裸加速度计≈9.8——诊断日志换算"超静息增量"用。 */
    private val restMag: Float get() =
        if (sensor?.type == Sensor.TYPE_LINEAR_ACCELERATION) 0f else SensorManager.GRAVITY_EARTH
    private var detector = KnockDetector()
    private var joltPeak = 0f
    private var joltPeakAt = 0L
    var listening = false; private set

    val available: Boolean get() = sensor != null

    /** 设置变更即时生效：已在监听也重建判定器（灵敏度变了也要换）。 */
    fun updateConfig(taps: Int, sensitivity: Int) {
        detector = KnockDetector(taps, sensitivity)
    }

    fun start(taps: Int, sensitivity: Int) {
        if (listening) { updateConfig(taps, sensitivity); return } // 已在监听：即时应用新配置
        val s = sensor ?: return
        detector = KnockDetector(taps, sensitivity)
        // FASTEST + 100ms 攒批上限：抓真实峰顶；线性加速度是虚拟传感器无 FIFO，该参数被忽略。
        listening = manager.registerListener(this, s, SensorManager.SENSOR_DELAY_FASTEST, 100_000)
        if (listening) {
            KnockDiag.registerCount.incrementAndGet()
            KnockDiag.listening = true
            if (KnockDiag.windowStart == 0L)
                KnockDiag.windowStart = android.os.SystemClock.elapsedRealtime()
            android.util.Log.i("KnockDiag", "sensor register taps=$taps sens=$sensitivity type=${s.type} ${KnockDiag.summary(android.os.SystemClock.elapsedRealtime())}")
        }
    }

    fun stop() {
        if (!listening) return
        manager.unregisterListener(this); listening = false
        KnockDiag.unregisterCount.incrementAndGet()
        KnockDiag.listening = false
        android.util.Log.i("KnockDiag", "sensor unregister ${KnockDiag.summary(android.os.SystemClock.elapsedRealtime())}")
    }

    override fun onSensorChanged(event: SensorEvent) {
        // 用 event.timestamp（采样时刻）而非送达时刻：攒批送达时同批样本几乎同时刻，
        // 送达时间会把两次真实敲击压进余震抑制窗口被吞掉。
        if (event.sensor.type != sensor?.type) return
        val x = event.values[0]; val y = event.values[1]; val z = event.values[2]
        val mag = kotlin.math.sqrt(x * x + y * y + z * z)
        val at = event.timestamp / 1_000_000L
        KnockDiag.sensorEvents.incrementAndGet()
        // 诊断：按"凸起簇"记峰值——尖峰过去 150ms 无后续则落一条 peak 日志，看真实峰顶分布。
        val dev = mag - restMag
        if (dev > 4f) {
            if (dev > joltPeak) joltPeak = dev
            joltPeakAt = at
        } else if (joltPeak > 0f && at - joltPeakAt > 150) {
            android.util.Log.i("KnockSensor", "jolt peak=%.1f".format(joltPeak))
            joltPeak = 0f
            KnockDiag.joltPeaks.incrementAndGet()
        }
        if (detector.onSample(at, x, y, z)) {
            KnockDiag.detectorCooldownFiltered = detector.cooldownFiltered
            android.util.Log.i("KnockSensor", "knock detected")
            onKnock(android.os.SystemClock.elapsedRealtime() - at)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
