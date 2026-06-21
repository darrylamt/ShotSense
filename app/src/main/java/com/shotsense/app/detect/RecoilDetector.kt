package com.shotsense.app.detect

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import com.shotsense.app.fusion.RecoilSpike
import kotlin.math.sqrt

/**
 * Recoil branch. Listens to the accelerometer at the fastest available rate on a
 * dedicated [HandlerThread], computes acceleration magnitude in g, and emits a
 * [RecoilSpike] when the jolt crosses the threshold.
 *
 * Note: phone accelerometers sample slowly (typically a few hundred Hz at best)
 * relative to the ~1-3 ms of a real firearm recoil. That is exactly why recoil
 * is used as the *confirmer* and sound is the primary trigger — we cannot count
 * on catching the full recoil waveform, only that *some* sharp jolt coincided
 * with the muzzle blast.
 */
class RecoilDetector(
    private val sensorManager: SensorManager,
    @Volatile var recoilThresholdG: Float = 2.5f,
    @Volatile var debounceMs: Long = 250,
    private val onSpike: (RecoilSpike) -> Unit,
    private val onLevels: (g: Float) -> Unit,
    private val onStatus: (status: Status) -> Unit,
) : SensorEventListener {

    data class Status(
        val hasSensor: Boolean,
        val sensorName: String,
        val sampleRateHz: Float,
        /** true when using TYPE_LINEAR_ACCELERATION (gravity already removed). */
        val gravityRemovedByHardware: Boolean,
    )

    private var handlerThread: HandlerThread? = null
    private var sensor: Sensor? = null
    private var usingLinear = false

    private var lastSpikeNanos = 0L
    private var lastEventNanos = 0L
    private var sampleRateHz = 0f

    // Slow gravity estimate for the TYPE_ACCELEROMETER fallback path.
    private val gravity = FloatArray(3)
    private var gravityInitialized = false

    @Volatile var isRunning = false
        private set

    fun start(): Boolean {
        if (isRunning) return true

        val linear = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        val raw = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        sensor = linear ?: raw
        usingLinear = linear != null

        val s = sensor
        if (s == null) {
            onStatus(Status(hasSensor = false, sensorName = "none", sampleRateHz = 0f, gravityRemovedByHardware = false))
            return false
        }

        val ht = HandlerThread("ShotSense-Recoil", Thread.MAX_PRIORITY).also { it.start() }
        handlerThread = ht
        val handler = Handler(ht.looper)

        gravityInitialized = false
        sampleRateHz = 0f
        lastEventNanos = 0L

        val ok = sensorManager.registerListener(this, s, SensorManager.SENSOR_DELAY_FASTEST, handler)
        isRunning = ok
        if (!ok) {
            Log.e(TAG, "registerListener failed for ${s.name}")
            ht.quitSafely()
            handlerThread = null
            return false
        }
        onStatus(
            Status(
                hasSensor = true,
                sensorName = s.name,
                sampleRateHz = 0f,
                gravityRemovedByHardware = usingLinear,
            ),
        )
        return true
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        runCatching { sensorManager.unregisterListener(this) }
        handlerThread?.quitSafely()
        handlerThread = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        // Use receipt time on the shared monotonic clock. SensorEvent.timestamp's
        // clock basis is device-dependent, so anchoring to elapsedRealtimeNanos here
        // keeps both branches strictly comparable for the fusion window.
        val now = SystemClock.elapsedRealtimeNanos()

        // Sample-rate estimate from successive event spacing (smoothed).
        if (lastEventNanos != 0L) {
            val dtNanos = now - lastEventNanos
            if (dtNanos > 0) {
                val inst = 1_000_000_000f / dtNanos
                sampleRateHz = if (sampleRateHz == 0f) inst else 0.9f * sampleRateHz + 0.1f * inst
            }
        }
        lastEventNanos = now

        val mag: Float = if (usingLinear) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]
            sqrt(x * x + y * y + z * z)
        } else {
            // Subtract a slow gravity baseline from raw accelerometer readings.
            if (!gravityInitialized) {
                gravity[0] = event.values[0]
                gravity[1] = event.values[1]
                gravity[2] = event.values[2]
                gravityInitialized = true
            } else {
                for (i in 0..2) {
                    gravity[i] = GRAVITY_ALPHA * gravity[i] + (1 - GRAVITY_ALPHA) * event.values[i]
                }
            }
            val lx = event.values[0] - gravity[0]
            val ly = event.values[1] - gravity[1]
            val lz = event.values[2] - gravity[2]
            sqrt(lx * lx + ly * ly + lz * lz)
        }

        val g = mag / EARTH_G
        onLevels(g)
        onStatus(
            Status(
                hasSensor = true,
                sensorName = sensor?.name ?: "?",
                sampleRateHz = sampleRateHz,
                gravityRemovedByHardware = usingLinear,
            ),
        )

        val spaced = (now - lastSpikeNanos) / 1_000_000.0 >= debounceMs
        if (g >= recoilThresholdG && spaced) {
            lastSpikeNanos = now
            onSpike(RecoilSpike(timestampNanos = now, gForce = g))
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { /* no-op */ }

    private companion object {
        const val TAG = "RecoilDetector"
        const val EARTH_G = 9.81f
        const val GRAVITY_ALPHA = 0.8f
    }
}
