
package il.co.israeltransit.offline.maps

import android.content.Context
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import java.io.File

@Composable
fun OfflineMapView(
    pmtilesFile: File,
    modifier: Modifier = Modifier,
    initialLatitude: Double = 31.7683,
    initialLongitude: Double = 35.2137,
    initialZoom: Double = 12.0,
    onMapReady: ((MapLibreMap) -> Unit)? = null,
    onMapError: ((String) -> Unit)? = null
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val mapView = remember {
        createMapView(context)
    }

    DisposableEffect(mapView, lifecycleOwner) {
        var started = false
        var resumed = false

        mapView.onCreate(Bundle())

        fun startMap() {
            if (!started) {
                mapView.onStart()
                started = true
            }
        }

        fun resumeMap() {
            startMap()
            if (!resumed) {
                mapView.onResume()
                resumed = true
            }
        }

        fun pauseMap() {
            if (resumed) {
                mapView.onPause()
                resumed = false
            }
        }

        fun stopMap() {
            pauseMap()
            if (started) {
                mapView.onStop()
                started = false
            }
        }

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> startMap()
                Lifecycle.Event.ON_RESUME -> resumeMap()
                Lifecycle.Event.ON_PAUSE -> pauseMap()
                Lifecycle.Event.ON_STOP -> stopMap()
                else -> Unit
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)

        if (lifecycleOwner.lifecycle.currentState
                .isAtLeast(Lifecycle.State.STARTED)
        ) {
            startMap()
        }

        if (lifecycleOwner.lifecycle.currentState
                .isAtLeast(Lifecycle.State.RESUMED)
        ) {
            resumeMap()
        }

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            stopMap()
            mapView.onDestroy()
        }
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier,
        update = {
            if (!pmtilesFile.exists() || !pmtilesFile.isFile) {
                onMapError?.invoke("קובץ המפה אינו נמצא")
                return@AndroidView
            }

            if (pmtilesFile.length() <= 0L) {
                onMapError?.invoke("קובץ המפה ריק")
                return@AndroidView
            }

            mapView.getMapAsync { map ->
                try {
                    val styleJson =
                        OfflineMapStyle.create(pmtilesFile)

                    map.setStyle(
                        Style.Builder().fromJson(styleJson)
                    ) {
                        map.cameraPosition =
                            CameraPosition.Builder()
                                .target(
                                    LatLng(
                                        initialLatitude,
                                        initialLongitude
                                    )
                                )
                                .zoom(initialZoom)
                                .build()

                        map.uiSettings.apply {
                            isCompassEnabled = true
                            isRotateGesturesEnabled = true
                            isTiltGesturesEnabled = false
                            isZoomGesturesEnabled = true
                            isScrollGesturesEnabled = true
                        }

                        onMapReady?.invoke(map)
                    }
                } catch (exception: Exception) {
                    onMapError?.invoke(
                        exception.message
                            ?: "שגיאה בטעינת המפה"
                    )
                }
            }
        }
    )
}

private fun createMapView(context: Context): MapView {
    MapLibre.getInstance(context)
    return MapView(context)
}
