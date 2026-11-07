package il.co.israeltransit.offline.maps

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    val context = LocalContext.current
    val applicationContext = context.applicationContext

    val repository = remember(applicationContext) {
        TransitStopDetailsRepository(applicationContext)
    }

    val locationControl = remember(applicationContext) {
        TransitMyLocationControl(applicationContext)
    }

    val coroutineScope = rememberCoroutineScope()

    var activeMap by remember {
        mutableStateOf<MapLibreMap?>(null)
    }

    var selectedStop by remember {
        mutableStateOf<SelectedTransitStop?>(null)
    }

    var selectedLines by remember {
        mutableStateOf<List<TransitStopLine>>(emptyList())
    }

    var isLoadingLines by remember {
        mutableStateOf(false)
    }

    var linesError by remember {
        mutableStateOf<String?>(null)
    }

    fun moveToLocation() {
        val map = activeMap

        if (map == null) {
            Toast.makeText(
                context,
                "המפה עדיין נטענת",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        coroutineScope.launch {
            locationControl.moveToCurrentLocation(map)
                .onFailure { exception ->
                    Toast.makeText(
                        context,
                        exception.message
                            ?: "לא ניתן לקבל את מיקום המכשיר",
                        Toast.LENGTH_LONG
                    ).show()
                }
        }
    }

    val locationPermissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->

            val granted =
                permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                    permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true

            if (granted) {
                moveToLocation()
            } else {
                Toast.makeText(
                    context,
                    "לא ניתנה הרשאת מיקום. אפשר להמשיך להשתמש באפליקציה כרגיל.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    val mapReadyCallback = remember(
        context,
        pmtilesFile.absolutePath,
        onError
    ) {
        { map: MapLibreMap ->
            activeMap = map

            map.getStyle { style ->
                TransitStopMapLayer.install(
    context = applicationContext,
    style = style,
    map = map,
    onComplete = {
                        val clickListener =
                            MapLibreMap.OnMapClickListener { coordinate ->

                                val screenPoint =
    map.projection.toScreenLocation(coordinate)

if (
    TransitMapClusterController.handleClusterTap(
        map = map,
        style = style,
        screenPoint = screenPoint
    )
) {
    return@OnMapClickListener true
}

val features = map.queryRenderedFeatures(
    screenPoint,
    STOP_LAYER_ID
)

                                val feature = features.firstOrNull()

                                if (feature == null) {
                                    selectedStop = null
                                    selectedLines = emptyList()
                                    linesError = null
                                    isLoadingLines = false
                                    false
                                } else {
                                    val properties = feature.properties()

                                    val stopId = properties
                                        ?.get("stop_id")
                                        ?.takeIf { !it.isJsonNull }
                                        ?.asString ?: ""

                                    val stopName = properties
                                        ?.get("stop_name")
                                        ?.takeIf { !it.isJsonNull }
                                        ?.asString
                                        ?.takeIf { it.isNotBlank() }
                                        ?: "תחנה ללא שם"

                                    if (stopId.isNotBlank()) {
                                        selectedStop = SelectedTransitStop(
                                            id = stopId,
                                            name = stopName
                                        )

                                        selectedLines = emptyList()
                                        linesError = null
                                        isLoadingLines = true

                                        coroutineScope.launch {
                                            try {
                                                val lines = withContext(
                                                    Dispatchers.IO
                                                ) {
                                                    repository.getLinesForStop(
                                                        stopId
                                                    )
                                                }

                                                if (selectedStop?.id == stopId) {
                                                    selectedLines = lines
                                                }
                                            } catch (exception: Exception) {
                                                if (selectedStop?.id == stopId) {
                                                    linesError =
                                                        exception.message
                                                            ?: "לא ניתן לטעון את הקווים בתחנה"
                                                }
                                            } finally {
                                                if (selectedStop?.id == stopId) {
                                                    isLoadingLines = false
                                                }
                                            }
                                        }

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

        TransitMapControls(
            map = activeMap,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp)
        )

        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp)
        ) {
            FilledTonalButton(
                onClick = {
                    if (!locationControl.hasLocationProvider()) {
                        Toast.makeText(
                            context,
                            "לא זמינים במכשיר שירותי מיקום. אפשר להמשיך להשתמש באפליקציה כרגיל.",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        val fineGranted =
                            ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.ACCESS_FINE_LOCATION
                            ) == PackageManager.PERMISSION_GRANTED

                        val coarseGranted =
                            ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                            ) == PackageManager.PERMISSION_GRANTED

                        if (fineGranted || coarseGranted) {
                            moveToLocation()
                        } else {
                            locationPermissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION
                                )
                            )
                        }
                    }
                }
            ) {
                Text("המיקום שלי")
            }
        }

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

                    Text(
                        text = "קווים בתחנה",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(
                            top = 12.dp,
                            bottom = 6.dp
                        )
                    )

                    when {
                        isLoadingLines -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator()
                                Text(
                                    text = "טוען קווים...",
                                    modifier = Modifier.padding(start = 12.dp)
                                )
                            }
                        }

                        linesError != null -> {
                            Text(
                                text = linesError ?: "",
                                color = MaterialTheme.colorScheme.error
                            )
                        }

                        selectedLines.isEmpty() -> {
                            Text("לא נמצאו קווים לתחנה זו")
                        }

                        else -> {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp)
                            ) {
                                items(
                                    items = selectedLines,
                                    key = { it.routeId }
                                ) { line ->
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 5.dp)
                                    ) {
                                        Text(
                                            text = "קו ${line.lineNumber}",
                                            style = MaterialTheme.typography.bodyLarge
                                        )

                                        if (line.lineName.isNotBlank()) {
                                            Text(
                                                text = line.lineName,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    TextButton(
                        onClick = {
                            selectedStop = null
                            selectedLines = emptyList()
                            linesError = null
                            isLoadingLines = false
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
