package com.shotsense.app.fusion

/**
 * Sensor events feeding the [FusionEngine].
 *
 * Every event carries a timestamp on a single monotonic clock
 * (`SystemClock.elapsedRealtimeNanos()` on device). Keeping both branches on the
 * same clock is what makes the fusion window comparison meaningful. This file has
 * no Android imports so the fusion logic stays unit-testable on the JVM.
 */

/** A loud, impulsive sound event from the microphone branch. */
data class SoundSpike(
    /** Monotonic timestamp in nanoseconds (elapsedRealtimeNanos basis). */
    val timestampNanos: Long,
    /** Peak sample magnitude for the triggering buffer, normalized 0..1. */
    val peak: Float,
    /** energy / background ratio at the moment of the spike. */
    val ratio: Float,
)

/** A sharp acceleration (recoil) event from the accelerometer branch. */
data class RecoilSpike(
    /** Monotonic timestamp in nanoseconds (elapsedRealtimeNanos basis). */
    val timestampNanos: Long,
    /** Acceleration magnitude expressed in g. */
    val gForce: Float,
)

/** A confirmed shot emitted by the [FusionEngine]. */
data class ConfirmedShot(
    /** Monotonic timestamp of confirmation (the later of the two contributing spikes). */
    val timestampNanos: Long,
    /** Running count of confirmed shots since the last [FusionEngine.reset]. */
    val shotCount: Int,
    /** Sound peak of the contributing [SoundSpike]. */
    val soundPeak: Float,
    /** Recoil g of the contributing [RecoilSpike], or 0 when confirmed by sound alone. */
    val recoilG: Float,
    /** true = confirmed by sound + recoil; false = sound-only confirmation. */
    val confirmedByRecoil: Boolean,
)
