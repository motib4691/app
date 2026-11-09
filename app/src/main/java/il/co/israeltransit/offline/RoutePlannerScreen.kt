package il.co.israeltransit.offline

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import il.co.israeltransit.offline.database.TransitDatabase
import il.co.israeltransit.offline.database.TransitRepository
import il.co.israeltransit.offline.maps.OfflineMapStorage
import il.co.israeltransit.offline.maps.OfflineMapView
import il.co.israeltransit.offline.maps.TransitMyLocationControl
import il.co.israeltransit.offline.routing.TransitRouteEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.io.File
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private data class PlannerSavedStop(
    val stopId: String,
    val stopName: String,
    val customName: String,
    val stopCode: String = stopId
)

private fun loadPlannerSavedStops(context: android.content.Context): List<PlannerSavedStop> {
    return try {
        val json = context.getSharedPreferences(
            "saved_transit_stops",
            android.content.Context.MODE_PRIVATE
        ).getString("stops", "[]") ?: "[]"

        val array = JSONArray(json)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val sId = item.optString("stopId")
                val sCode = item.optString("stopCode").ifBlank { sId }
                add(
                    PlannerSavedStop(
                        stopId = sId,
                        stopName = item.optString("stopName"),
                        customName = item.optString("customName"),
                        stopCode = sCode
                    )
                )
            }
        }
    } catch (_: Exception) {
        emptyList()
    }
}

private val plannerTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

@Composable
fun RoutePlannerScreen(
    onBack: () -> Unit,
    initialOrigin: TransitRepository.TransitStop? = null,
    initialDestination: TransitRepository.TransitStop? = null,
    onClearInitialStops: () -> Unit = {}
) {
    val context = LocalContext.current

    val database = remember(context) { TransitDatabase(context) }
    val repository = remember(database) { TransitRepository(database) }
    val routeEngine = remember(database) { TransitRouteEngine(database) }
    val mapStorage = remember(context) { OfflineMapStorage(context) }
    val settings = remember(context) { TransitSettings(context) }
    val locationControl = remember(context) { TransitMyLocationControl(context) }

    val scope = rememberCoroutineScope()

    var originQuery by remember { mutableStateOf("") }
    var destinationQuery by remember { mutableStateOf("") }

    var originResults by remember { mutableStateOf(emptyList<TransitRepository.TransitStop>()) }
    var destinationResults by remember { mutableStateOf(emptyList<TransitRepository.TransitStop>()) }

    var selectedOrigin by remember { mutableStateOf<TransitRepository.TransitStop?>(null) }
    var selectedDestination by remember { mutableStateOf<TransitRepository.TransitStop?>(null) }

    var activeField by remember { mutableStateOf("origin") }
    var searching by remember { mutableStateOf(false) }
    var calculating by remember { mutableStateOf(false) }
    var detectingLocation by remember { mutableStateOf(false) }

    var routeResult by remember { mutableStateOf<TransitRouteEngine.SearchResult?>(null) }
    var departureTime by remember { mutableStateOf(LocalDateTime.now()) }

    var journeyForMap by remember { mutableStateOf<TransitRouteEngine.Journey?>(null) }
    var showMapNotInstalledDialog by remember { mutableStateOf(false) }

    val savedStops = remember(context) { loadPlannerSavedStops(context) }

    fun findAndSetNearestStopFromGPS() {
        detectingLocation = true
        scope.launch {
            val locationResult = locationControl.getCurrentLocation()
            detectingLocation = false

            locationResult.onSuccess { loc ->
                val nearest = withContext(Dispatchers.IO) {
                    repository.findNearestStop(loc.latitude, loc.longitude)
                }

                if (nearest != null) {
                    selectedOrigin = nearest
                    originQuery = nearest.name
                    originResults = emptyList()
                    activeField = "destination"
                    routeResult = null
                    Toast.makeText(
                        context,
                        "נבחרה התחנה הקרובה אליך: ${nearest.name} (תחנה ${nearest.stopCode})",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(
                        context,
                        "לא נמצאו תחנות בקרבת מיקומך.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }.onFailure {
                Toast.makeText(
                    context,
                    "לא ניתן לקבל מיקום כעת. אפשר לבחור תחנה ידנית.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true

        if (granted) {
            findAndSetNearestStopFromGPS()
        } else {
            Toast.makeText(
                context,
                "לא ניתנה הרשאת מיקום. באפשרותך לבחור תחנה ידנית.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    LaunchedEffect(initialOrigin, initialDestination) {
        if (initialOrigin != null) {
            selectedOrigin = initialOrigin
            originQuery = initialOrigin.name
            originResults = emptyList()
            activeField = "destination"
        }
        if (initialDestination != null) {
            selectedDestination = initialDestination
            destinationQuery = initialDestination.name
            destinationResults = emptyList()
            activeField = "origin"
        }
        if (initialOrigin != null || initialDestination != null) {
            routeResult = null
            onClearInitialStops()
        }
    }

    LaunchedEffect(originQuery, activeField) {
        if (activeField != "origin") return@LaunchedEffect
        val value = originQuery.trim()
        if (value.isEmpty()) {
            originResults = emptyList()
        } else {
            delay(250)
            searching = true
            originResults = withContext(Dispatchers.IO) {
                repository.searchStops(value, 30)
            }
            searching = false
        }
    }

    LaunchedEffect(destinationQuery, activeField) {
        if (activeField != "destination") return@LaunchedEffect
        val value = destinationQuery.trim()
        if (value.isEmpty()) {
            destinationResults = emptyList()
        } else {
            delay(250)
            searching = true
            destinationResults = withContext(Dispatchers.IO) {
                repository.searchStops(value, 30)
            }
            searching = false
        }
    }

    fun chooseStop(stop: TransitRepository.TransitStop) {
        if (activeField == "origin") {
            selectedOrigin = stop
            originQuery = stop.name
            originResults = emptyList()
        } else {
            selectedDestination = stop
            destinationQuery = stop.name
            destinationResults = emptyList()
        }
        routeResult = null
    }

    fun searchRoute() {
        val origin = selectedOrigin ?: return
        val destination = selectedDestination ?: return

        if (origin.stopId == destination.stopId || calculating) return

        val requestedDepartureTime = departureTime
        val maxTransfers = settings.maxTransfers
        val minBufferMinutes = settings.minTransferMinutes.toLong()
        val allowWalking = settings.allowWalkingTransfers

        calculating = true
        routeResult = null

        scope.launch {
            val result = withContext(Dispatchers.IO) {
                routeEngine.findJourneys(
                    originStopId = origin.stopId,
                    destinationStopId = destination.stopId,
                    departureTime = requestedDepartureTime,
                    maxTransfers = maxTransfers,
                    minimumTransferMinutes = minBufferMinutes,
                    allowWalkingTransfers = allowWalking,
                    maxResults = 5
                )
            }
            routeResult = result
            calculating = false
        }
    }

    if (journeyForMap != null) {
        val journey = journeyForMap!!
        val firstLeg = journey.legs.firstOrNull()
        val initialLat = firstLeg?.fromLat ?: 31.7683
        val initialLon = firstLeg?.fromLon ?: 35.2137
        val mapFile: File = mapStorage.getMapFile()

        Column(modifier = Modifier.fillMaxSize()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 4.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "מפת המסלול הנבחר",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "${journey.departureAt.format(plannerTimeFormatter)} – ${journey.arrivalAt.format(plannerTimeFormatter)} (${journey.durationMinutes} דק')",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    OutlinedButton(onClick = { journeyForMap = null }) {
                        Text("סגירת מפה")
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                OfflineMapView(
                    pmtilesFile = mapFile,
                    modifier = Modifier.fillMaxSize(),
                    initialLatitude = initialLat,
                    initialLongitude = initialLon,
                    initialZoom = 13.0,
                    onMapReady = { map ->
                        map.getStyle { style ->
                            val validCoords = buildList {
                                journey.legs.forEach { leg ->
                                    if (leg.fromLat != null && leg.fromLon != null) add(LatLng(leg.fromLat, leg.fromLon))
                                    if (leg.toLat != null && leg.toLon != null) add(LatLng(leg.toLat, leg.toLon))
                                }
                            }

                            if (validCoords.isNotEmpty()) {
                                val boundsBuilder = LatLngBounds.Builder()
                                validCoords.forEach { boundsBuilder.include(it) }

                                try {
                                    map.easeCamera(
                                        CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), 120),
                                        1200
                                    )
                                } catch (_: Exception) {
                                    map.easeCamera(CameraUpdateFactory.newLatLngZoom(validCoords.first(), 14.0))
                                }

                                val linePoints = validCoords.map { Point.fromLngLat(it.longitude, it.latitude) }
                                if (linePoints.size >= 2) {
                                    val lineString = LineString.fromLngLats(linePoints)
                                    val lineSourceId = "journey-path-source"
                                    val lineLayerId = "journey-path-layer"

                                    if (style.getLayer(lineLayerId) != null) style.removeLayer(lineLayerId)
                                    if (style.getSource(lineSourceId) != null) style.removeSource(lineSourceId)

                                    style.addSource(GeoJsonSource(lineSourceId, Feature.fromGeometry(lineString)))
                                    style.addLayer(
                                        LineLayer(lineLayerId, lineSourceId).withProperties(
                                            lineColor("#1769AA"),
                                            lineWidth(4.5f),
                                            lineCap("round"),
                                            lineJoin("round")
                                        )
                                    )
                                }

                                val stopFeatures = validCoords.mapIndexed { idx, coord ->
                                    val color = when (idx) {
                                        0 -> "#2E7D32"              // ירוק לתחנת מוצא
                                        validCoords.lastIndex -> "#C62828" // אדום לתחנת יעד
                                        else -> "#EF6C00"           // כתום לתחנות החלפה
                                    }
                                    val f = Feature.fromGeometry(Point.fromLngLat(coord.longitude, coord.latitude))
                                    f.addStringProperty("marker_color", color)
                                    f
                                }

                                val stopsSourceId = "journey-stops-source"
                                val stopsLayerId = "journey-stops-layer"

                                if (style.getLayer(stopsLayerId) != null) style.removeLayer(stopsLayerId)
                                if (style.getSource(stopsSourceId) != null) style.removeSource(stopsSourceId)

                                style.addSource(GeoJsonSource(stopsSourceId, FeatureCollection.fromFeatures(stopFeatures)))
                                style.addLayer(
                                    CircleLayer(stopsLayerId, stopsSourceId).withProperties(
                                        circleColor(Expression.get("marker_color")),
                                        circleRadius(7.5f),
                                        circleStrokeColor("#FFFFFF"),
                                        circleStrokeWidth(2.5f)
                                    )
                                )
                            }
                        }
                    }
                )

                Card(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(16.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "שלבי המסלול:",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        journey.legs.forEachIndexed { idx, leg ->
                            Text(
                                text = "${idx + 1}. קו ${leg.lineNumber}: מ-${leg.fromStopName} ל-${leg.toStopName}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        journey.finalWalk?.let { walk ->
                            Text(
                                text = "הליכה מסכמת: כ-${walk.distanceMeters.roundToInt()} מ' אל ${walk.toStopName}",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                }
            }
        }
        return
    }

    if (showMapNotInstalledDialog) {
        AlertDialog(
            onDismissRequest = { showMapNotInstalledDialog = false },
            title = { Text("מפה לא מותקנת") },
            text = {
                Text(
                    "קובץ המפה לא הותקן עדיין במכשיר.\n\n" +
                    "תכנון הנסיעה, זמני ההמתנה וזמני ההליכה המלאים פועלים ומוצגים כאן ברשימה גם ללא מפה.\n\n" +
                    "כדי לצפות במסלול על גבי מפה וקטורית, תוכל לייבא קובץ מפה במסך 'מפה לא מקוונת'."
                )
            },
            confirmButton = {
                TextButton(onClick = { showMapNotInstalledDialog = false }) {
                    Text("הבנתי")
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "תכנון מסלול",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            OutlinedButton(onClick = onBack) {
                Text("חזרה")
            }
        }

        val walkingNote = if (!settings.allowWalkingTransfers) " | החלפה באותה תחנה בלבד" else ""
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (settings.maxTransfers == 0) "חיפוש קווים ישירים בלבד"
                           else "עד ${settings.maxTransfers} החלפות | מרווח: ${settings.minTransferMinutes} דק'$walkingNote",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "לפי הגדרות",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }

        Text(
            "בחר את נקודת המוצא ואת היעד. אפשר לחפש לפי שם תחנה או מספר תחנה מהשלט.",
            style = MaterialTheme.typography.bodyMedium
        )

        if (!database.isDatabaseAvailable()) {
            Card {
                Text(
                    "כדי לתכנן מסלול, יש להתקין תחילה את קובץ הנתונים במסך עדכון נתונים.",
                    modifier = Modifier.padding(16.dp)
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("מוצא", fontWeight = FontWeight.Bold)

                OutlinedButton(
                    onClick = {
                        val fineGranted = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.ACCESS_FINE_LOCATION
                        ) == PackageManager.PERMISSION_GRANTED
                        val coarseGranted = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        ) == PackageManager.PERMISSION_GRANTED

                        if (fineGranted || coarseGranted) {
                            findAndSetNearestStopFromGPS()
                        } else {
                            locationPermissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION
                                )
                            )
                        }
                    },
                    enabled = !detectingLocation
                ) {
                    Text(
                        if (detectingLocation) "מאתר תחנה..."
                        else "המיקום שלי",
                        fontSize = 12.sp
                    )
                }
            }

            OutlinedTextField(
                value = originQuery,
                onValueChange = {
                    originQuery = it
                    selectedOrigin = null
                    activeField = "origin"
                    routeResult = null
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { activeField = "origin" },
                label = { Text("תחנת מוצא (שם או מספר מהשלט)") },
                singleLine = true
            )

            if (activeField == "origin") {
                originResults.forEach { stop ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { chooseStop(stop) }
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(stop.name, fontWeight = FontWeight.Bold)
                            Text(
                                "תחנה ${stop.stopCode}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            Text("יעד", fontWeight = FontWeight.Bold)

            OutlinedTextField(
                value = destinationQuery,
                onValueChange = {
                    destinationQuery = it
                    selectedDestination = null
                    activeField = "destination"
                    routeResult = null
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("תחנת יעד (שם או מספר מהשלט)") },
                singleLine = true
            )

            if (activeField == "destination") {
                destinationResults.forEach { stop ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { chooseStop(stop) }
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(stop.name, fontWeight = FontWeight.Bold)
                            Text(
                                "תחנה ${stop.stopCode}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            if (searching) {
                Text("מחפש תחנות...")
            }

            OutlinedButton(
                onClick = {
                    val oldOrigin = selectedOrigin
                    selectedOrigin = selectedDestination
                    selectedDestination = oldOrigin

                    originQuery = selectedOrigin?.name.orEmpty()
                    destinationQuery = selectedDestination?.name.orEmpty()

                    originResults = emptyList()
                    destinationResults = emptyList()
                    routeResult = null
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("החלפת מוצא ויעד")
            }

            if (savedStops.isNotEmpty()) {
                Text("התחנות שלי", fontWeight = FontWeight.Bold)

                savedStops.forEach { saved ->
                    OutlinedButton(
                        onClick = {
                            val stop = TransitRepository.TransitStop(
                                stopId = saved.stopId,
                                name = saved.stopName,
                                latitude = null,
                                longitude = null,
                                stopCode = saved.stopCode
                            )
                            chooseStop(stop)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("${saved.customName} — ${saved.stopName} (תחנה ${saved.stopCode})")
                    }
                }

                Text(
                    "לחיצה על תחנה שמורה תציב אותה בשדה הפעיל.",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            DepartureDateTimePicker(
                value = departureTime,
                onValueChange = {
                    departureTime = it
                    routeResult = null
                },
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = { searchRoute() },
                modifier = Modifier.fillMaxWidth(),
                enabled = selectedOrigin != null &&
                    selectedDestination != null &&
                    selectedOrigin?.stopId != selectedDestination?.stopId &&
                    !calculating
            ) {
                Text(
                    if (calculating) "מחשב מסלול..."
                    else if (settings.maxTransfers == 0) "חפש קו ישיר"
                    else "חפש מסלול"
                )
            }

            if (calculating) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator()
                }
                Text("מחפש נסיעות מתאימות בלוח הזמנים המקומי...")
            }

            if (
                selectedOrigin != null &&
                selectedDestination != null &&
                selectedOrigin?.stopId == selectedDestination?.stopId
            ) {
                Text(
                    "המוצא והיעד זהים. יש לבחור שתי תחנות שונות.",
                    color = MaterialTheme.colorScheme.error
                )
            }

            routeResult?.let { result ->
                Spacer(modifier = Modifier.height(8.dp))

                if (result.error != null) {
                    Card {
                        Text(
                            result.error,
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                } else if (result.journeys.isEmpty()) {
                    Card {
                        Text(
                            "לא נמצאו מסלולים מתאימים במועד המבוקש.",
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                } else {
                    Text(
                        "תוצאות חיפוש",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )

                    result.journeys.forEachIndexed { index, journey ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "אפשרות ${index + 1}",
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )

                                    if (journey.transfers == 0) {
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f)
                                        ) {
                                            Text(
                                                text = "נסיעה ישירה",
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                                color = MaterialTheme.colorScheme.secondary,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 12.sp
                                            )
                                        }
                                    } else {
                                        Text(
                                            "${journey.transfers} החלפות",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                Text(
                                    "${journey.departureAt.format(plannerTimeFormatter)} – ${journey.arrivalAt.format(plannerTimeFormatter)}",
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold
                                )

                                Text(
                                    "משך הנסיעה: ${journey.durationMinutes} דקות",
                                    fontWeight = FontWeight.Medium
                                )

                                journey.legs.forEachIndexed { legIndex, leg ->
                                    Spacer(modifier = Modifier.height(4.dp))

                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.background,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.padding(12.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text(
                                                    "נסיעה ${legIndex + 1}: קו ${leg.lineNumber}",
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                                Text(
                                                    leg.agencyName,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }

                                            if (leg.lineName.isNotBlank()) {
                                                Text(leg.lineName, style = MaterialTheme.typography.bodySmall)
                                            }

                                            Spacer(modifier = Modifier.height(4.dp))

                                            Text(
                                                "יציאה: ${leg.departureAt.format(plannerTimeFormatter)} מ־${leg.fromStopName}"
                                            )
                                            Text(
                                                "הגעה: ${leg.arrivalAt.format(plannerTimeFormatter)} עד ${leg.toStopName}"
                                            )
                                        }
                                    }

                                    // פירוט הליכה והמתנה בין נסיעות
                                    if (legIndex < journey.legs.lastIndex) {
                                        val nextLeg = journey.legs[legIndex + 1]
                                        val waitMinutes = Duration.between(leg.arrivalAt, nextLeg.departureAt).toMinutes()

                                        val sameStop = leg.toStopId == nextLeg.fromStopId
                                        val walkDistance = if (!sameStop) {
                                            TransitRouteEngine.calculateDistanceMeters(
                                                leg.toLat, leg.toLon,
                                                nextLeg.fromLat, nextLeg.fromLon
                                            )
                                        } else null

                                        val walkMinutes = TransitRouteEngine.estimateWalkingMinutes(walkDistance)

                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.08f),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Column(modifier = Modifier.padding(10.dp)) {
                                                if (sameStop) {
                                                    Text(
                                                        "החלפה באותה תחנה",
                                                        fontWeight = FontWeight.Bold,
                                                        color = MaterialTheme.colorScheme.secondary
                                                    )
                                                    Text(
                                                        "המתנה לקו ${nextLeg.lineNumber}: כ-${waitMinutes} דקות",
                                                        style = MaterialTheme.typography.bodySmall
                                                    )
                                                } else {
                                                    val distText = walkDistance?.let { "כ-${it.roundToInt()} מטרים " } ?: ""
                                                    Text(
                                                        "הליכה בין תחנות ההחלפה",
                                                        fontWeight = FontWeight.Bold,
                                                        color = MaterialTheme.colorScheme.secondary
                                                    )
                                                    Text(
                                                        "הליכה ${distText}(כ-${walkMinutes} דק') מ־${leg.toStopName} ל־${nextLeg.fromStopName}",
                                                        style = MaterialTheme.typography.bodySmall
                                                    )
                                                    val remainingWait = (waitMinutes - walkMinutes).coerceAtLeast(0L)
                                                    Text(
                                                        "זמן המתנה צפוי לקו ${nextLeg.lineNumber}: כ-${remainingWait} דקות",
                                                        style = MaterialTheme.typography.bodySmall
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                // פירוט הליכה מסכמת אל תחנת היעד (אם קיימת)
                                journey.finalWalk?.let { walk ->
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.padding(10.dp)) {
                                            Text(
                                                "הליכה מסכמת אל היעד",
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.secondary
                                            )
                                            Text(
                                                "הליכה של כ-${walk.distanceMeters.roundToInt()} מטרים (כ-${walk.walkMinutes} דקות) מ־${walk.fromStopName} אל ${walk.toStopName}",
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(4.dp))

                                FilledTonalButton(
                                    onClick = {
                                        if (mapStorage.isMapAvailable()) {
                                            journeyForMap = journey
                                        } else {
                                            showMapNotInstalledDialog = true
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("הצג מסלול על המפה")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
