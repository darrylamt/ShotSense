package com.shotsense.app.model

/** Top-level run state shown in the UI. */
enum class RunState { STOPPED, ARMED, TEST }

/** Fusion panel indicator. */
enum class FusionStatus { WAITING, SOUND_ONLY, RECOIL_ONLY, CONFIRMED }

/** One row in the confirmed-shots log. */
data class ShotRecord(
    val epochMillis: Long,
    val soundPeak: Float,
    val recoilG: Float,
    val confirmedByRecoil: Boolean,
    val lat: Double?,
    val lng: Double?,
    val approximateLocation: Boolean,
    /** Human-readable alert outcome, e.g. "TEST (not sent)", "HTTP 200; SMS ok", "FAILED". */
    val alertStatus: String,
)

/** Single immutable snapshot of everything the UI renders. */
data class DetectorState(
    val runState: RunState = RunState.STOPPED,

    // Sound branch
    val soundPeak: Float = 0f,
    val soundPeakHold: Float = 0f,
    val soundRatio: Float = 0f,
    val audioSource: String = "—",

    // Recoil branch
    val recoilG: Float = 0f,
    val recoilHold: Float = 0f,
    val hasRecoilSensor: Boolean = false,
    val recoilSensorName: String = "—",
    val recoilSampleRateHz: Float = 0f,

    // Fusion
    val fusionStatus: FusionStatus = FusionStatus.WAITING,
    val confirmedCount: Int = 0,

    // Confirmed shots log (most recent first)
    val shots: List<ShotRecord> = emptyList(),

    // Current thresholds, so meters can draw the trigger lines without a second source.
    val saturationThreshold: Float = 0.88f,
    val recoilThresholdG: Float = 2.5f,
)
