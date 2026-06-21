package com.shotsense.app.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresPermission
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** A resolved location fix attached to an alert. */
data class LocationFix(
    val lat: Double,
    val lng: Double,
    val accuracyMeters: Float,
    /** true when we fell back to a last-known fix instead of a fresh one. */
    val approximate: Boolean,
)

/**
 * Returns a fresh location fix at alert time, with last-known location as an
 * immediate fallback. Uses [com.google.android.gms.location.FusedLocationProviderClient]
 * when Google Play Services is present, otherwise falls back to [LocationManager] GPS.
 */
class LocationProvider(private val context: Context) {

    private val hasPlayServices: Boolean by lazy {
        GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    }

    /**
     * Request a current fix, falling back to last-known (marked approximate) if a
     * fresh fix does not arrive within [timeoutMs].
     */
    @RequiresPermission(anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    suspend fun getFix(timeoutMs: Long = 2_000L): LocationFix? {
        val fresh = withTimeoutOrNull(timeoutMs) {
            if (hasPlayServices) fusedCurrentLocation() else managerCurrentLocation(timeoutMs)
        }
        if (fresh != null) return fresh.toFix(approximate = false)

        val last = lastKnown()
        return last?.toFix(approximate = true)
    }

    @SuppressLint("MissingPermission")
    private suspend fun fusedCurrentLocation(): Location? = suspendCancellableCoroutine { cont ->
        val client = LocationServices.getFusedLocationProviderClient(context)
        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .build()
        val cts = CancellationTokenSource()
        client.getCurrentLocation(request, cts.token)
            .addOnSuccessListener { loc -> if (cont.isActive) cont.resume(loc) }
            .addOnFailureListener { e ->
                Log.w(TAG, "fused getCurrentLocation failed", e)
                if (cont.isActive) cont.resume(null)
            }
        cont.invokeOnCancellation { cts.cancel() }
    }

    @SuppressLint("MissingPermission")
    private suspend fun managerCurrentLocation(timeoutMs: Long): Location? =
        suspendCancellableCoroutine { cont ->
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val provider = when {
                lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                else -> null
            }
            if (provider == null) {
                cont.resume(null)
                return@suspendCancellableCoroutine
            }
            val listener = object : android.location.LocationListener {
                override fun onLocationChanged(location: Location) {
                    runCatching { lm.removeUpdates(this) }
                    if (cont.isActive) cont.resume(location)
                }

                @Deprecated("Required by interface on older APIs")
                override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) {}
                override fun onProviderEnabled(p: String) {}
                override fun onProviderDisabled(p: String) {}
            }
            runCatching {
                lm.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
            }.onFailure {
                Log.w(TAG, "requestLocationUpdates failed", it)
                if (cont.isActive) cont.resume(null)
            }
            cont.invokeOnCancellation { runCatching { lm.removeUpdates(listener) } }
        }

    @SuppressLint("MissingPermission")
    private suspend fun lastKnown(): Location? {
        if (hasPlayServices) {
            val viaFused = suspendCancellableCoroutine<Location?> { cont ->
                LocationServices.getFusedLocationProviderClient(context).lastLocation
                    .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                    .addOnFailureListener { if (cont.isActive) cont.resume(null) }
            }
            if (viaFused != null) return viaFused
        }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return runCatching {
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        }.getOrNull()
    }

    private fun Location.toFix(approximate: Boolean) = LocationFix(
        lat = latitude,
        lng = longitude,
        accuracyMeters = if (hasAccuracy()) accuracy else -1f,
        approximate = approximate,
    )

    private companion object {
        const val TAG = "LocationProvider"
    }
}
