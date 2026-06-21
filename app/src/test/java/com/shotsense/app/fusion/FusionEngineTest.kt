package com.shotsense.app.fusion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FusionEngineTest {

    private fun ms(value: Long): Long = value * 1_000_000L

    private fun sound(atMs: Long, peak: Float = 0.95f, ratio: Float = 20f) =
        SoundSpike(timestampNanos = ms(atMs), peak = peak, ratio = ratio)

    private fun recoil(atMs: Long, g: Float = 3.0f) =
        RecoilSpike(timestampNanos = ms(atMs), gForce = g)

    // --- Required acceptance cases -----------------------------------------

    @Test
    fun bothSpikesInsideWindow_confirm() {
        val engine = FusionEngine(fusionWindowMs = 100, debounceMs = 250, requireRecoil = true)

        assertNull("sound alone should not confirm in requireRecoil mode", engine.onSoundSpike(sound(0)))
        val shot = engine.onRecoilSpike(recoil(50))

        assertEquals(1, engine.confirmedCount)
        assertTrue(shot != null && shot.confirmedByRecoil)
        assertEquals(1, shot!!.shotCount)
        assertEquals(0.95f, shot.soundPeak, 1e-6f)
        assertEquals(3.0f, shot.recoilG, 1e-6f)
        // Confirmation timestamp is the later of the two contributing spikes.
        assertEquals(ms(50), shot.timestampNanos)
    }

    @Test
    fun spikesOutsideWindow_doNotConfirm() {
        val engine = FusionEngine(fusionWindowMs = 100, debounceMs = 250, requireRecoil = true)

        assertNull(engine.onSoundSpike(sound(0)))
        // 200ms apart > 100ms window.
        assertNull(engine.onRecoilSpike(recoil(200)))
        assertEquals(0, engine.confirmedCount)
    }

    @Test
    fun windowIsInclusiveAtBoundary() {
        val engine = FusionEngine(fusionWindowMs = 100, debounceMs = 250, requireRecoil = true)

        assertNull(engine.onSoundSpike(sound(0)))
        // Exactly at the window edge should still confirm.
        val shot = engine.onRecoilSpike(recoil(100))
        assertTrue(shot != null)
    }

    @Test
    fun debounceBlocksRapidRepeats() {
        val engine = FusionEngine(fusionWindowMs = 100, debounceMs = 250, requireRecoil = true)

        // First shot at t=0 confirms.
        engine.onSoundSpike(sound(0))
        val first = engine.onRecoilSpike(recoil(0))
        assertTrue(first != null)
        assertEquals(1, engine.confirmedCount)

        // Second pair only 100ms later — within debounce, must be blocked.
        engine.onSoundSpike(sound(100))
        val blocked = engine.onRecoilSpike(recoil(100))
        assertNull(blocked)
        assertEquals(1, engine.confirmedCount)

        // Third pair at 300ms — past debounce, confirms again.
        engine.onSoundSpike(sound(300))
        val third = engine.onRecoilSpike(recoil(300))
        assertTrue(third != null)
        assertEquals(2, engine.confirmedCount)
        assertEquals(2, third!!.shotCount)
    }

    @Test
    fun requireRecoilFalse_confirmsOnSoundAlone() {
        val engine = FusionEngine(fusionWindowMs = 100, debounceMs = 250, requireRecoil = false)

        val shot = engine.onSoundSpike(sound(0, peak = 0.9f))
        assertTrue(shot != null)
        assertFalse("sound-only confirmation", shot!!.confirmedByRecoil)
        assertEquals(0f, shot.recoilG, 1e-6f)
        assertEquals(1, engine.confirmedCount)
    }

    // --- Extra coverage -----------------------------------------------------

    @Test
    fun requireRecoilFalse_recoilAloneNeverConfirms() {
        val engine = FusionEngine(requireRecoil = false)
        assertNull(engine.onRecoilSpike(recoil(0)))
        assertEquals(0, engine.confirmedCount)
    }

    @Test
    fun requireRecoilTrue_recoilThenSoundAlsoConfirms() {
        // Order independence: recoil first, then sound inside the window.
        val engine = FusionEngine(fusionWindowMs = 100, requireRecoil = true)
        assertNull(engine.onRecoilSpike(recoil(0)))
        val shot = engine.onSoundSpike(sound(30))
        assertTrue(shot != null)
        assertEquals(ms(30), shot!!.timestampNanos)
    }

    @Test
    fun spikesAreConsumedOnConfirm() {
        // A single recoil must not pair with two separate sounds.
        val engine = FusionEngine(fusionWindowMs = 100, debounceMs = 0, requireRecoil = true)
        engine.onSoundSpike(sound(0))
        assertTrue(engine.onRecoilSpike(recoil(0)) != null)
        // New sound near the already-consumed recoil should NOT confirm by itself.
        assertNull(engine.onSoundSpike(sound(20)))
        assertEquals(1, engine.confirmedCount)
    }

    @Test
    fun burstOfShotsIncrementsCount() {
        val engine = FusionEngine(fusionWindowMs = 100, debounceMs = 250, requireRecoil = true)
        var t = 0L
        repeat(3) {
            engine.onSoundSpike(sound(t))
            engine.onRecoilSpike(recoil(t))
            t += 300
        }
        assertEquals(3, engine.confirmedCount)
    }

    @Test
    fun resetClearsCounterAndState() {
        val engine = FusionEngine(requireRecoil = false)
        engine.onSoundSpike(sound(0))
        assertEquals(1, engine.confirmedCount)
        engine.reset()
        assertEquals(0, engine.confirmedCount)
        // After reset, debounce history is cleared so an immediate spike confirms.
        val shot = engine.onSoundSpike(sound(10))
        assertTrue(shot != null)
        assertEquals(1, shot!!.shotCount)
    }
}
