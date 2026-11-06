
package il.co.israeltransit.offline

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import il.co.israeltransit.offline.database.TransitDatabase
import il.co.israeltransit.offline.database.TransitRepository
import il.co.israeltransit.offline.routing.TransitRouteEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private data class PlannerSavedStop(
    val stopId: String,
    val stopName: String,
    val customName: String
)

private fun loadPlannerSavedStops(
    context: android.content.Context
): List<PlannerSavedStop> {
    return try {
        val json = context.getSharedPreferences(
            "saved_transit_stops",
            android.content.Context.MODE_PRIVATE
        ).getString("stops", "[]") ?: "[]"

        val array = JSONArray(json)

        buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)

                add(
                    PlannerSavedStop(
                        stopId = item.optString("stopId"),
                        stopName = item.optString("stopName"),
                        customName = item.optString("customName")
                    )
                )
            }
        }
    } catch (_: Exception) {
        emptyList()
    }
}

private val plannerDateFormatter =
    DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")

private val plannerTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm")

@Composable
fun RoutePlannerScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current

    val database = remember(context) {
        TransitDatabase(context)
    }

    val repository = remember(database) {
        TransitRepository(database)
    }

    val routeEngine = remember(database) {
        TransitRouteEngine(database)
    }

    val scope = rememberCoroutineScope()

    var originQuery by remember { mutableStateOf("") }
    var destinationQuery by remember { mutableStateOf("") }

    var originResults by remember {
        mutableStateOf(emptyList<TransitRepository.TransitStop>())
    }

    var destinationResults by remember {
        mutableStateOf(emptyList<TransitRepository.TransitStop>())
    }

    var selectedOrigin by remember {
        mutableStateOf<TransitRepository.TransitStop?>(null)
    }

    var selectedDestination by remember {
        mutableStateOf<TransitRepository.TransitStop?>(null)
    }

    var activeField by remember {
        mutableStateOf("origin")
    }

    var searching by remember {
        mutableStateOf(false)
    }

    var calculating by remember {
        mutableStateOf(false)
    }

    var routeResult by remember {
        mutableStateOf<TransitRouteEngine.SearchResult?>(null)
    }

    val savedStops = remember(context) {
        loadPlannerSavedStops(context)
    }

    val now = remember {
        LocalDateTime.now()
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

        if (origin.stopId == destination.stopId) return

        calculating = true
        routeResult = null

        scope.launch {
            val result = withContext(Dispatchers.IO) {
                routeEngine.findJourneys(
                    originStopId = origin.stopId,
                    destinationStopId = destination.stopId,
                    departureTime = LocalDateTime.now(),
                    maxTransfers = 3,
                    maxResults = 5
                )
            }

            routeResult = result
            calculating = false
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
                "תכנון מסלול",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            OutlinedButton(onClick = onBack) {
                Text("חזרה")
            }
        }

        Text(
            "בחר את נקודת המוצא ואת היעד. אפשר לחפש לפי שם תחנה או מספר תחנה.",
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

            Text(
                "מוצא",
                fontWeight = FontWeight.Bold
            )

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
                    .clickable {
                        activeField = "origin"
                    },
                label = { Text("תחנת מוצא") },
                singleLine = true
            )

            if (activeField == "origin") {
                originResults.forEach { stop ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                chooseStop(stop)
                            }
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp)
                        ) {
                            Text(
                                stop.name,
                                fontWeight = FontWeight.Bold
                            )

                            Text(
                                "תחנה ${stop.stopId}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            Text(
                "יעד",
                fontWeight = FontWeight.Bold
            )

            OutlinedTextField(
                value = destinationQuery,
                onValueChange = {
                    destinationQuery = it
                    selectedDestination = null
                    activeField = "destination"
                    routeResult = null
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("תחנת יעד") },
                singleLine = true
            )

            if (activeField == "destination") {
                destinationResults.forEach { stop ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                chooseStop(stop)
                            }
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp)
                        ) {
                            Text(
                                stop.name,
                                fontWeight = FontWeight.Bold
                            )

                            Text(
                                "תחנה ${stop.stopId}",
                                style = MaterialTheme.typography.bodySmall
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
                Text(
                    "התחנות שלי",
                    fontWeight = FontWeight.Bold
                )

                savedStops.forEach { saved ->
                    OutlinedButton(
                        onClick = {
                            val stop = TransitRepository.TransitStop(
                                stopId = saved.stopId,
                                name = saved.stopName,
                                latitude = null,
                                longitude = null
                            )

                            chooseStop(stop)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "${saved.customName} — ${saved.stopName}"
                        )
                    }
                }

                Text(
                    "לחיצה על תחנה שמורה תציב אותה בשדה הפעיל.",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                "מועד יציאה",
                fontWeight = FontWeight.Bold
            )

            Text(
                "עכשיו — ${now.format(plannerDateFormatter)}"
            )

            Button(
                onClick = {
                    searchRoute()
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = selectedOrigin != null &&
                    selectedDestination != null &&
                    selectedOrigin?.stopId != selectedDestination?.stopId &&
                    !calculating
            ) {
                Text(
                    if (calculating) "מחשב מסלול..."
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

                Text(
                    "מחפש נסיעות מתאימות בלוח הזמנים..."
                )
            }

            if (selectedOrigin != null &&
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
                            "לא נמצאו מסלולים מתאימים.",
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
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {

                                Text(
                                    "אפשרות ${index + 1}",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleMedium
                                )

                                Text(
                                    "${journey.departureAt.format(plannerTimeFormatter)} – " +
                                        journey.arrivalAt.format(plannerTimeFormatter),
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold
                                )

                                Text(
                                    "משך הנסיעה: ${journey.durationMinutes} דקות"
                                )

                                Text(
                                    "מספר החלפות: ${journey.transfers}"
                                )

                                journey.legs.forEachIndexed { legIndex, leg ->

                                    Spacer(
                                        modifier = Modifier.height(4.dp)
                                    )

                                    Text(
                                        "נסיעה ${legIndex + 1}",
                                        fontWeight = FontWeight.Bold
                                    )

                                    Text(
                                        "קו ${leg.lineNumber} — ${leg.agencyName}"
                                    )

                                    if (leg.lineName.isNotBlank()) {
                                        Text(leg.lineName)
                                    }

                                    Text(
                                        "יציאה: ${leg.departureAt.format(plannerTimeFormatter)}"
                                    )

                                    Text(
                                        "מ־${leg.fromStopName} (${leg.fromStopId})"
                                    )

                                    Text(
                                        "הגעה: ${leg.arrivalAt.format(plannerTimeFormatter)}"
                                    )

                                    Text(
                                        "עד ${leg.toStopName} (${leg.toStopId})"
                                    )

                                    if (legIndex < journey.legs.lastIndex) {
                                        Text(
                                            "החלפה בתחנה זו",
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.Bold
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
