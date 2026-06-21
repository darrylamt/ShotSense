package com.shotsense.app.data

import android.content.Context
import android.util.Log
import com.shotsense.app.fusion.ConfirmedShot
import com.shotsense.app.fusion.RecoilSpike
import com.shotsense.app.fusion.SoundSpike
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Appends every sound spike, recoil spike, and confirmed shot to a CSV in the
 * app's external files directory. This lets thresholds be tuned from real range
 * data instead of guesswork.
 *
 * Columns: timestamp,type,peak,ratio,gforce,confirmed,lat,lng
 *
 * Thread-safe: called from the audio and sensor threads as well as the service.
 */
class CalibrationLogger(private val context: Context) {

    private val lock = Any()

    val file: File
        get() = File(context.getExternalFilesDir(null), FILE_NAME)

    private fun ensureHeader() {
        val f = file
        if (!f.exists() || f.length() == 0L) {
            f.parentFile?.mkdirs()
            f.appendText("timestamp,type,peak,ratio,gforce,confirmed,lat,lng\n")
        }
    }

    fun logSound(spike: SoundSpike) = append(
        type = "SOUND",
        peak = spike.peak,
        ratio = spike.ratio,
        gforce = null,
        confirmed = false,
    )

    fun logRecoil(spike: RecoilSpike) = append(
        type = "RECOIL",
        peak = null,
        ratio = null,
        gforce = spike.gForce,
        confirmed = false,
    )

    fun logConfirmed(shot: ConfirmedShot, lat: Double?, lng: Double?) = append(
        type = "CONFIRMED",
        peak = shot.soundPeak,
        ratio = null,
        gforce = shot.recoilG,
        confirmed = true,
        lat = lat,
        lng = lng,
    )

    private fun append(
        type: String,
        peak: Float?,
        ratio: Float?,
        gforce: Float?,
        confirmed: Boolean,
        lat: Double? = null,
        lng: Double? = null,
    ) {
        synchronized(lock) {
            try {
                ensureHeader()
                val ts = TIME.format(Instant.now())
                val line = buildString {
                    append(ts).append(',')
                    append(type).append(',')
                    append(peak?.let { "%.5f".format(it) } ?: "").append(',')
                    append(ratio?.let { "%.3f".format(it) } ?: "").append(',')
                    append(gforce?.let { "%.4f".format(it) } ?: "").append(',')
                    append(confirmed).append(',')
                    append(lat?.let { "%.6f".format(it) } ?: "").append(',')
                    append(lng?.let { "%.6f".format(it) } ?: "")
                    append('\n')
                }
                file.appendText(line)
            } catch (e: Exception) {
                Log.e(TAG, "CSV append failed", e)
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            runCatching { if (file.exists()) file.delete() }
        }
    }

    private companion object {
        const val TAG = "CalibrationLogger"
        const val FILE_NAME = "shotsense_calibration.csv"
        val TIME: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault())
    }
}
