package com.shotsense.app.fusion

import kotlin.math.abs
import kotlin.math.max

/**
 * Pure-Kotlin fusion core. All timing decisions for declaring a confirmed shot
 * live here so they can be unit-tested without Android.
 *
 * Rules:
 *  - Sound is the primary trigger. A confirmed shot always needs a [SoundSpike].
 *  - When [requireRecoil] is true, a [RecoilSpike] must also have occurred within
 *    [fusionWindowMs] of the sound spike (the two-sensor requirement that keeps
 *    ordinary loud noises from causing false alarms).
 *  - When [requireRecoil] is false, a sound spike alone confirms (useful for
 *    bench testing on hardware with no usable accelerometer).
 *  - [debounceMs] suppresses repeat confirmations that happen too close together.
 *  - Contributing spikes are consumed on confirmation so a single recoil cannot
 *    pair with multiple sounds (or vice versa).
 *
 * Not thread-safe by itself; the owner (DetectionService) funnels events in
 * from the audio and sensor threads under its own synchronization.
 */
class FusionEngine(
    var fusionWindowMs: Long = 100,
    var debounceMs: Long = 250,
    var requireRecoil: Boolean = true,
) {
    private var lastSound: SoundSpike? = null
    private var lastRecoil: RecoilSpike? = null
    private var lastConfirmedNanos: Long? = null

    /** Running count of confirmed shots since the last [reset]. */
    var confirmedCount: Int = 0
        private set

    /** Feed a sound spike. Returns a [ConfirmedShot] if this completes a shot. */
    fun onSoundSpike(spike: SoundSpike): ConfirmedShot? {
        lastSound = spike
        return tryConfirm(spike.timestampNanos)
    }

    /** Feed a recoil spike. Returns a [ConfirmedShot] if this completes a shot. */
    fun onRecoilSpike(spike: RecoilSpike): ConfirmedShot? {
        lastRecoil = spike
        // Recoil never confirms on its own; it only completes a pending sound spike.
        if (!requireRecoil) return null
        return tryConfirm(spike.timestampNanos)
    }

    private fun tryConfirm(@Suppress("UNUSED_PARAMETER") eventNanos: Long): ConfirmedShot? {
        val sound = lastSound ?: return null

        if (requireRecoil) {
            val recoil = lastRecoil ?: return null
            val deltaMs = abs(sound.timestampNanos - recoil.timestampNanos) / NANOS_PER_MS
            if (deltaMs > fusionWindowMs) return null
            val confirmNanos = max(sound.timestampNanos, recoil.timestampNanos)
            if (!passesDebounce(confirmNanos)) return null
            return confirm(confirmNanos, sound, recoil, confirmedByRecoil = true)
        }

        // Sound-only mode.
        val confirmNanos = sound.timestampNanos
        if (!passesDebounce(confirmNanos)) return null
        return confirm(confirmNanos, sound, lastRecoil, confirmedByRecoil = false)
    }

    private fun passesDebounce(confirmNanos: Long): Boolean {
        val last = lastConfirmedNanos ?: return true
        val deltaMs = (confirmNanos - last) / NANOS_PER_MS
        return deltaMs >= debounceMs
    }

    private fun confirm(
        confirmNanos: Long,
        sound: SoundSpike,
        recoil: RecoilSpike?,
        confirmedByRecoil: Boolean,
    ): ConfirmedShot {
        lastConfirmedNanos = confirmNanos
        confirmedCount += 1
        // Consume contributing spikes so they are not reused for another confirmation.
        lastSound = null
        lastRecoil = null
        return ConfirmedShot(
            timestampNanos = confirmNanos,
            shotCount = confirmedCount,
            soundPeak = sound.peak,
            recoilG = recoil?.gForce ?: 0f,
            confirmedByRecoil = confirmedByRecoil,
        )
    }

    /** Clear all state including the confirmed-shot counter. */
    fun reset() {
        lastSound = null
        lastRecoil = null
        lastConfirmedNanos = null
        confirmedCount = 0
    }

    private companion object {
        const val NANOS_PER_MS = 1_000_000.0
    }
}
