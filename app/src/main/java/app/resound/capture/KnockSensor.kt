package app.resound.capture

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock

/**
 * 敲敲传感器：加速度计 → KnockDetector；检测命中回调 onKnock。
 * 只在开关打开时注册；SENSOR_DELAY_GAME(~50Hz)够识别 80ms 级敲击，不追求高采样省电。
 * 注意（如实）：进程活着且 CPU 醒着才收得到事件；息屏+Doze 下非唤醒传感器可能延迟/暂停——
 * 敲敲启动属"尽力而为"，录音中/暂停中的敲敲暂停-恢复是可靠的（服务前台常驻）。
 */
class KnockSensor(
    context: Context,
    private val onKnock: () -> Unit,
) : SensorEventListener {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accel: Sensor? = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var detector = KnockDetector()
    var listening = false; private set

    val available: Boolean get() = accel != null

    fun updateConfig(taps: Int, sensitivity: Int) {
        if (detector.tapsRequired != taps) detector = KnockDetector(taps, sensitivity)
    }

    fun start(taps: Int, sensitivity: Int) {
        if (listening || accel == null) return
        detector = KnockDetector(taps, sensitivity)
        // maxReportLatency 200ms：允许硬件 FIFO 攒批，息屏轻 Doze 下仍能（延迟地）送达。
        listening = manager.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME, 200_000)
    }

    fun stop() {
        if (!listening) return
        manager.unregisterListener(this); listening = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER &&
            detector.onSample(
                SystemClock.elapsedRealtime(),
                event.values[0], event.values[1], event.values[2])
        ) onKnock()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
