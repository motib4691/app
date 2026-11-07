package il.co.israeltransit.offline.maps

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.tasks.await
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap

class TransitMyLocationControl(
    context: Context
) {
    private val appContext = context.applicationContext
    private val fusedLocationClient =
        LocationServices.getFusedLocationProviderClient(appContext)

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
        if (!hasLocationPermission()) {
            return Result.failure(
                SecurityException("אין הרשאת מיקום")
            )
        }

        return try {
            val location = fusedLocationClient
                .getCurrentLocation(
                    Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                    null
                )
                .await()
                ?: throw IllegalStateException("לא התקבל מיקום מהמכשיר")

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
}
