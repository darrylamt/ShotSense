package com.shotsense.app.detect

import android.Manifest
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresPermission
import com.shotsense.app.fusion.SoundSpike
import kotlin.math.abs
import kotlin.math.max

/**
 * Microphone branch. Owns an [AudioRecord], reads raw PCM on a dedicated thread,
 * computes a per-buffer peak and energy, tracks a slow ambient background, and
 * emits a [SoundSpike] for loud impulsive events.
 *
 * The unprocessed / voice-recognition audio source is used deliberately to avoid
 * the phone's automatic gain control and noise suppression, which would flatten
 * the very impulse we are trying to detect.
 */
class AudioDetector(
    @Volatile var saturationThreshold: Float = 0.88f,
    @Volatile var impulseRatioK: Float = 12f,
    @Volatile var debounceMs: Long = 250,
    private val onSpike: (SoundSpike) -> Unit,
    private val onLevels: (peak: Float, ratio: Float) -> Unit,
) {
    @Volatile private var running = false
    private var thread: Thread? = null
    private var record: AudioRecord? = null

    /** Slow EMA of buffer energy, used as the noise floor. */
    @Volatile private var background = BACKGROUND_FLOOR
    private var lastSpikeNanos = 0L

    /** Description of the audio source that was actually opened (for the UI). */
    @Volatile var sourceLabel: String = "—"
        private set

    val isRunning: Boolean get() = running

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start(): Boolean {
        if (running) return true
        val rec = openRecord() ?: return false
        record = rec
        running = true
        thread = Thread({ loop(rec) }, "ShotSense-Audio").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        return true
    }

    fun stop() {
        running = false
        thread?.let { runCatching { it.join(500) } }
        thread = null
        record?.let {
            runCatching { if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop() }
            runCatching { it.release() }
        }
        record = null
    }

    @SuppressLint("MissingPermission")
    private fun openRecord(): AudioRecord? {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuf <= 0) {
            Log.e(TAG, "Invalid min buffer size: $minBuf")
            return null
        }
        // Generous buffer to avoid overruns; reads still happen in small frames.
        val bufferBytes = max(minBuf, FRAMES_PER_READ * 2 * 4)

        for ((source, label) in candidateSources()) {
            val rec = runCatching {
                AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(CHANNEL)
                            .setEncoding(ENCODING)
                            .build(),
                    )
                    .setBufferSizeInBytes(bufferBytes)
                    .build()
            }.getOrNull()

            if (rec != null && rec.state == AudioRecord.STATE_INITIALIZED) {
                sourceLabel = label
                Log.i(TAG, "Opened AudioRecord with source=$label")
                return rec
            }
            rec?.release()
        }
        Log.e(TAG, "Failed to open AudioRecord with any source")
        return null
    }

    /** Preferred sources first; the default processed MIC path is the last resort. */
    private fun candidateSources(): List<Pair<Int, String>> = listOf(
        MediaRecorder.AudioSource.UNPROCESSED to "UNPROCESSED",
        MediaRecorder.AudioSource.VOICE_RECOGNITION to "VOICE_RECOGNITION",
        MediaRecorder.AudioSource.MIC to "MIC",
    )

    private fun loop(rec: AudioRecord) {
        val buffer = ShortArray(FRAMES_PER_READ)
        runCatching { rec.startRecording() }.onFailure {
            Log.e(TAG, "startRecording failed", it)
            running = false
            return
        }

        while (running) {
            val n = rec.read(buffer, 0, buffer.size)
            // Timestamp at read time on the shared monotonic clock so the fusion
            // window comparison against the recoil branch is valid.
            val now = SystemClock.elapsedRealtimeNanos()
            if (n <= 0) continue

            var peak = 0f
            var sumSq = 0.0
            for (i in 0 until n) {
                val s = buffer[i] / 32768f
                val a = abs(s)
                if (a > peak) peak = a
                sumSq += (s * s).toDouble()
            }
            val energy = (sumSq / n).toFloat()

            // Only let quiet buffers raise the floor, so loud events don't desensitize us.
            if (peak < BACKGROUND_UPDATE_PEAK_LIMIT) {
                background = ((1 - BACKGROUND_ALPHA) * background + BACKGROUND_ALPHA * energy)
                    .coerceAtLeast(BACKGROUND_FLOOR)
            }

            val ratio = energy / background
            onLevels(peak, ratio)

            val spikedByLevel = peak >= saturationThreshold && ratio >= impulseRatioK
            val spacedOut = (now - lastSpikeNanos) / 1_000_000.0 >= debounceMs
            if (spikedByLevel && spacedOut) {
                lastSpikeNanos = now
                onSpike(SoundSpike(timestampNanos = now, peak = peak, ratio = ratio))
            }
        }
    }

    private companion object {
        const val TAG = "AudioDetector"
        const val SAMPLE_RATE = 44100
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val FRAMES_PER_READ = 1024

        const val BACKGROUND_ALPHA = 0.05f
        const val BACKGROUND_FLOOR = 1e-7f
        const val BACKGROUND_UPDATE_PEAK_LIMIT = 0.30f
    }
}
