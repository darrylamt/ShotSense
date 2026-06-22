package com.shotsense.app.alert

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.telephony.SmsManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/** Everything needed to render and send an alert for one confirmed shot (or a test). */
data class AlertPayload(
    val deviceId: String,
    val epochMillis: Long,
    val lat: Double?,
    val lng: Double?,
    val accuracyMeters: Float?,
    val shots: Int,
    /** true = confirmed by sound + recoil; false = sound only. */
    val confirmed: Boolean,
    val soundPeak: Float,
    val recoilG: Float,
    val approximateLocation: Boolean = false,
    /** true for the "Send test alert" button so payloads are clearly labelled. */
    val isTest: Boolean = false,
    // Operator profile
    val operatorName: String = "",
    val operatorPhone: String = "",
    val firearmType: String = "",
    val photoUrl: String = "",
)

/** Per-channel send configuration. */
data class AlertConfig(
    val smsEnabled: Boolean,
    val smsNumber: String,
    val httpEnabled: Boolean,
    val httpUrl: String,
)

/** Result of attempting one channel. */
data class ChannelResult(
    val channel: String,
    val success: Boolean,
    val detail: String,
)

/**
 * Builds the alert message and sends it over the enabled channels (SMS, HTTP).
 * HTTP is the primary scalable channel (feeds the response console); SMS is the
 * offline fallback.
 */
class Alerter(private val context: Context) {

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    suspend fun send(payload: AlertPayload, config: AlertConfig): List<ChannelResult> {
        val results = mutableListOf<ChannelResult>()
        if (config.smsEnabled) results += sendSms(payload, config.smsNumber)
        if (config.httpEnabled) results += sendHttp(payload, config.httpUrl)
        if (results.isEmpty()) {
            results += ChannelResult("none", false, "No channels enabled")
        }
        return results
    }

    // --- SMS ---------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun sendSms(payload: AlertPayload, number: String): ChannelResult {
        if (number.isBlank()) return ChannelResult("SMS", false, "No number configured")
        return try {
            val manager = smsManager()
            val text = buildSmsText(payload)
            val parts = manager.divideMessage(text)
            manager.sendMultipartTextMessage(number, null, parts, null, null)
            ChannelResult("SMS", true, "Sent to $number (${parts.size} part(s))")
        } catch (e: Exception) {
            Log.e(TAG, "SMS send failed", e)
            ChannelResult("SMS", false, e.message ?: "send failed")
        }
    }

    @Suppress("DEPRECATION")
    private fun smsManager(): SmsManager =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            SmsManager.getDefault()
        }

    fun buildSmsText(p: AlertPayload): String {
        val confidence = when {
            p.confirmed -> "CONFIRMED sound+recoil"
            else -> "SOUND ONLY"
        }
        val time = LOCAL_TIME.format(Instant.ofEpochMilli(p.epochMillis))
        val loc = if (p.lat != null && p.lng != null) {
            "${fmt(p.lat)}, ${fmt(p.lng)}" + if (p.approximateLocation) " (approx)" else ""
        } else {
            "unavailable"
        }
        val maps = if (p.lat != null && p.lng != null) {
            "https://maps.google.com/?q=${p.lat},${p.lng}"
        } else {
            "—"
        }
        val header = if (p.isTest) "TEST ALERT (no real shot)" else "GUNSHOT ALERT"
        return buildString {
            appendLine(header)
            appendLine("Device: ${p.deviceId}")
            appendLine("Shots: ${p.shots}")
            appendLine("Confidence: $confidence")
            appendLine("Time: $time")
            appendLine("Loc: $loc")
            append(maps)
        }
    }

    // --- HTTP --------------------------------------------------------------

    private suspend fun sendHttp(payload: AlertPayload, url: String): ChannelResult {
        if (url.isBlank()) return ChannelResult("HTTP", false, "No endpoint configured")
        return withContext(Dispatchers.IO) {
            try {
                val body = buildJson(payload).toRequestBody(JSON)
                val request = Request.Builder().url(url).post(body).build()
                http.newCall(request).execute().use { resp ->
                    if (resp.isSuccessful) {
                        ChannelResult("HTTP", true, "HTTP ${resp.code}")
                    } else {
                        ChannelResult("HTTP", false, "HTTP ${resp.code}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "HTTP send failed", e)
                ChannelResult("HTTP", false, e.message ?: "request failed")
            }
        }
    }

    fun buildJson(p: AlertPayload): String = JSONObject().apply {
        put("deviceId", p.deviceId)
        put("timestamp", ISO.format(Instant.ofEpochMilli(p.epochMillis)))
        put("lat", p.lat ?: JSONObject.NULL)
        put("lng", p.lng ?: JSONObject.NULL)
        put("accuracyMeters", p.accuracyMeters?.toDouble() ?: JSONObject.NULL)
        put("shots", p.shots)
        put("confirmed", p.confirmed)
        put("soundPeak", p.soundPeak.toDouble())
        put("recoilG", p.recoilG.toDouble())
        put("test", p.isTest)
        put("approximateLocation", p.approximateLocation)
        put("operatorName", p.operatorName)
        put("operatorPhone", p.operatorPhone)
        put("firearmType", p.firearmType)
        put("photoUrl", p.photoUrl)
    }.toString()

    private fun fmt(v: Double): String = String.format("%.6f", v)

    private companion object {
        const val TAG = "Alerter"
        val JSON = "application/json; charset=utf-8".toMediaType()
        val ISO: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT
        val LOCAL_TIME: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())
    }
}
