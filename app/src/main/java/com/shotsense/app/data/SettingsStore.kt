package com.shotsense.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Arming mode shown clearly in the UI. */
enum class ArmingMode { TEST, ARMED }

/** All persisted tunables and config. */
data class Settings(
    val saturationThreshold: Float = 0.88f,
    val impulseRatioK: Float = 12f,
    val recoilThresholdG: Float = 2.5f,
    val fusionWindowMs: Long = 100,
    val debounceMs: Long = 250,
    val requireRecoil: Boolean = true,
    val deviceId: String = "shotsense-01",
    val smsEnabled: Boolean = false,
    val smsNumber: String = "",
    val httpEnabled: Boolean = false,
    val httpUrl: String = "",
    val armingMode: ArmingMode = ArmingMode.TEST,
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "shotsense_settings")

/** DataStore (Preferences) wrapper for [Settings]. */
class SettingsStore(private val context: Context) {

    val settings: Flow<Settings> = context.dataStore.data.map { it.toSettings() }

    suspend fun setSaturationThreshold(v: Float) = put { it[K.saturation] = v }
    suspend fun setImpulseRatioK(v: Float) = put { it[K.impulseRatio] = v }
    suspend fun setRecoilThresholdG(v: Float) = put { it[K.recoilG] = v }
    suspend fun setFusionWindowMs(v: Long) = put { it[K.fusionWindow] = v }
    suspend fun setDebounceMs(v: Long) = put { it[K.debounce] = v }
    suspend fun setRequireRecoil(v: Boolean) = put { it[K.requireRecoil] = v }
    suspend fun setDeviceId(v: String) = put { it[K.deviceId] = v }
    suspend fun setSmsEnabled(v: Boolean) = put { it[K.smsEnabled] = v }
    suspend fun setSmsNumber(v: String) = put { it[K.smsNumber] = v }
    suspend fun setHttpEnabled(v: Boolean) = put { it[K.httpEnabled] = v }
    suspend fun setHttpUrl(v: String) = put { it[K.httpUrl] = v }
    suspend fun setArmingMode(v: ArmingMode) = put { it[K.armingMode] = v.name }

    /** Persist a whole snapshot in one edit (used by the settings screen). */
    suspend fun saveAll(s: Settings) = put { p ->
        p[K.saturation] = s.saturationThreshold
        p[K.impulseRatio] = s.impulseRatioK
        p[K.recoilG] = s.recoilThresholdG
        p[K.fusionWindow] = s.fusionWindowMs
        p[K.debounce] = s.debounceMs
        p[K.requireRecoil] = s.requireRecoil
        p[K.deviceId] = s.deviceId
        p[K.smsEnabled] = s.smsEnabled
        p[K.smsNumber] = s.smsNumber
        p[K.httpEnabled] = s.httpEnabled
        p[K.httpUrl] = s.httpUrl
        p[K.armingMode] = s.armingMode.name
    }

    private suspend fun put(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }

    private fun Preferences.toSettings(): Settings {
        val defaults = Settings()
        return Settings(
            saturationThreshold = this[K.saturation] ?: defaults.saturationThreshold,
            impulseRatioK = this[K.impulseRatio] ?: defaults.impulseRatioK,
            recoilThresholdG = this[K.recoilG] ?: defaults.recoilThresholdG,
            fusionWindowMs = this[K.fusionWindow] ?: defaults.fusionWindowMs,
            debounceMs = this[K.debounce] ?: defaults.debounceMs,
            requireRecoil = this[K.requireRecoil] ?: defaults.requireRecoil,
            deviceId = this[K.deviceId] ?: defaults.deviceId,
            smsEnabled = this[K.smsEnabled] ?: defaults.smsEnabled,
            smsNumber = this[K.smsNumber] ?: defaults.smsNumber,
            httpEnabled = this[K.httpEnabled] ?: defaults.httpEnabled,
            httpUrl = this[K.httpUrl] ?: defaults.httpUrl,
            armingMode = runCatching { ArmingMode.valueOf(this[K.armingMode] ?: defaults.armingMode.name) }
                .getOrDefault(defaults.armingMode),
        )
    }

    private object K {
        val saturation = floatPreferencesKey("saturation_threshold")
        val impulseRatio = floatPreferencesKey("impulse_ratio_k")
        val recoilG = floatPreferencesKey("recoil_threshold_g")
        val fusionWindow = longPreferencesKey("fusion_window_ms")
        val debounce = longPreferencesKey("debounce_ms")
        val requireRecoil = booleanPreferencesKey("require_recoil")
        val deviceId = stringPreferencesKey("device_id")
        val smsEnabled = booleanPreferencesKey("sms_enabled")
        val smsNumber = stringPreferencesKey("sms_number")
        val httpEnabled = booleanPreferencesKey("http_enabled")
        val httpUrl = stringPreferencesKey("http_url")
        val armingMode = stringPreferencesKey("arming_mode")
    }
}
