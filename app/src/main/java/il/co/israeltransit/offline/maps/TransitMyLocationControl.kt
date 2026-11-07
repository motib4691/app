package il.co.israeltransit.offline.maps

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class TransitMyLocationControl(context: Context) {

    private val appContext = context.applicationContext

    private val locationManager =
        appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val coarse = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        return fine || coarse
    }

    suspend fun moveToCurrentLocation(
        map: MapLibreMap
    ): Result<Location> {
        return try {
            if (!hasLocationPermission()) {
                throw SecurityException("אין הרשאת מיקום")
            }

            val location = withTimeout(20_000L) {
                requestLocation()
            }

            require(
                location.latitude in -90.0..90.0 &&
                    location.longitude in -180.0..180.0
            ) {
                "התקבל מיקום לא תקין"
            }

            map.animateCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder()
                        .target(
                            LatLng(
                                location.latitude,
                                location.longitude
                            )
                        )
                        .zoom(15.0)
                        .build()
                )
            )

            Result.success(location)
        } catch (exception: Exception) {
            Result.failure(exception)
        }
    }

    private suspend fun requestLocation(): Location =
        suspendCancellableCoroutine { continuation ->

            val fineGranted = ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            val preferredProvider = if (fineGranted) {
                LocationManager.GPS_PROVIDER
            } else {
                LocationManager.NETWORK_PROVIDER
            }

            val provider = when {
                locationManager.isProviderEnabled(preferredProvider) ->
                    preferredProvider

                locationManager.isProviderEnabled(
                    LocationManager.NETWORK_PROVIDER
                ) -> LocationManager.NETWORK_PROVIDER

                locationManager.isProviderEnabled(
                    LocationManager.GPS_PROVIDER
                ) -> LocationManager.GPS_PROVIDER

                else -> null
            } ?: throw IllegalStateException("שירותי המיקום כבויים במכשיר")

            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (continuation.isActive) {
                        continuation.resume(location)
                    }
                    locationManager.removeUpdates(this)
                }

                @Deprecated("Deprecated by Android")
                override fun onStatusChanged(
                    provider: String?,
                    status: Int,
                    extras: android.os.Bundle?
                ) = Unit

                override fun onProviderEnabled(provider: String) = Unit

                override fun onProviderDisabled(provider: String) = Unit
            }

            continuation.invokeOnCancellation {
                locationManager.removeUpdates(listener)
            }

            try {
                locationManager.requestLocationUpdates(
                    provider,
                    0L,
                    0f,
                    listener,
                    Looper.getMainLooper()
                )
            } catch (exception: Exception) {
                locationManager.removeUpdates(listener)
                if (continuation.isActive) {
                    continuation.resumeWithException(exception)
                }
            }
        }
}
