
package il.co.israeltransit.offline

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import il.co.israeltransit.offline.database.TransitDatabase
import il.co.israeltransit.offline.database.TransitRepository
import il.co.israeltransit.offline.schedule.TransitScheduleRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.format.DateTimeFormatter

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
                    st.departure_time
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
                            arrivalTime = if (cursor.isNull(6)) {
                                null
                            } else {
                                cursor.getInt(6)
                            },
                            departureTime = if (cursor.isNull(7)) {
                                null
                            } else {
                                cursor.getInt(7)
                            }
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

@Composable
fun LineSearchScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val database = remember(context) { TransitDatabase(context) }
    val repository = remember(database) { TransitRepository(database) }
    val variantRepository = remember(database) {
        RouteVariantRepository(database)
    }

    var query by remember { mutableStateOf("") }
    var results by remember {
        mutableStateOf(emptyList<TransitRepository.TransitLine>())
    }
    var selectedLine by remember {
        mutableStateOf<TransitRepository.TransitLine?>(null)
    }
    var variants by remember { mutableStateOf(emptyList<RouteVariant>()) }
    var loading by remember { mutableStateOf(false) }
    var detailLoading by remember { mutableStateOf(false) }

    LaunchedEffect(query) {
        val value = query.trim()
        if (value.isEmpty()) {
            results = emptyList()
        } else {
            delay(250)
            loading = true
            results = withContext(Dispatchers.IO) {
                repository.searchLines(value, 50)
            }
            loading = false
        }
    }

    LaunchedEffect(selectedLine?.routeId) {
        val routeId = selectedLine?.routeId
        if (routeId != null) {
            detailLoading = true
            variants = withContext(Dispatchers.IO) {
                variantRepository.getVariants(routeId)
            }
            detailLoading = false
        } else {
            variants = emptyList()
        }
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
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = if (selectedLine == null) "חיפוש קו" else "מסלולי הקו",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Button(
                onClick = {
                    if (selectedLine != null) {
                        selectedLine = null
                        variants = emptyList()
                    } else {
                        onBack()
                    }
                }
            ) {
                Text(if (selectedLine != null) "חזרה לתוצאות" else "חזרה")
            }
        }

        if (selectedLine == null) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("מספר קו או שם קו") },
                singleLine = true
            )

            if (!database.isDatabaseAvailable()) {
                Text("כדי לחפש קווים, יש להתקין תחילה קובץ נתונים במסך עדכון נתונים.")
            } else if (loading) {
                CircularProgressIndicator()
            } else if (query.isNotBlank() && results.isEmpty()) {
                Text("לא נמצאו קווים מתאימים.")
            }

            results.forEach { line ->
                Card(
                    onClick = { selectedLine = line },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Text(
                            text = "קו ${line.number}",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(line.name)
                        Text(
                            text = "מפעיל: ${line.agencyName}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        } else {
            Text(
                text = "קו ${selectedLine!!.number}",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text("מפעיל: ${selectedLine!!.agencyName}")
            Text(selectedLine!!.name)

            if (detailLoading) {
                CircularProgressIndicator()
            } else if (variants.isEmpty()) {
                Text("לא נמצאו מסלולי נסיעה לקו זה.")
            } else {
                Text(
                    text = "נמצאו ${variants.size} מסלולי נסיעה שונים",
                    fontWeight = FontWeight.Bold
                )

                variants.forEachIndexed { index, variant ->
                    Card(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "מסלול ${index + 1}",
                                fontWeight = FontWeight.Bold
                            )
                            if (variant.headsign.isNotBlank()) {
                                Text("יעד: ${variant.headsign}")
                            }
                            if (variant.directionId.isNotBlank()) {
                                Text("כיוון: ${variant.directionId}")
                            }
                            Text(
                                "מספר תחנות: ${variant.stops.size}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            variant.stops.forEach { stop ->
                                Text(
                                    "${stop.sequence}. ${stop.name} (${stop.stopId})",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StopSearchScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val database = remember(context) { TransitDatabase(context) }
    val repository = remember(database) { TransitRepository(database) }
    val scheduleRepository = remember(database) {
        TransitScheduleRepository(database)
    }

    var query by remember { mutableStateOf("") }
    var results by remember {
        mutableStateOf(emptyList<TransitRepository.TransitStop>())
    }
    var selectedStop by remember {
        mutableStateOf<TransitRepository.TransitStop?>(null)
    }
    var lines by remember {
        mutableStateOf(emptyList<TransitRepository.TransitLine>())
    }
    var departures by remember {
        mutableStateOf(emptyList<TransitScheduleRepository.Departure>())
    }
    var loading by remember { mutableStateOf(false) }
    var detailLoading by remember { mutableStateOf(false) }

    LaunchedEffect(query) {
        val value = query.trim()
        if (value.isEmpty()) {
            results = emptyList()
        } else {
            delay(250)
            loading = true
            results = withContext(Dispatchers.IO) {
                repository.searchStops(value, 50)
            }
            loading = false
        }
    }

    LaunchedEffect(selectedStop?.stopId) {
        val stopId = selectedStop?.stopId
        if (stopId != null) {
            detailLoading = true
            val details = withContext(Dispatchers.IO) {
                Pair(
                    repository.getLinesForStop(stopId, 100),
                    scheduleRepository.upcomingDepartures(stopId, limit = 15)
                )
            }
            lines = details.first
            departures = details.second
            detailLoading = false
        } else {
            lines = emptyList()
            departures = emptyList()
        }
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
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = if (selectedStop == null) "חיפוש תחנה" else "פרטי תחנה",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Button(
                onClick = {
                    if (selectedStop != null) {
                        selectedStop = null
                    } else {
                        onBack()
                    }
                }
            ) {
                Text(if (selectedStop != null) "חזרה לתוצאות" else "חזרה")
            }
        }

        if (selectedStop == null) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("שם תחנה או מספר תחנה") },
                singleLine = true
            )

            if (!database.isDatabaseAvailable()) {
                Text("כדי לחפש תחנות, יש להתקין תחילה קובץ נתונים במסך עדכון נתונים.")
            } else if (loading) {
                CircularProgressIndicator()
            } else if (query.isNotBlank() && results.isEmpty()) {
                Text("לא נמצאו תחנות מתאימות.")
            }

            results.forEach { stop ->
                Card(
                    onClick = { selectedStop = stop },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            stop.name,
                            fontWeight = FontWeight.Bold
                        )
                        Text("מספר תחנה: ${stop.stopId}")
                    }
                }
            }
        } else {
            Text(
                selectedStop!!.name,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text("מספר תחנה: ${selectedStop!!.stopId}")

            if (detailLoading) {
                CircularProgressIndicator()
            } else {
                Text(
                    "קווים העוברים בתחנה",
                    fontWeight = FontWeight.Bold
                )

                if (lines.isEmpty()) {
                    Text("לא נמצאו קווים בתחנה זו.")
                } else {
                    lines.forEach { line ->
                        Card(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Text(
                                    "קו ${line.number}",
                                    fontWeight = FontWeight.Bold
                                )
                                Text(line.name)
                                Text(
                                    "מפעיל: ${line.agencyName}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "יציאות קרובות",
                    fontWeight = FontWeight.Bold
                )

                if (departures.isEmpty()) {
                    Text("לא נמצאו יציאות קרובות לפי לוח הזמנים המקומי.")
                } else {
                    val formatter = DateTimeFormatter.ofPattern("HH:mm")
                    departures.forEach { departure ->
                        Text(
                            "${departure.departureAt.format(formatter)}  " +
                                "קו ${departure.lineNumber}  " +
                                "${departure.headsign}  " +
                                "(${departure.agencyName})"
                        )
                    }
                }
            }
        }
    }
}
