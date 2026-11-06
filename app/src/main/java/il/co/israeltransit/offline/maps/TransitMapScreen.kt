
package il.co.israeltransit.offline.maps

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.maplibre.android.maps.MapLibreMap
import java.io.File

private const val STOP_LAYER_ID = "transit-stops-individual"

private data class SelectedTransitStop(
    val id: String,
    val name: String
)

@Composable
fun TransitMapScreen(
    pmtilesFile: File,
    modifier: Modifier = Modifier,
    initialLatitude: Double = 31.7683,
    initialLongitude: Double = 35.2137,
    initialZoom: Double = 12.0,
    onError: (String) -> Unit = {}
) {
    val context = LocalContext.current.applicationContext

    var selectedStop by remember {
        mutableStateOf<SelectedTransitStop?>(null)
    }

    val mapReadyCallback = remember(
        context,
        pmtilesFile.absolutePath,
        onError
    ) {
        { map: MapLibreMap ->
            map.getStyle { style ->
                TransitStopMapLayer.install(
                    context = context,
                    style = style,
                    onComplete = {
                        val clickListener =
                            MapLibreMap.OnMapClickListener { coordinate ->

                                val screenPoint =
                                    map.projection.toScreenLocation(
                                        coordinate
                                    )

                                val features =
                                    map.queryRenderedFeatures(
                                        screenPoint,
                                        STOP_LAYER_ID
                                    )

                                val feature = features.firstOrNull()

                                if (feature == null) {
                                    selectedStop = null
                                    false
                                } else {
                                    val properties =
                                        feature.properties()

                                    val stopId =
                                        properties
                                            ?.get("stop_id")
                                            ?.takeIf { !it.isJsonNull }
                                            ?.asString
                                            ?: ""

                                    val stopName =
                                        properties
                                            ?.get("stop_name")
                                            ?.takeIf { !it.isJsonNull }
                                            ?.asString
                                            ?.takeIf { it.isNotBlank() }
                                            ?: "תחנה ללא שם"

                                    if (stopId.isNotBlank()) {
                                        selectedStop =
                                            SelectedTransitStop(
                                                id = stopId,
                                                name = stopName
                                            )
                                        true
                                    } else {
                                        false
                                    }
                                }
                            }

                        map.addOnMapClickListener(clickListener)
                    },
                    onError = onError
                )
            }
        }
    }

    Box(
        modifier = modifier.fillMaxSize()
    ) {
        OfflineMapView(
            pmtilesFile = pmtilesFile,
            modifier = Modifier.fillMaxSize(),
            initialLatitude = initialLatitude,
            initialLongitude = initialLongitude,
            initialZoom = initialZoom,
            onMapReady = mapReadyCallback,
            onMapError = onError
        )

        selectedStop?.let { stop ->
            Card(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = stop.name,
                        style = MaterialTheme.typography.titleMedium
                    )

                    Text(
                        text = "מספר תחנה: ${stop.id}",
                        style = MaterialTheme.typography.bodyMedium
                    )

                    TextButton(
                        onClick = {
                            selectedStop = null
                        },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("סגירה")
                    }
                }
            }
        }
    }
}
