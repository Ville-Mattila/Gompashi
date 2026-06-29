package fi.gompashi.app

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate

/**
 * Emits the device location using the AOSP [LocationManager] only (no Google Play Services),
 * so the app works on de-Googled devices too. Caller MUST hold location permission before
 * collecting.
 *
 * On Android 12+ it uses the platform fused provider; older versions use GPS + network. The
 * latest known fix is sent immediately so the UI isn't stuck waiting for the first update.
 */
class LocationProvider(context: Context) {
    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    @SuppressLint("MissingPermission")
    fun locationFlow(): Flow<Location> = callbackFlow {
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) { trySend(location) }
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
            @Deprecated("Required on API < 30")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }

        // Seed with the best last-known fix so the compass shows something right away.
        bestLastKnown()?.let { trySend(it) }

        providers().forEach { p ->
            try {
                lm.requestLocationUpdates(p, 2_000L, 0f, listener, Looper.getMainLooper())
            } catch (_: Exception) {
                // provider may be unavailable on this device; ignore and rely on the others
            }
        }

        awaitClose { lm.removeUpdates(listener) }
    }.conflate()

    /**
     * Every available provider: the platform fused one (API 31+, best on real devices) plus
     * GPS and network. Subscribing to all keeps it working everywhere — emulators and some
     * devices feed only GPS, de-Googled devices have no fused provider, etc. [conflate] keeps
     * the latest fix from whichever delivers.
     */
    private fun providers(): List<String> {
        val all = lm.allProviders
        return buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && all.contains(LocationManager.FUSED_PROVIDER)) {
                add(LocationManager.FUSED_PROVIDER)
            }
            if (all.contains(LocationManager.GPS_PROVIDER)) add(LocationManager.GPS_PROVIDER)
            if (all.contains(LocationManager.NETWORK_PROVIDER)) add(LocationManager.NETWORK_PROVIDER)
        }
    }

    @SuppressLint("MissingPermission")
    private fun bestLastKnown(): Location? {
        var best: Location? = null
        for (p in lm.allProviders) {
            val loc = try { lm.getLastKnownLocation(p) } catch (_: Exception) { null } ?: continue
            if (best == null || loc.time > best!!.time) best = loc
        }
        return best
    }
}
