package com.shotsense.app.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.SensorManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.shotsense.app.MainActivity
import com.shotsense.app.R
import com.shotsense.app.alert.AlertConfig
import com.shotsense.app.alert.AlertPayload
import com.shotsense.app.alert.Alerter
import com.shotsense.app.data.ArmingMode
import com.shotsense.app.data.CalibrationLogger
import com.shotsense.app.data.Settings
import com.shotsense.app.data.SettingsStore
import com.shotsense.app.detect.AudioDetector
import com.shotsense.app.detect.RecoilDetector
import com.shotsense.app.fusion.ConfirmedShot
import com.shotsense.app.fusion.FusionEngine
import com.shotsense.app.fusion.RecoilSpike
import com.shotsense.app.fusion.SoundSpike
import com.shotsense.app.location.LocationProvider
import com.shotsense.app.model.DetectorState
import com.shotsense.app.model.FusionStatus
import com.shotsense.app.model.RunState
import com.shotsense.app.model.ShotRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

/**
 * Started foreground service that owns the detectors, fusion engine, location,
 * and alerting, and publishes a single [DetectorState] for the UI to observe.
 *
 * A persistent notification ("Gunshot detection active") plus a partial wake lock
 * keep audio processing alive with the screen off.
 */
class DetectionService : LifecycleService() {

    private lateinit var settingsStore: SettingsStore
    private lateinit var calibrationLogger: CalibrationLogger
    private lateinit var locationProvider: LocationProvider
    private lateinit var alerter: Alerter

    private val engine = FusionEngine()
    private val engineLock = Any()

    private lateinit var audioDetector: AudioDetector
    private lateinit var recoilDetector: RecoilDetector

    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile private var running = false
    @Volatile private var currentSettings = Settings()

    // Live values updated by detector callbacks (read by the snapshot ticker).
    @Volatile private var soundPeakCur = 0f
    @Volatile private var soundRatioCur = 0f
    @Volatile private var recoilGCur = 0f
    private var soundPeakHold = 0f
    private var recoilHold = 0f

    @Volatile private var recoilStatus = RecoilDetector.Status(false, "—", 0f, false)

    @Volatile private var lastSoundNanos = 0L
    @Volatile private var lastRecoilNanos = 0L
    @Volatile private var lastConfirmedNanos = 0L

    private val shots = ArrayDeque<ShotRecord>()

    override fun onCreate() {
        super.onCreate()
        settingsStore = SettingsStore(applicationContext)
        calibrationLogger = CalibrationLogger(applicationContext)
        locationProvider = LocationProvider(applicationContext)
        alerter = Alerter(applicationContext)

        val sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        audioDetector = AudioDetector(
            onSpike = ::onSoundSpike,
            onLevels = { peak, ratio -> soundPeakCur = peak; soundRatioCur = ratio },
        )
        recoilDetector = RecoilDetector(
            sensorManager = sensorManager,
            onSpike = ::onRecoilSpike,
            onLevels = { g -> recoilGCur = g },
            onStatus = { recoilStatus = it },
        )

        // Live-apply settings changes to the engine and detectors.
        lifecycleScope.launch {
            settingsStore.settings.collect { s ->
                currentSettings = s
                synchronized(engineLock) {
                    engine.fusionWindowMs = s.fusionWindowMs
                    engine.debounceMs = s.debounceMs
                    engine.requireRecoil = s.requireRecoil
                }
                audioDetector.saturationThreshold = s.saturationThreshold
                audioDetector.impulseRatioK = s.impulseRatioK
                audioDetector.debounceMs = s.debounceMs
                recoilDetector.recoilThresholdG = s.recoilThresholdG
                recoilDetector.debounceMs = s.debounceMs
                publish()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> startDetection()
            ACTION_STOP -> { stopDetection(); stopSelf() }
            ACTION_SIMULATE_SHOT -> simulateShot()
            ACTION_SIMULATE_RECOIL -> simulateRecoil()
            ACTION_TEST_ALERT -> sendTestAlert()
        }
        return START_STICKY
    }

    // --- Lifecycle ---------------------------------------------------------

    @android.annotation.SuppressLint("MissingPermission")
    private fun startDetection() {
        if (running) return
        startForegroundNotification()
        acquireWakeLock()

        synchronized(engineLock) { engine.reset() }
        shots.clear()

        val micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (micGranted) {
            audioDetector.start()
        } else {
            Log.w(TAG, "RECORD_AUDIO not granted; audio branch disabled")
        }
        recoilDetector.start()

        running = true
        startSnapshotLoop()
        publish()
    }

    private fun stopDetection() {
        if (!running) return
        running = false
        audioDetector.stop()
        recoilDetector.stop()
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        publish()
    }

    override fun onDestroy() {
        stopDetection()
        super.onDestroy()
    }

    // --- Event handling ----------------------------------------------------

    private fun onSoundSpike(spike: SoundSpike) {
        lastSoundNanos = SystemClock.elapsedRealtimeNanos()
        calibrationLogger.logSound(spike)
        val confirmed = synchronized(engineLock) { engine.onSoundSpike(spike) }
        confirmed?.let(::onConfirmed)
    }

    private fun onRecoilSpike(spike: RecoilSpike) {
        lastRecoilNanos = SystemClock.elapsedRealtimeNanos()
        calibrationLogger.logRecoil(spike)
        val confirmed = synchronized(engineLock) { engine.onRecoilSpike(spike) }
        confirmed?.let(::onConfirmed)
    }

    private fun onConfirmed(shot: ConfirmedShot) {
        lastConfirmedNanos = SystemClock.elapsedRealtimeNanos()
        val settings = currentSettings
        val armed = settings.armingMode == ArmingMode.ARMED
        lifecycleScope.launch {
            val fix = runCatching {
                if (hasLocationPermission()) locationProvider.getFix() else null
            }.getOrNull()
            calibrationLogger.logConfirmed(shot, fix?.lat, fix?.lng)

            val status: String = if (!armed) {
                "TEST (not sent)"
            } else {
                val payload = AlertPayload(
                    deviceId = settings.deviceId,
                    epochMillis = System.currentTimeMillis(),
                    lat = fix?.lat,
                    lng = fix?.lng,
                    accuracyMeters = fix?.accuracyMeters,
                    shots = shot.shotCount,
                    confirmed = shot.confirmedByRecoil,
                    soundPeak = shot.soundPeak,
                    recoilG = shot.recoilG,
                    approximateLocation = fix?.approximate ?: false,
                    operatorName = settings.operatorName,
                    operatorPhone = settings.operatorPhone,
                    firearmType = settings.firearmType,
                    photoUrl = settings.photoUrl,
                )
                val results = alerter.send(payload, settings.toAlertConfig())
                results.joinToString("; ") { "${it.channel} ${if (it.success) "ok" else "FAIL(${it.detail})"}" }
            }

            synchronized(shots) {
                shots.addFirst(
                    ShotRecord(
                        epochMillis = System.currentTimeMillis(),
                        soundPeak = shot.soundPeak,
                        recoilG = shot.recoilG,
                        confirmedByRecoil = shot.confirmedByRecoil,
                        lat = fix?.lat,
                        lng = fix?.lng,
                        approximateLocation = fix?.approximate ?: false,
                        alertStatus = status,
                    ),
                )
                while (shots.size > MAX_LOG) shots.removeLast()
            }
            publish()
        }
    }

    // --- Simulation / test -------------------------------------------------

    private fun simulateShot() {
        val now = SystemClock.elapsedRealtimeNanos()
        // Inject a sound spike and a recoil spike together to exercise fusion.
        onSoundSpike(SoundSpike(timestampNanos = now, peak = 0.99f, ratio = 50f))
        onRecoilSpike(RecoilSpike(timestampNanos = now, gForce = 4.0f))
    }

    private fun simulateRecoil() {
        val now = SystemClock.elapsedRealtimeNanos()
        onRecoilSpike(RecoilSpike(timestampNanos = now, gForce = 4.0f))
    }

    private fun sendTestAlert() {
        val settings = currentSettings
        lifecycleScope.launch {
            val fix = runCatching {
                if (hasLocationPermission()) locationProvider.getFix() else null
            }.getOrNull()
            val payload = AlertPayload(
                deviceId = settings.deviceId,
                epochMillis = System.currentTimeMillis(),
                lat = fix?.lat,
                lng = fix?.lng,
                accuracyMeters = fix?.accuracyMeters,
                shots = 1,
                confirmed = true,
                soundPeak = 0.94f,
                recoilG = 3.1f,
                approximateLocation = fix?.approximate ?: false,
                isTest = true,
                operatorName = settings.operatorName,
                operatorPhone = settings.operatorPhone,
                firearmType = settings.firearmType,
                photoUrl = settings.photoUrl,
            )
            val results = alerter.send(payload, settings.toAlertConfig())
            val status = "TEST ALERT → " + results.joinToString("; ") {
                "${it.channel} ${if (it.success) "ok" else "FAIL(${it.detail})"}"
            }
            synchronized(shots) {
                shots.addFirst(
                    ShotRecord(
                        epochMillis = System.currentTimeMillis(),
                        soundPeak = 0f,
                        recoilG = 0f,
                        confirmedByRecoil = false,
                        lat = fix?.lat,
                        lng = fix?.lng,
                        approximateLocation = fix?.approximate ?: false,
                        alertStatus = status,
                    ),
                )
                while (shots.size > MAX_LOG) shots.removeLast()
            }
            publish()
        }
    }

    // --- State publishing --------------------------------------------------

    private fun startSnapshotLoop() {
        lifecycleScope.launch {
            while (isActive && running) {
                // Peak-hold meters: jump up to the current value, then decay slowly.
                soundPeakHold = maxOf(soundPeakCur, soundPeakHold * HOLD_DECAY)
                recoilHold = maxOf(recoilGCur, recoilHold * HOLD_DECAY)
                publish()
                delay(SNAPSHOT_INTERVAL_MS)
            }
        }
    }

    private fun publish() {
        val s = currentSettings
        val runState = when {
            !running -> RunState.STOPPED
            s.armingMode == ArmingMode.ARMED -> RunState.ARMED
            else -> RunState.TEST
        }
        val snapshot = DetectorState(
            runState = runState,
            soundPeak = soundPeakCur,
            soundPeakHold = soundPeakHold,
            soundRatio = soundRatioCur,
            audioSource = audioDetector.sourceLabel,
            recoilG = recoilGCur,
            recoilHold = recoilHold,
            hasRecoilSensor = recoilStatus.hasSensor,
            recoilSensorName = recoilStatus.sensorName,
            recoilSampleRateHz = recoilStatus.sampleRateHz,
            fusionStatus = computeFusionStatus(),
            confirmedCount = synchronized(engineLock) { engine.confirmedCount },
            shots = synchronized(shots) { shots.toList() },
            saturationThreshold = s.saturationThreshold,
            recoilThresholdG = s.recoilThresholdG,
        )
        _state.value = snapshot
    }

    private fun computeFusionStatus(): FusionStatus {
        if (!running) return FusionStatus.WAITING
        val now = SystemClock.elapsedRealtimeNanos()
        fun ageMs(t: Long) = if (t == 0L) Long.MAX_VALUE else (now - t) / 1_000_000
        if (ageMs(lastConfirmedNanos) < CONFIRMED_FLASH_MS) return FusionStatus.CONFIRMED
        val soundRecent = ageMs(lastSoundNanos) < BRANCH_RECENT_MS
        val recoilRecent = ageMs(lastRecoilNanos) < BRANCH_RECENT_MS
        return when {
            soundRecent && !recoilRecent -> FusionStatus.SOUND_ONLY
            recoilRecent && !soundRecent -> FusionStatus.RECOIL_ONLY
            soundRecent && recoilRecent -> FusionStatus.SOUND_ONLY
            else -> FusionStatus.WAITING
        }
    }

    // --- Notification / wake lock -----------------------------------------

    private fun startForegroundNotification() {
        val mgr = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Detection",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Active gunshot detection" }
            mgr.createNotificationChannel(channel)
        }

        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, DetectionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Gunshot detection active")
            .setContentText("ShotSense is listening")
            .setSmallIcon(R.drawable.ic_stat_detect)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(
                Notification.Action.Builder(
                    null as android.graphics.drawable.Icon?,
                    "Stop",
                    stopIntent,
                ).build(),
            )
            .build()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    @Suppress("WakelockTimeout")
    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ShotSense::Detection").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun Settings.toAlertConfig() = AlertConfig(
        smsEnabled = smsEnabled,
        smsNumber = smsNumber,
        httpEnabled = httpEnabled,
        httpUrl = httpUrl,
    )

    companion object {
        private const val TAG = "DetectionService"
        private const val CHANNEL_ID = "shotsense_detection"
        private const val NOTIFICATION_ID = 1001
        private const val MAX_LOG = 100

        private const val SNAPSHOT_INTERVAL_MS = 50L
        private const val HOLD_DECAY = 0.90f
        private const val CONFIRMED_FLASH_MS = 1500L
        private const val BRANCH_RECENT_MS = 700L

        const val ACTION_START = "com.shotsense.app.action.START"
        const val ACTION_STOP = "com.shotsense.app.action.STOP"
        const val ACTION_SIMULATE_SHOT = "com.shotsense.app.action.SIMULATE_SHOT"
        const val ACTION_SIMULATE_RECOIL = "com.shotsense.app.action.SIMULATE_RECOIL"
        const val ACTION_TEST_ALERT = "com.shotsense.app.action.TEST_ALERT"

        private val _state = MutableStateFlow(DetectorState())
        val state: StateFlow<DetectorState> = _state.asStateFlow()

        private fun send(context: Context, action: String) {
            val intent = Intent(context, DetectionService::class.java).setAction(action)
            if (action == ACTION_START) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
        }

        fun start(context: Context) = send(context, ACTION_START)
        fun stop(context: Context) = send(context, ACTION_STOP)
        fun simulateShot(context: Context) = send(context, ACTION_SIMULATE_SHOT)
        fun simulateRecoil(context: Context) = send(context, ACTION_SIMULATE_RECOIL)
        fun sendTestAlert(context: Context) = send(context, ACTION_TEST_ALERT)
    }
}
