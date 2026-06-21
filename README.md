# ShotSense

A native Android **prototype** that detects a firearm discharge from a phone mounted
near the firearm and sends an alert to a security response endpoint.

Detection fuses **two sensors**: a near-instant loud sound impulse on the microphone
**and** a sharp recoil jolt on the accelerometer. A shot is **confirmed only when both
happen inside a short time window**, so ordinary loud noises (claps, door slams, music)
do not trigger false alarms.

> ⚠️ This is a field-testing prototype, **not** a Play Store release. It defaults to
> **Test mode**, so no real alert is sent during development.

---

## How it works

A gunshot produces two signals a phone can catch at once:

- **Sound branch** — a very loud, near-instant impulse on the mic (primary trigger).
- **Recoil branch** — a sharp acceleration spike on the accelerometer (confirmer).

The **FusionEngine** declares a *confirmed shot* only when a sound spike and a recoil
spike land within `fusionWindowMs` of each other (subject to debounce). On confirmation,
the app grabs a GPS fix and sends an alert with device ID, coordinates, shot count, and
confidence.

Phone accelerometers sample slowly (a few hundred Hz at best) relative to the ~1–3 ms of
a real recoil — which is exactly why recoil is the **confirmer**, not the primary trigger.

### Single monotonic clock
Both branches timestamp events with `SystemClock.elapsedRealtimeNanos()` (audio at buffer
read time, sensor at event receipt). The fusion window comparison is therefore valid
across branches.

---

## Architecture

| Component | Responsibility |
|---|---|
| `fusion/FusionEngine.kt` | **Pure Kotlin, no Android imports.** All window/debounce/`requireRecoil` timing rules. Fully unit-tested. |
| `fusion/Events.kt` | `SoundSpike`, `RecoilSpike`, `ConfirmedShot` |
| `detect/AudioDetector.kt` | Owns `AudioRecord`, reads PCM on a dedicated thread, computes peak/energy/ratio, tracks ambient floor, emits `SoundSpike` |
| `detect/RecoilDetector.kt` | `SensorManager` listener on a `HandlerThread` at fastest rate, magnitude in g, reports sample rate + sensor availability, emits `RecoilSpike` |
| `location/LocationProvider.kt` | Fresh fix at alert time (FusedLocation → LocationManager fallback), last-known fallback |
| `alert/Alerter.kt` | Builds the message; sends over SMS + HTTP; returns a per-channel result |
| `data/SettingsStore.kt` | DataStore (Preferences) for all tunables/config |
| `data/CalibrationLogger.kt` | Appends every spike + confirmed shot to a CSV for threshold tuning |
| `service/DetectionService.kt` | Foreground service that owns all of the above and exposes a single `StateFlow<DetectorState>`; persistent notification + partial wake lock |
| `model/DetectorState.kt` | Single immutable UI snapshot |
| `ui/` | Jetpack Compose dashboard + settings |

---

## Detection algorithm & defaults

**Sound branch**
- `AudioRecord`: 44100 Hz, mono, PCM 16-bit.
- Audio source preference: `UNPROCESSED` → `VOICE_RECOGNITION` → `MIC` (avoids AGC/noise
  suppression that would flatten the impulse).
- Per ~1024-frame buffer: `peak = max(|sample|)/32768`, `energy = mean(sample²)`.
- `background` = slow EMA of energy, **updated only when `peak < 0.30`** (loud events don't
  raise the floor), clamped to a small floor.
- `ratio = energy / background`.
- Emit `SoundSpike` when `peak ≥ saturationThreshold` **and** `ratio ≥ impulseRatioK`
  (subject to debounce).

**Recoil branch**
- Prefers `TYPE_LINEAR_ACCELERATION` (gravity already removed); falls back to
  `TYPE_ACCELEROMETER` minus a slow gravity baseline.
- Registered at `SENSOR_DELAY_FASTEST` on a `HandlerThread`.
- `magnitude = sqrt(x²+y²+z²)`, expressed in g (÷ 9.81).
- Emit `RecoilSpike` when `g ≥ recoilThresholdG` (subject to debounce).

**Fusion**
- Confirmed when both spikes exist and `|soundTime − recoilTime| ≤ fusionWindowMs`, and at
  least `debounceMs` since the last confirmation.
- `requireRecoil = false` lets a sound spike alone confirm (for hardware with no usable
  accelerometer).
- Counts consecutive confirmed shots as a shot count.

**Defaults**

| Tunable | Default |
|---|---|
| `saturationThreshold` | `0.88` |
| `impulseRatioK` | `12` |
| `recoilThresholdG` | `2.5` |
| `fusionWindowMs` | `100` |
| `debounceMs` | `250` |
| `requireRecoil` | `true` |

All are editable on the Settings screen and persisted via DataStore.

---

## Modes

- **Test (default):** detections are logged and shown on screen — **no SMS/HTTP sent**.
- **Armed:** confirmed shots send the alert over the enabled channels.

## Alert channels

**SMS** (`sendMultipartTextMessage`):
```
GUNSHOT ALERT
Device: <deviceId>
Shots: <n>
Confidence: <CONFIRMED sound+recoil | SOUND ONLY>
Time: <yyyy-MM-dd HH:mm:ss>
Loc: <lat>, <lng>
https://maps.google.com/?q=<lat>,<lng>
```

**HTTP POST** (JSON — primary scalable channel):
```json
{
  "deviceId": "string",
  "timestamp": "ISO-8601",
  "lat": 0.0,
  "lng": 0.0,
  "accuracyMeters": 0.0,
  "shots": 1,
  "confirmed": true,
  "soundPeak": 0.0,
  "recoilG": 0.0
}
```

> `SEND_SMS` works fine for a sideloaded prototype. For any Play Store path it is
> restricted, so HTTP is the primary scalable channel and SMS is the offline fallback.

---

## Calibration logging

Every sound spike, recoil spike, and confirmed shot is appended to
`<externalFilesDir>/shotsense_calibration.csv`:

```
timestamp,type,peak,ratio,gforce,confirmed,lat,lng
```

Use **EXPORT CSV** on the dashboard to share it. Tune thresholds from real range data.

---

## Build & run

Requires **Android Studio** (Ladybug or newer) with an Android SDK; `compileSdk 35`,
`minSdk 26`. The Gradle wrapper (8.9) is included.

```bash
# from the project root
./gradlew :app:assembleDebug      # build the APK
./gradlew :app:testDebugUnitTest  # run FusionEngine unit tests
```

Or open the folder in Android Studio and Run.

> Note: this repository was scaffolded in an environment without the Android SDK, so the
> first compile should be done in Android Studio (which will also create `local.properties`
> with your `sdk.dir`). The `FusionEngine` and its tests are plain JVM Kotlin.

### Permissions
On first launch a permission gate explains each permission and requests them. **Microphone
and Location are required** to run; **SMS is optional** (only for the SMS channel).
`POST_NOTIFICATIONS` is requested on Android 13+.

---

## Tests

`app/src/test/java/com/shotsense/app/fusion/FusionEngineTest.kt` covers:
- both spikes inside the window confirm;
- spikes outside the window do **not** confirm (plus inclusive boundary);
- debounce blocks rapid repeats;
- `requireRecoil = false` confirms on sound alone (and recoil alone never confirms);
- contributing spikes are consumed on confirmation;
- burst counting and `reset()`.

---

## Safety / design constraints honored

- The two-sensor requirement is **on by default** (`requireRecoil = true`) — false alarms
  are the main risk.
- **Test mode is the default** so no real alert is sent during development.
- Uses the **UNPROCESSED / VOICE_RECOGNITION** audio source, never the default processed
  mic path.
- All timing is on a **single monotonic clock**.
