package app.convoyage.data

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.sqrt

/**
 * Lean angle from the physics of a turn, not from the phone's tilt.
 *
 * In a steady turn the apparent gravity stays in the bike's own plane, so an accelerometer alone reads "upright"
 * whatever the lean, and the phone's mount angle is unknown anyway. What does give it away is the turn itself:
 * tan(lean) = v * omega / g, with v the road speed and omega the turn rate about the vertical. The gyro measures the
 * turn rate about the bike's own up axis (the slow-filtered accelerometer), which is omega * cos(lean), so
 * tan(lean) * cos(lean) = sin(lean) = v * measured / g exactly.
 */
object LeanMath {
    const val G = 9.80665

    /** Below this road speed there is no meaningful lean (walking the bike, filtering through traffic). */
    const val MIN_MPS = 3.0

    /** Above this the reading is noise: road bikes run out of tyre around 55-60 degrees. */
    const val MAX_DEG = 65.0

    /**
     * Signed lean in degrees, positive to the left (a counter-clockwise turn seen from above), or 0 when too slow.
     * [yawRate] is the gyro rate about the bike's up axis in rad/s.
     */
    fun leanDeg(speedMps: Double, yawRate: Double): Double {
        if (speedMps < MIN_MPS) return 0.0
        val s = (speedMps * yawRate / G).coerceIn(-1.0, 1.0)
        return Math.toDegrees(asin(s)).coerceIn(-MAX_DEG, MAX_DEG)
    }

    /** Rate of turn about [up] (any length, need not be normalised); 0 if [up] is degenerate. */
    fun yawRate(gyro: FloatArray, up: FloatArray): Double {
        val n = sqrt((up[0] * up[0] + up[1] * up[1] + up[2] * up[2]).toDouble())
        if (n < 1e-3) return 0.0
        return (gyro[0] * up[0] + gyro[1] * up[1] + gyro[2] * up[2]) / n
    }
}

/**
 * The live lean angle while something holds it ([acquire]/[release], counted like the location tracker).
 * [lean] is null while nobody holds it or the phone has no gyroscope, so a ride's max lean is only measured
 * in Ride mode, where the phone is on the bike.
 */
class LeanSensor(context: Context, private val speedMps: () -> Double?) : SensorEventListener {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager?
    private val gyro = sm?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val accel = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val _lean = MutableStateFlow<Double?>(null)
    val lean: StateFlow<Double?> = _lean.asStateFlow()
    val available: Boolean get() = gyro != null && accel != null

    private var holders = 0
    private val up = FloatArray(3)
    private var haveUp = false
    private var smooth = 0.0
    private var lastNs = 0L

    @Synchronized
    fun acquire() {
        if (holders++ > 0 || !available) return
        haveUp = false; smooth = 0.0; lastNs = 0L
        sm!!.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME)
        sm.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME)
        _lean.value = 0.0
    }

    @Synchronized
    fun release() {
        if (holders == 0) return
        if (--holders > 0) return
        sm?.unregisterListener(this)
        _lean.value = null
    }

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                // About a 1.5 s time constant at ~50 Hz: bumps and braking wash out, the bike's up axis stays.
                val a = if (haveUp) 0.013f else 1f
                for (i in 0..2) up[i] += a * (e.values[i] - up[i])
                haveUp = true
            }
            Sensor.TYPE_GYROSCOPE -> {
                if (!haveUp) return
                val dt = if (lastNs == 0L) 0.02 else ((e.timestamp - lastNs) / 1e9).coerceIn(0.0, 0.2)
                lastNs = e.timestamp
                val raw = LeanMath.leanDeg(speedMps() ?: 0.0, LeanMath.yawRate(e.values, up))
                // ~0.25 s smoothing: the meter should follow a flick but not every vibration.
                val k = (dt / (0.25 + dt))
                smooth += k * (raw - smooth)
                val v = Math.round(smooth * 2) / 2.0
                if (abs(v - (_lean.value ?: 0.0)) >= 0.5) _lean.value = v
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
