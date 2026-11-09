package il.co.israeltransit.offline

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import il.co.israeltransit.offline.database.TransitDatabase
import il.co.israeltransit.offline.database.TransitRepository
import il.co.israeltransit.offline.maps.OfflineMapStorage
import il.co.israeltransit.offline.maps.OfflineMapView
import il.co.israeltransit.offline.routing.FareCalculator
import il.co.israeltransit.offline.routing.FareResult
import il.co.israeltransit.offline.routing.TransitRouteEngine
import il.co.israeltransit.offline.ui.OperatorTheme
import il.co.israeltransit.offline.ui.TransitLineBadge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val plannerTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

@Composable
fun RoutePlannerScreen(
    onBack: () -> Unit,
    initialOrigin: TransitRepository.TransitStop? = null,
    initialDestination: TransitRepository.TransitStop? = null,
    onClearInitialStops: () -> Unit = {}
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val database = remember(context) { TransitDatabase(context) }
    val repository = remember(database) { TransitRepository(database) }
    val routeEngine = remember(database) { TransitRouteEngine(database) }
    val mapStorage = remember(context) { OfflineMapStorage(context) }
    val settings = remember(context) { TransitSettings(context) }
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

    var routeResult by remember { mutableStateOf<TransitRouteEngine.SearchResult?>(null) }
    var departureTime by remember { mutableStateOf(LocalDateTime.now()) }

    var expandedJourneyIndex by remember { mutableIntStateOf(-1) }
    var detailedJourney by remember { mutableStateOf<TransitRouteEngine.Journey?>(null) }
    var isMapFullScreen by remember { mutableStateOf(false) }
    var fareInfoToShow by remember { mutableStateOf<FareResult?>(null) }

    val originFocusRequester = remember { FocusRequester() }
    val destFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(200)
        originFocusRequester.requestFocus()
    }

    LaunchedEffect(initialOrigin, initialDestination) {
        if (initialOrigin != null) {
            selectedOrigin = initialOrigin
            originQuery = initialOrigin.name
            activeField = "destination"
        }
        if (initialDestination != null) {
            selectedDestination = initialDestination
            destinationQuery = initialDestination.name
        }
        if (initialOrigin != null || initialDestination != null) {
            onClearInitialStops()
        }
    }

    LaunchedEffect(originQuery, activeField) {
        if (activeField != "origin") return@LaunchedEffect
        val q = originQuery.trim()
        if (q.isEmpty()) {
            originResults = emptyList()
        } else {
            delay(200)
            searching = true
            originResults = withContext(Dispatchers.IO) { repository.searchStops(q, 30) }
            searching = false
        }
    }

    LaunchedEffect(destinationQuery, activeField) {
        if (activeField != "destination") return@LaunchedEffect
        val q = destinationQuery.trim()
        if (q.isEmpty()) {
            destinationResults = emptyList()
        } else {
            delay(200)
            searching = true
            destinationResults = withContext(Dispatchers.IO) { repository.searchStops(q, 30) }
            searching = false
        }
    }

    fun triggerSearch() {
        val orig = selectedOrigin ?: return
        val dest = selectedDestination ?: return
        if (orig.stopId == dest.stopId || calculating) return

        calculating = true
        routeResult = null
        expandedJourneyIndex = -1

        scope.launch {
            val result = withContext(Dispatchers.IO) {
                routeEngine.findJourneys(
                    originStopId = orig.stopId,
                    destinationStopId = dest.stopId,
                    departureTime = departureTime,
                    maxTransfers = settings.maxTransfers,
                    minimumTransferMinutes = settings.minTransferMinutes.toLong(),
                    allowWalkingTransfers = settings.allowWalkingTransfers,
                    maxResults = 5
                )
            }
            routeResult = result
            calculating = false
        }
    }

    // הפעלה אוטומטית ברגע ששני השדות מלאים
    LaunchedEffect(selectedOrigin, selectedDestination) {
        if (selectedOrigin != null && selectedDestination != null && selectedOrigin?.stopId != selectedDestination?.stopId) {
            triggerSearch()
        }
    }

    BackHandler(enabled = detailedJourney != null || isMapFullScreen) {
        if (isMapFullScreen) isMapFullScreen = false
        else detailedJourney = null
    }

    // דיאלוג חישוב תעריף נסיעה ('דרך שווה')
    fareInfoToShow?.let { fare ->
        AlertDialog(
            onDismissRequest = { fareInfoToShow = null },
            title = { Text("תעריף נסיעה משוער ('דרך שווה')") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("מחיר נסיעה: ${fare.priceNis} ₪", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = MaterialTheme.colorScheme.primary)
                    Text(fare.description, fontSize = 13.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = { fareInfoToShow = null }) { Text("סגירה") }
            }
        )
    }

    // מסך פירוט מסלול מלא ייעודי
    if (detailedJourney != null) {
        val journey = detailedJourney!!
        val totalMeters = journey.legs.sumOf { leg ->
            TransitRouteEngine.calculateDistanceMeters(leg.fromLat, leg.fromLon, leg.toLat, leg.toLon) ?: 3000.0
        }
        val fare = remember(journey) {
            FareCalculator.calculateFare(totalMeters, journey.legs.any { it.routeId.contains("train") }, journey.durationMinutes)
        }

        if (isMapFullScreen) {
            Box(modifier = Modifier.fillMaxSize()) {
                OfflineMapView(
                    pmtilesFile = mapStorage.getMapFile(),
                    modifier = Modifier.fillMaxSize()
                )
                FilledTonalButton(
                    onClick = { isMapFullScreen = false },
                    modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Text("יציאה ממסך מלא")
                }
            }
            return
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
                Text("פירוט מסלול מלא", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                OutlinedButton(onClick = { detailedJourney = null }, shape = RoundedCornerShape(20.dp)) { Text("חזרה") }
            }

            Text(
                "${journey.departureAt.format(plannerTimeFormatter)} – ${journey.arrivalAt.format(plannerTimeFormatter)} (${journey.durationMinutes} דק')",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = {
                        val summary = buildString {
                            append("פירוט מסלול נסיעה:\n")
                            append("יציאה: ${journey.departureAt.format(plannerTimeFormatter)} | הגעה: ${journey.arrivalAt.format(plannerTimeFormatter)}\n")
                            journey.legs.forEachIndexed { i, leg ->
                                append("${i + 1}. קו ${leg.lineNumber} (${leg.agencyName}) מ-${leg.fromStopName} ל-${leg.toStopName}\n")
                            }
                        }
                        clipboardManager.setText(AnnotatedString(summary))
                        Toast.makeText(context, "סיכום המסלול הועתק ללוח", Toast.LENGTH_SHORT).show()
                    },
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Text("📋 העתק סיכום נסיעה", fontSize = 12.sp)
                }

                FilledTonalButton(
                    onClick = { fareInfoToShow = fare },
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Text("💳 תעריף: ${fare.priceNis} ₪", fontSize = 12.sp)
                }
            }

            journey.legs.forEachIndexed { idx, leg ->
                var showStopsList by remember { mutableStateOf(false) }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(1.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TransitLineBadge(lineNumber = leg.lineNumber, agencyName = leg.agencyName)
                            Text(leg.lineName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("עליה בתחנה: ${leg.fromStopName} בשעה ${leg.departureAt.format(plannerTimeFormatter)}", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text(
                            text = "נסיעה ➔ לחץ להצגת תחנות ביניים",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { showStopsList = !showStopsList }
                        )
                        AnimatedVisibility(visible = showStopsList) {
                            Text("תחנת עלייה: ${leg.fromStopName}\n...\nתחנת ירידה: ${leg.toStopName}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp))
                        }
                        Text("ירידה בתחנה: ${leg.toStopName} בשעה ${leg.arrivalAt.format(plannerTimeFormatter)}", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }

            // מפה מוקטנת אינטראקטיבית בתחתית המסך
            if (mapStorage.isMapAvailable()) {
                Card(
                    modifier = Modifier.fillMaxWidth().height(220.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        OfflineMapView(
                            pmtilesFile = mapStorage.getMapFile(),
                            modifier = Modifier.fillMaxSize()
                        )
                        FilledTonalButton(
                            onClick = { isMapFullScreen = true },
                            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Text("מסך מלא", fontSize = 11.sp)
                        }
                    }
                }
            }
        }
        return
    }

    // מסך תכנון מסלול ראשי
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("תכנון מסלול", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            OutlinedButton(onClick = onBack, shape = RoundedCornerShape(20.dp)) { Text("חזרה") }
        }

        // שדה מוצא
        OutlinedTextField(
            value = originQuery,
            onValueChange = {
                originQuery = it
                selectedOrigin = null
                activeField = "origin"
            },
            modifier = Modifier.fillMaxWidth().focusRequester(originFocusRequester),
            label = { Text("תחנת מוצא (שם או מספר)") },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            trailingIcon = {
                if (originQuery.isNotEmpty()) {
                    IconButton(onClick = { originQuery = ""; selectedOrigin = null }) { Text("✕") }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { destFocusRequester.requestFocus() })
        )

        if (activeField == "origin") {
            originResults.take(6).forEach { stop ->
                Card(
                    modifier = Modifier.fillMaxWidth().clickable {
                        selectedOrigin = stop
                        originQuery = stop.name
                        originResults = emptyList()
                        activeField = "destination"
                        destFocusRequester.requestFocus()
                    },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(stop.name, fontWeight = FontWeight.Bold)
                        Text("תחנה ${stop.stopCode}", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }

        // שדה יעד
        OutlinedTextField(
            value = destinationQuery,
            onValueChange = {
                destinationQuery = it
                selectedDestination = null
                activeField = "destination"
            },
            modifier = Modifier.fillMaxWidth().focusRequester(destFocusRequester),
            label = { Text("תחנת יעד (שם או מספר)") },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            trailingIcon = {
                if (destinationQuery.isNotEmpty()) {
                    IconButton(onClick = { destinationQuery = ""; selectedDestination = null }) { Text("✕") }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { triggerSearch() })
        )

        if (activeField == "destination") {
            destinationResults.take(6).forEach { stop ->
                Card(
                    modifier = Modifier.fillMaxWidth().clickable {
                        selectedDestination = stop
                        destinationQuery = stop.name
                        destinationResults = emptyList()
                        triggerSearch()
                    },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(stop.name, fontWeight = FontWeight.Bold)
                        Text("תחנה ${stop.stopCode}", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }

        if (calculating) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator()
            }
        }

        // תוצאות חיפוש באקורדיון
        routeResult?.let { res ->
            if (res.journeys.isEmpty()) {
                Text(res.error ?: "לא נמצאו מסלולים מתאימים.")
            } else {
                Text("תוצאות חיפוש", fontWeight = FontWeight.Bold, fontSize = 16.sp)

                res.journeys.forEachIndexed { idx, journey ->
                    val isExpanded = expandedJourneyIndex == idx
                    val diffMin = Duration.between(LocalDateTime.now(), journey.departureAt).toMinutes().coerceAtLeast(0)

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(1.dp)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            // שורה מכווצת לריפרוף מהיר
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { expandedJourneyIndex = if (isExpanded) -1 else idx },
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text("${journey.durationMinutes} דק'", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                        journey.legs.forEachIndexed { i, leg ->
                                            TransitLineBadge(lineNumber = leg.lineNumber, agencyName = leg.agencyName)
                                            if (i < journey.legs.lastIndex) Text("➔", fontSize = 11.sp)
                                        }
                                    }
                                }

                                Column(horizontalAlignment = Alignment.End) {
                                    Text("${journey.departureAt.format(plannerTimeFormatter)} ➔ ${journey.arrivalAt.format(plannerTimeFormatter)}", fontWeight = FontWeight.Bold)
                                    Text("עוד $diffMin דקות", color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                            }

                            // שורה מורחבת בלחיצה
                            AnimatedVisibility(visible = isExpanded) {
                                Column(modifier = Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    journey.legs.forEach { leg ->
                                        Text("• קו ${leg.lineNumber} מ-${leg.fromStopName} ל-${leg.toStopName}", style = MaterialTheme.typography.bodySmall)
                                    }
                                    Text("יציאות נוספות: ${journey.departureAt.plusMinutes(15).format(plannerTimeFormatter)} • ${journey.departureAt.plusMinutes(30).format(plannerTimeFormatter)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                                    Button(
                                        onClick = { detailedJourney = journey },
                                        shape = RoundedCornerShape(20.dp),
                                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                                    ) {
                                        Text("צפה בפירוט המסלול המלא")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
