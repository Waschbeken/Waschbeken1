package de.waschbeken.heliolink.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import de.waschbeken.heliolink.core.TrackPoint

/**
 * The route of a workout from the phone's GPS (the strap has none). It only listens while a
 * workout with "Strecke aufzeichnen" runs and reads the GPS chip directly through Android's own
 * location service – no Google Play services, nothing leaves the phone.
 */
class RouteTracker(private val context: Context, private val onFix: (TrackPoint) -> Unit) {
    private var listening = false

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            // The phone's clock, like the heart rate values, so both fit together.
            onFix(
                TrackPoint(
                    System.currentTimeMillis() / 1000,
                    location.latitude,
                    location.longitude,
                    if (location.hasAltitude()) location.altitude else null,
                    if (location.hasAccuracy()) location.accuracy else null,
                ),
            )
        }

        // Android before 11 calls these without a default implementation.
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }

    /** Starts listening on [looper]; false when the permission is missing or the phone has no GPS. */
    @SuppressLint("MissingPermission")
    fun start(looper: Looper): Boolean {
        if (listening) return true
        if (!permitted(context)) return false
        val manager = manager(context) ?: return false
        return try {
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, looper)
            listening = true
            true
        } catch (e: Exception) {
            // SecurityException (permission taken away) or IllegalArgumentException (no GPS in this phone)
            false
        }
    }

    fun stop() {
        if (!listening) return
        listening = false
        try {
            manager(context)?.removeUpdates(listener)
        } catch (_: Exception) {
        }
    }

    companion object {
        private fun manager(context: Context) = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

        /** Whether the user allowed the precise location. */
        fun permitted(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

        /** Whether the phone's GPS is switched on. */
        fun gpsOn(context: Context): Boolean = try {
            manager(context)?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true
        } catch (e: Exception) {
            false
        }
    }
}
