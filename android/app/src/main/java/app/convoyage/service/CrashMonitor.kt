package app.convoyage.service

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import app.convoyage.data.CrashDetector
import app.convoyage.data.LocationTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/** Feeds the accelerometer and GPS speed into a [CrashDetector] while started. */
class CrashMonitor(
    context: Context,
    private val tracker: LocationTracker,
    private val onDetected: (peakG: Double) -> Unit,
) : SensorEventListener {
    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val detector = CrashDetector()
    private var job: Job? = null
    val running: Boolean get() = job != null

    fun start(scope: CoroutineScope) {
        if (job != null) return
        detector.reset()
        sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        job = scope.launch {
            tracker.fix.collect { f ->
                if (f != null && detector.onSpeed(f.timeMs, f.speedMps)) onDetected(detector.peakG)
            }
        }
    }

    fun stop() {
        sensors.unregisterListener(this)
        job?.cancel()
        job = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        val g = sqrt(event.values[0] * event.values[0] + event.values[1] * event.values[1] + event.values[2] * event.values[2]) /
            CrashDetector.STANDARD_GRAVITY.toFloat()
        detector.onAccel(System.currentTimeMillis(), g.toDouble())
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
