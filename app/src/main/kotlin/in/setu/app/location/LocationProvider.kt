package `in`.setu.app.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** A fix good enough to put in a packet. */
data class Fix(val lat: Double, val lon: Double, val accuracyM: Int, val ageSeconds: Long)

/**
 * Uses the platform LocationManager, not Play Services, so it works on phones
 * without Google location and inside buildings falls back to the network fix.
 */
class LocationProvider(private val context: Context) {
    private val lm get() = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Best cached fix from any provider, newest first. */
    @SuppressLint("MissingPermission")
    fun lastKnown(): Fix? {
        if (!hasPermission()) return null
        return lm.allProviders.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }?.toFix()
    }

    /**
     * Waits up to [timeoutMs] for a fresh fix, returning the best cached one if
     * nothing arrives. Never throws: an SOS must go out even without a fix.
     */
    @SuppressLint("MissingPermission")
    suspend fun current(timeoutMs: Long = 8_000): Fix? {
        if (!hasPermission()) return null
        val fresh = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        lm.removeUpdates(this)
                        if (cont.isActive) cont.resume(location.toFix())
                    }
                    @Deprecated("Deprecated in Java") override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) {}
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                }
                val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                    .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
                if (providers.isEmpty()) {
                    cont.resume(null)
                    return@suspendCancellableCoroutine
                }
                providers.forEach { lm.requestLocationUpdates(it, 0L, 0f, listener, Looper.getMainLooper()) }
                cont.invokeOnCancellation { lm.removeUpdates(listener) }
            }
        }
        return fresh ?: lastKnown()
    }

    private fun Location.toFix() = Fix(
        lat = latitude,
        lon = longitude,
        accuracyM = if (hasAccuracy()) accuracy.toInt() else 0,
        ageSeconds = ((System.currentTimeMillis() - time) / 1000).coerceAtLeast(0),
    )
}
