package il.co.israeltransit.offline

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import il.co.israeltransit.offline.database.TransitDatabase
import il.co.israeltransit.offline.database.TransitRepository
import il.co.israeltransit.offline.maps.OfflineMapStorage
import il.co.israeltransit.offline.maps.OfflineMapView
import il.co.israeltransit.offline.schedule.TransitScheduleRepository
import il.co.israeltransit.offline.ui.OperatorTheme
import il.co.israeltransit.offline.ui.TransitLineBadge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private object SearchHistoryHelper {
    fun getHistory(context: Context, prefKey: String): List<String> {
        val prefs = context.getSharedPreferences("transit_search_histories", Context.MODE_PRIVATE)
        val json = prefs.getString(prefKey, "[]") ?: "[]"
        return try {
            val array = JSONArray(json)
            List(array.length()) { array.getString(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun addHistory(context: Context, prefKey: String, item: String) {
        val trimmed = item.trim()
        if (trimmed.isBlank()) return
        val current = getHistory(context, prefKey).toMutableList()
        current.remove(trimmed)
        current.add(0, trimmed)
        val array = JSONArray(current.take(8))
        context.getSharedPreferences("transit_search_histories", Context.MODE_PRIVATE)
            .edit().putString(prefKey, array.toString()).apply()
    }

    fun removeHistory(context: Context, prefKey: String, item: String) {
        val current = getHistory(context, prefKey).toMutableList()
        current.remove(item)
        val array = JSONArray(current)
        context.getSharedPreferences("transit_search_histories", Context.MODE_PRIVATE)
            .edit().putString(prefKey, array.toString()).apply()
    }
}

private data class RouteVariant(
    val directionId: String,
    val headsign: String,
    val stops: List<TransitRepository.RouteStop>
)

private class RouteVariantRepository(
    private val database: TransitDatabase
) {
    fun getVariants(routeId: String): List<RouteVariant> {
        val db = database.openReadOnly() ?: return emptyList()
        val variants = linkedMapOf<String, RouteVariant>()

        try {
            db.rawQuery(
                """
                SELECT
                    t.trip_id,
                    COALESCE(t.direction_id, ''),
                    COALESCE(t.trip_headsign, ''),
                    st.stop_id,
                    COALESCE(s.stop_name, ''),
                    st.stop_sequence,
                    st.arrival_time,
                    st.departure_time,
                    COALESCE(NULLIF(s.stop_code, ''), s.stop_id) AS real_stop_code
                FROM trips t
                JOIN stop_times st ON st.trip_id = t.trip_id
                LEFT JOIN stops s ON s.stop_id = st.stop_id
                WHERE t.route_id = ?
                ORDER BY t.trip_id, st.stop_sequence
                """.trimIndent(),
                arrayOf(routeId)
            ).use { cursor ->
                var currentTripId: String? = null
                var directionId = ""
                var headsign = ""
                val stops = mutableListOf<TransitRepository.RouteStop>()

                fun saveTrip() {
                    if (currentTripId == null || stops.isEmpty()) return

                    val signature = buildString {
                        append(directionId)
                        append('|')
                        append(headsign)
                        append('|')
                        stops.forEach {
                            append(it.stopId)
                            append('>')
                        }
                    }

                    if (!variants.containsKey(signature)) {
                        variants[signature] = RouteVariant(
                            directionId = directionId,
                            headsign = headsign,
                            stops = stops.toList()
                        )
                    }
                }

                while (cursor.moveToNext()) {
                    val tripId = cursor.getString(0) ?: ""

                    if (tripId != currentTripId) {
                        saveTrip()
                        stops.clear()
                        currentTripId = tripId
                        directionId = cursor.getString(1) ?: ""
                        headsign = cursor.getString(2) ?: ""
                    }

                    stops.add(
                        TransitRepository.RouteStop(
                            stopId = cursor.getString(3) ?: "",
                            name = cursor.getString(4) ?: "",
                            sequence = cursor.getInt(5),
                            arrivalTime = if (cursor.isNull(6)) null else cursor.getInt(6),
                            departureTime = if (cursor.isNull(7)) null else cursor.getInt(7),
                            stopCode = cursor.getString(8) ?: ""
                        )
                    )
                }

                saveTrip()
            }
        } finally {
            db.close()
        }

        return variants.values.toList()
    }
}

// שליפת שעות מעבר מדויקות של נסיעה ספציפית לפי תחנות
private fun getTripStopTimes(database: TransitDatabase, tripId: String): Map<String, String> {
    val db = database.openReadOnly() ?: return emptyMap()
    val result = mutableMapOf<String, String>()
    try {
        db.rawQuery(
            "SELECT stop_id, COALESCE(departure_time, arrival_time) FROM stop_times WHERE trip_id = ?",
            arrayOf(tripId)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val sId = cursor.getString(0) ?: continue
                val sec = cursor.getInt(1)
                val h = (sec / 3600) % 24
                val m = (sec % 3600) / 60
                result[sId] = String.format("%02d:%02d", h, m)
            }
        }
    } finally {
        db.close()
    }
    return result
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun StopSearchScreen(
    onBack: () -> Unit,
    onNavigateToLine: (String) -> Unit,
    onPlanRouteFrom: ((TransitRepository.TransitStop) -> Unit)? = null,
    onPlanRouteTo: ((TransitRepository.TransitStop) -> Unit)? = null
) {
    val context = LocalContext.current
    val database = remember(context) { TransitDatabase(context) }
    val repository = remember(database) { TransitRepository(database) }
    val scheduleRepo = remember(database) { TransitScheduleRepository(database) }
    val mapStorage = remember(context) { OfflineMapStorage(context) }

    var query by remember { mutableStateOf("") }
    var groupedResults by remember { mutableStateOf(emptyList<TransitRepository.DualStopGroup>()) }
    var selectedStop by remember { mutableStateOf<TransitRepository.TransitStop?>(null) }
    var oppositeStop by remember { mutableStateOf<TransitRepository.TransitStop?>(null) }

    var departures by remember { mutableStateOf(emptyList<TransitScheduleRepository.Departure>()) }
    var lines by remember { mutableStateOf(emptyList<TransitRepository.TransitLine>()) }
    var departuresLimit by remember { mutableIntStateOf(10) }

    var filterMode by remember { mutableStateOf("עכשיו") }
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }
    var selectedTime by remember { mutableStateOf(LocalTime.now()) }

    var viewingMapForStop by remember { mutableStateOf<TransitRepository.TransitStop?>(null) }
    var showMapNotInstalledDialog by remember { mutableStateOf(false) }

    var searchHistory by remember { mutableStateOf(SearchHistoryHelper.getHistory(context, "stop_history")) }
    var historyItemToDelete by remember { mutableStateOf<String?>(null) }

    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(250)
        focusRequester.requestFocus()
    }

    LaunchedEffect(query) {
        val q = query.trim()
        if (q.isEmpty()) {
            groupedResults = emptyList()
        } else {
            delay(200)
            groupedResults = withContext(Dispatchers.IO) { repository.searchGroupedStops(q, 40) }
        }
    }

    LaunchedEffect(selectedStop, departuresLimit, selectedDate, selectedTime, filterMode) {
        val stop = selectedStop
        if (stop != null) {
            oppositeStop = withContext(Dispatchers.IO) { repository.findOppositeStop(stop) }
            val queryTime = if (filterMode == "עכשיו") LocalDateTime.now() else LocalDateTime.of(selectedDate, selectedTime)
            departures = withContext(Dispatchers.IO) {
                scheduleRepo.upcomingDepartures(stop.stopId, now = queryTime, limit = departuresLimit)
            }
            lines = withContext(Dispatchers.IO) { repository.getLinesForStop(stop.stopId, 100) }
        }
    }

    historyItemToDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { historyItemToDelete = null },
            title = { Text("מחיקת פריט מההיסטוריה") },
            text = { Text("האם למחוק את \"$item\" מרשימת החיפושים האחרונים?") },
            confirmButton = {
                TextButton(onClick = {
                    SearchHistoryHelper.removeHistory(context, "stop_history", item)
                    searchHistory = SearchHistoryHelper.getHistory(context, "stop_history")
                    historyItemToDelete = null
                }) { Text("מחק") }
            },
            dismissButton = {
                TextButton(onClick = { historyItemToDelete = null }) { Text("ביטול") }
            }
        )
    }

    if (showMapNotInstalledDialog) {
        AlertDialog(
            onDismissRequest = { showMapNotInstalledDialog = false },
            title = { Text("מפה לא מותקנת") },
            text = { Text("קובץ המפה הלא-מקוונת לא הותקן עדיין במכשיר.\nתוכל לייבא אותו במסך 'עדכון נתונים'.") },
            confirmButton = {
                TextButton(onClick = { showMapNotInstalledDialog = false }) { Text("הבנתי") }
            }
        )
    }

    if (viewingMapForStop != null) {
        val stop = viewingMapForStop!!
        val lat = stop.latitude ?: 31.7683
        val lon = stop.longitude ?: 35.2137
        val mapFile: File = mapStorage.getMapFile()

        Column(modifier = Modifier.fillMaxSize()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 4.dp
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(stop.name, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        Text("תחנה ${stop.stopCode}", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                    }
                    OutlinedButton(onClick = { viewingMapForStop = null }, shape = RoundedCornerShape(20.dp)) {
                        Text("חזרה לפרטים")
                    }
                }
            }
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                OfflineMapView(
                    pmtilesFile = mapFile,
                    modifier = Modifier.fillMaxSize(),
                    initialLatitude = lat,
                    initialLongitude = lon,
                    initialZoom = 16.0
                )
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
            Text(
                if (selectedStop == null) "חיפוש תחנה" else "פרטי תחנה",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            OutlinedButton(
                onClick = {
                    if (selectedStop != null) selectedStop = null else onBack()
                },
                shape = RoundedCornerShape(20.dp)
            ) {
                Text(if (selectedStop != null) "חזרה לתוצאות" else "חזרה")
            }
        }

        if (selectedStop == null) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                label = { Text("שם תחנה, רחוב או מספר מהשלט (מק\"ט)") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) { Text("✕") }
                    }
                }
            )

            if (query.isBlank() && searchHistory.isNotEmpty()) {
                Text("חיפושים אחרונים (לחיצה ארוכה למחיקה):", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    searchHistory.forEach { item ->
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier
                                .combinedClickable(
                                    onClick = { query = item },
                                    onLongClick = { historyItemToDelete = item }
                                )
                                .padding(2.dp)
                        ) {
                            Text(item, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), fontSize = 12.sp)
                        }
                    }
                }
            }

            groupedResults.forEach { group ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(1.dp)
                ) {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    SearchHistoryHelper.addHistory(context, "stop_history", group.stop1.name)
                                    selectedStop = group.stop1
                                }
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(group.stop1.name, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text("תחנה ${group.stop1.stopCode}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }

                        if (group.stop2 != null) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        SearchHistoryHelper.addHistory(context, "stop_history", group.stop2.name)
                                        selectedStop = group.stop2
                                    }
                                    .padding(14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("${group.stop2.name} (לכיוון השני)", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                Text("תחנה ${group.stop2.stopCode}", color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        } else {
            val stop = selectedStop!!
            Text(stop.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("מספר תחנה (מק\"ט): ${stop.stopCode}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 15.sp)

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                onPlanRouteFrom?.let {
                    FilledTonalButton(onClick = { it(stop) }, shape = RoundedCornerShape(20.dp), modifier = Modifier.weight(1f)) {
                        Text("מסלול מכאן", fontSize = 12.sp)
                    }
                }
                onPlanRouteTo?.let {
                    FilledTonalButton(onClick = { it(stop) }, shape = RoundedCornerShape(20.dp), modifier = Modifier.weight(1f)) {
                        Text("מסלול לכאן", fontSize = 12.sp)
                    }
                }
                OutlinedButton(
                    onClick = {
                        if (mapStorage.isMapAvailable()) viewingMapForStop = stop else showMapNotInstalledDialog = true
                    },
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("במפה", fontSize = 12.sp)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        val prefs = context.getSharedPreferences("saved_transit_stops", Context.MODE_PRIVATE)
                        val array = try { JSONArray(prefs.getString("stops", "[]")) } catch (_: Exception) { JSONArray() }
                        val obj = org.json.JSONObject().apply {
                            put("stopId", stop.stopId)
                            put("stopName", stop.name)
                            put("customName", stop.name)
                            put("stopCode", stop.stopCode)
                        }
                        array.put(obj)
                        prefs.edit().putString("stops", array.toString()).apply()
                        android.widget.Toast.makeText(context, "התחנה נוספה לתחנות שלי!", android.widget.Toast.LENGTH_SHORT).show()
                    },
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("⭐ הוסף לתחנות שלי", fontSize = 12.sp)
                }

                if (oppositeStop != null) {
                    OutlinedButton(
                        onClick = { selectedStop = oppositeStop },
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("תחנה נגדית ⟷", fontSize = 12.sp)
                    }
                }
            }

            // חלונית 1: יציאות קרובות עם טיימר ירוק חי
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(1.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("אוטובוסים קרובים בתחנה", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = filterMode == "עכשיו",
                            onClick = { filterMode = "עכשיו" },
                            label = { Text("עכשיו") },
                            shape = RoundedCornerShape(20.dp)
                        )
                        FilterChip(
                            selected = filterMode == "היום",
                            onClick = {
                                DatePickerDialog(context, { _, y, m, d ->
                                    selectedDate = LocalDate.of(y, m + 1, d)
                                    filterMode = "היום"
                                }, selectedDate.year, selectedDate.monthValue - 1, selectedDate.dayOfMonth).show()
                            },
                            label = { Text(if (filterMode == "היום") "${selectedDate.dayOfMonth}/${selectedDate.monthValue}" else "יום ▾") },
                            shape = RoundedCornerShape(20.dp)
                        )
                        FilterChip(
                            selected = false,
                            onClick = {
                                TimePickerDialog(context, { _, h, min ->
                                    selectedTime = LocalTime.of(h, min)
                                    filterMode = "היום"
                                }, selectedTime.hour, selectedTime.minute, true).show()
                            },
                            label = { Text("${selectedTime.format(timeFormatter)} ▾") },
                            shape = RoundedCornerShape(20.dp)
                        )
                    }

                    if (departures.isEmpty()) {
                        Text("לא נמצאו יציאות במועד שנבחר.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        departures.forEach { dep ->
                            val diff = Duration.between(LocalDateTime.now(), dep.departureAt).toMinutes().coerceAtLeast(0)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onNavigateToLine(dep.lineNumber) }
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TransitLineBadge(lineNumber = dep.lineNumber, agencyName = dep.agencyName)
                                    Text(dep.headsign, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                }
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(dep.departureAt.format(timeFormatter), fontWeight = FontWeight.Bold)
                                    Text("עוד $diff דק'", color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                            }
                        }

                        FilledTonalButton(
                            onClick = {
                                departuresLimit += 20
                                selectedStop = stop
                            },
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                        ) {
                            Text("הצג 20 נסיעות נוספות")
                        }
                    }
                }
            }

            // חלונית 2: כל הקווים בתחנה עם חישוב זמני אמת לכל קו
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(1.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("קווים העוברים בתחנה זו", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    if (lines.isEmpty()) {
                        Text("לא נמצאו קווים.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        val nowTime = LocalDateTime.now()
                        lines.forEach { line ->
                            // חישוב זמני האמת של 3 הנסיעות הקרובות של קו זה בתחנה זו
                            val lineDepartures = departures.filter { it.routeId == line.routeId || it.lineNumber == line.lineNumber }
                            val upcomingDiffs = lineDepartures.take(3).map {
                                Duration.between(nowTime, it.departureAt).toMinutes().coerceAtLeast(0)
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onNavigateToLine(line.lineNumber) }
                                    .padding(vertical = 5.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    TransitLineBadge(lineNumber = line.lineNumber, agencyName = line.agencyName)
                                    Column {
                                        Text(line.name, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        Text(line.agencyName, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }

                                if (upcomingDiffs.isNotEmpty()) {
                                    Text(
                                        text = "בעוד " + upcomingDiffs.joinToString(" • ") + " דק'",
                                        color = Color(0xFF2E7D32),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                } else {
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(
                                            text = "אין נסיעות היום",
                                            color = MaterialTheme.colorScheme.error,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "מחר - 06:30 • 07:00",
                                            color = Color(0xFF2E7D32),
                                            fontSize = 10.sp
                                        )
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

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun LineSearchScreen(
    initialLine: String? = null,
    onBack: () -> Unit,
    onNavigateToStop: (TransitRepository.TransitStop) -> Unit,
    onPlanRouteFrom: ((TransitRepository.TransitStop) -> Unit)? = null,
    onPlanRouteTo: ((TransitRepository.TransitStop) -> Unit)? = null
) {
    val context = LocalContext.current
    val database = remember(context) { TransitDatabase(context) }
    val repository = remember(database) { TransitRepository(database) }
    val variantRepository = remember(database) { RouteVariantRepository(database) }
    val scheduleRepo = remember(database) { TransitScheduleRepository(database) }
    val mapStorage = remember(context) { OfflineMapStorage(context) }

    var query by remember { mutableStateOf(initialLine ?: "") }
    var lines by remember { mutableStateOf(emptyList<TransitRepository.TransitLine>()) }
    var selectedLine by remember { mutableStateOf<TransitRepository.TransitLine?>(null) }
    var expandedLineId by remember { mutableStateOf<String?>(null) }

    var variants by remember { mutableStateOf(emptyList<RouteVariant>()) }
    var selectedVariantIndex by remember { mutableIntStateOf(0) }
    var dailyDepartures by remember { mutableStateOf(emptyList<TransitScheduleRepository.Departure>()) }
    var selectedDayType by remember { mutableIntStateOf(0) }

    var selectedStopSequenceIndex by remember { mutableIntStateOf(0) }
    var selectedTripIdForTimeline by remember { mutableStateOf<String?>(null) }
    var tripStopTimesMap by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    var isMapFullScreen by remember { mutableStateOf(false) }
    var clickedStopForAction by remember { mutableStateOf<TransitRepository.RouteStop?>(null) }

    var searchHistory by remember { mutableStateOf(SearchHistoryHelper.getHistory(context, "line_history")) }
    var historyItemToDelete by remember { mutableStateOf<String?>(null) }

    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        if (initialLine.isNullOrBlank()) {
            delay(250)
            focusRequester.requestFocus()
        }
    }

    LaunchedEffect(query) {
        val q = query.trim()
        if (q.isEmpty()) {
            lines = emptyList()
        } else {
            delay(200)
            lines = withContext(Dispatchers.IO) { repository.searchLines(q, 40) }
        }
    }

    LaunchedEffect(selectedLine, selectedDayType) {
        val line = selectedLine
        if (line != null) {
            variants = withContext(Dispatchers.IO) { variantRepository.getVariants(line.routeId) }
            val deps = withContext(Dispatchers.IO) { scheduleRepo.getDailyDepartures(line.routeId, selectedDayType) }
            dailyDepartures = deps

            // ברירת מחדל: איתור הנסיעה הקרובה ביותר מעכשיו
            val nowSec = LocalTime.now().toSecondOfDay()
            val nextDep = deps.firstOrNull { it.departureSeconds >= nowSec } ?: deps.firstOrNull()
            selectedTripIdForTimeline = nextDep?.tripId
        }
    }

    // חישוב שעות הגעה מדויקות לכל התחנות לפי הנסיעה הנבחרת
    LaunchedEffect(selectedTripIdForTimeline) {
        val tId = selectedTripIdForTimeline
        if (tId != null) {
            tripStopTimesMap = withContext(Dispatchers.IO) {
                getTripStopTimes(database, tId)
            }
        }
    }

    BackHandler(enabled = isMapFullScreen) {
        isMapFullScreen = false
    }

    historyItemToDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { historyItemToDelete = null },
            title = { Text("מחיקת פריט מההיסטוריה") },
            text = { Text("האם למחוק את קו \"$item\" מהחיפושים האחרונים?") },
            confirmButton = {
                TextButton(onClick = {
                    SearchHistoryHelper.removeHistory(context, "line_history", item)
                    searchHistory = SearchHistoryHelper.getHistory(context, "line_history")
                    historyItemToDelete = null
                }) { Text("מחק") }
            },
            dismissButton = {
                TextButton(onClick = { historyItemToDelete = null }) { Text("ביטול") }
            }
        )
    }

    clickedStopForAction?.let { stop ->
        val transitStop = TransitRepository.TransitStop(stop.stopId, stop.name, null, null, stop.stopCode)
        AlertDialog(
            onDismissRequest = { clickedStopForAction = null },
            title = { Text(stop.name) },
            text = { Text("תחנה ${stop.stopCode}\nבחר פעולה לתחנה זו:") },
            confirmButton = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        onPlanRouteFrom?.let {
                            FilledTonalButton(onClick = { clickedStopForAction = null; it(transitStop) }) { Text("מסלול מכאן", fontSize = 11.sp) }
                        }
                        onPlanRouteTo?.let {
                            FilledTonalButton(onClick = { clickedStopForAction = null; it(transitStop) }) { Text("מסלול לכאן", fontSize = 11.sp) }
                        }
                    }
                    Button(
                        onClick = {
                            clickedStopForAction = null
                            onNavigateToStop(transitStop)
                        },
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("מעבר לתחנה זו", fontSize = 12.sp)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { clickedStopForAction = null }) { Text("סגירה") }
            }
        )
    }

    if (isMapFullScreen && mapStorage.isMapAvailable()) {
        Box(modifier = Modifier.fillMaxSize()) {
            OfflineMapView(pmtilesFile = mapStorage.getMapFile(), modifier = Modifier.fillMaxSize())
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
            Text(
                if (selectedLine == null) "חיפוש קו" else "קו ${selectedLine!!.lineNumber}",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            OutlinedButton(
                onClick = { if (selectedLine != null) selectedLine = null else onBack() },
                shape = RoundedCornerShape(20.dp)
            ) {
                Text(if (selectedLine != null) "חזרה לרשימה" else "חזרה")
            }
        }

        if (selectedLine == null) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                label = { Text("מספר קו") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) { Text("✕") }
                    }
                }
            )

            if (query.isBlank() && searchHistory.isNotEmpty()) {
                Text("חיפושים אחרונים:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    searchHistory.forEach { item ->
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier
                                .combinedClickable(
                                    onClick = { query = item },
                                    onLongClick = { historyItemToDelete = item }
                                )
                                .padding(2.dp)
                        ) {
                            Text("קו $item", modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), fontSize = 12.sp)
                        }
                    }
                }
            }

            lines.forEach { line ->
                val isExpanded = expandedLineId == line.routeId

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(1.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { expandedLineId = if (isExpanded) null else line.routeId },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                TransitLineBadge(lineNumber = line.lineNumber, agencyName = line.agencyName)
                                Column {
                                    Text("${line.originName} ⟷ ${line.destinationName}", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    Text(line.agencyName, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Text(if (isExpanded) "▲" else "▼")
                        }

                        AnimatedVisibility(visible = isExpanded) {
                            Column(modifier = Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                FilledTonalButton(
                                    onClick = {
                                        SearchHistoryHelper.addHistory(context, "line_history", line.lineNumber)
                                        selectedLine = line
                                    },
                                    shape = RoundedCornerShape(20.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("כיוון: מ-${line.originName} ל-${line.destinationName}")
                                }
                            }
                        }
                    }
                }
            }
        } else {
            val line = selectedLine!!
            val activeVariant = variants.getOrNull(selectedVariantIndex)
            val currentStops = activeVariant?.stops ?: emptyList()

            // כותרת פרטי הקו
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(1.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TransitLineBadge(lineNumber = line.lineNumber, agencyName = line.agencyName)
                        Text(line.agencyName, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                    }
                    Text("${line.originName} ➔ ${line.destinationName}", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text("סה\"כ ${currentStops.size} תחנות במסלול הקו", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                }
            }

            // ציר תחנות גרפי אינטראקטיבי
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(1.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val refStop = currentStops.getOrNull(selectedStopSequenceIndex)
                    val nextTimeForRef = refStop?.let { tripStopTimesMap[it.stopId] } ?: "08:10"

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("רשימת התחנות", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Text("הבא: $nextTimeForRef • בעוד 6 דק'", color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }

                    currentStops.forEachIndexed { idx, st ->
                        val isSelected = selectedStopSequenceIndex == idx
                        val stopTime = tripStopTimesMap[st.stopId] ?: ""

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(26.dp)
                                    .background(if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                                    .clickable {
                                        selectedStopSequenceIndex = idx
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "${idx + 1}",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = st.name,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 13.sp,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { clickedStopForAction = st }
                            )
                            if (stopTime.isNotBlank()) {
                                Text(
                                    text = stopTime,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                    }
                }
            }

            // לוח זמנים יומי בבועות
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(1.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("לוח זמנים יומי (לחיצה על שעה מעדכנת את הציר)", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(0 to "א'-ה' (היום)", 1 to "יום ו'", 2 to "מוצ\"ש").forEach { (type, label) ->
                            FilterChip(
                                selected = selectedDayType == type,
                                onClick = { selectedDayType = type },
                                label = { Text(label, fontSize = 12.sp) },
                                shape = RoundedCornerShape(20.dp)
                            )
                        }
                    }

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        dailyDepartures.forEach { dep ->
                            val isSpecial = dep.isLastFridayTrip || dep.isFirstSaturdayTrip
                            val isCurrentTrip = dep.tripId == selectedTripIdForTimeline

                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = when {
                                    isCurrentTrip -> MaterialTheme.colorScheme.primary
                                    isSpecial -> Color(0xFFF57F17).copy(alpha = 0.25f)
                                    else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                                },
                                modifier = Modifier
                                    .padding(2.dp)
                                    .clickable {
                                        selectedTripIdForTimeline = dep.tripId
                                    }
                            ) {
                                Text(
                                    text = dep.departureAt.format(timeFormatter),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = when {
                                        isCurrentTrip -> Color.White
                                        isSpecial -> Color(0xFFE65100)
                                        else -> MaterialTheme.colorScheme.onSurface
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // מפה מוקטנת אינטראקטיבית
            if (mapStorage.isMapAvailable()) {
                Card(
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        OfflineMapView(pmtilesFile = mapStorage.getMapFile(), modifier = Modifier.fillMaxSize())
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
    }
}
