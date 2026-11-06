
package il.co.israeltransit.offline.maps

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import org.maplibre.android.maps.MapLibreMap
import java.io.File

/**
 * Complete offline transit map:
 * - Local PMTiles basemap
 * - Locally imported transit database
 * - Clustered public transport stops
 */
@Composable
fun TransitMapScreen(
    pmtilesFile: File,
    modifier: Modifier = Modifier,
    initialLatitude: Double = 31.7683,
    initialLongitude: Double = 35.2137,
    initialZoom: Double = 12.0,
    onError: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val appContext = context.applicationContext

    val mapReadyCallback = remember(
        appContext,
        pmtilesFile.absolutePath,
        onError
    ) {
        { map: MapLibreMap ->
            map.getStyle { style ->
                TransitStopMapLayer.install(
                    context = appContext,
                    style = style,
                    onComplete = {
                        // The stop layer is now installed.
                    },
                    onError = onError
                )
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        OfflineMapView(
            pmtilesFile = pmtilesFile,
            modifier = Modifier.fillMaxSize(),
            initialLatitude = initialLatitude,
            initialLongitude = initialLongitude,
            initialZoom = initialZoom,
            onMapReady = mapReadyCallback,
            onMapError = onError
        )
    }
}
