package il.co.israeltransit.offline

import android.content.Context
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.sp
import il.co.israeltransit.offline.database.TransitDatabase
import il.co.israeltransit.offline.database.TransitRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private data class SavedStop(
    val stopId: String,
    val stopName: String,
    val customName: String
)

private class SavedStopsStorage(context: Context) {
    private val preferences = context.getSharedPreferences(
        "saved_transit_stops",
        Context.MODE_PRIVATE
    )

    fun load(): List<SavedStop> {
        val json = preferences.getString("stops", "[]") ?: "[]"

        return try {
            val array = JSONArray(json)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        SavedStop(
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

    fun save(stops: List<SavedStop>) {
        val array = JSONArray()

        stops.forEach { stop ->
            array.put(
                JSONObject().apply {
                    put("stopId", stop.stopId)
                    put("stopName", stop.stopName)
                    put("customName", stop.customName)
                }
            )
        }

        preferences.edit()
            .putString("stops", array.toString())
            .apply()
    }
}

@Composable
fun SavedStopsScreen(
    onBack: () -> Unit,
    onPlanRouteFrom: ((TransitRepository.TransitStop) -> Unit)? = null,
    onPlanRouteTo: ((TransitRepository.TransitStop) -> Unit)? = null
) {
    val context = LocalContext.current
    val database = remember(context) { TransitDatabase(context) }
    val repository = remember(database) { TransitRepository(database) }
    val storage = remember(context) { SavedStopsStorage(context) }

    var savedStops by remember { mutableStateOf(storage.load()) }
    var query by remember { mutableStateOf("") }
    var searchResults by remember {
        mutableStateOf(emptyList<TransitRepository.TransitStop>())
    }
    var searching by remember { mutableStateOf(false) }

    var dialogStop by remember { mutableStateOf<TransitRepository.TransitStop?>(null) }
    var editingStop by remember { mutableStateOf<SavedStop?>(null) }
    var customName by remember { mutableStateOf("") }

    fun persist(stops: List<SavedStop>) {
        savedStops = stops
        storage.save(stops)
    }

    LaunchedEffect(query) {
        val value = query.trim()

        if (value.isEmpty()) {
            searchResults = emptyList()
            searching = false
        } else {
            delay(250)
            searching = true
            searchResults = withContext(Dispatchers.IO) {
                repository.searchStops(value, 50)
            }
            searching = false
        }
    }

    if (dialogStop != null || editingStop != null) {
        val isEditing = editingStop != null
        val stopName = editingStop?.stopName ?: dialogStop?.name.orEmpty()
        val stopId = editingStop?.stopId ?: dialogStop?.stopId.orEmpty()

        AlertDialog(
            onDismissRequest = {
                dialogStop = null
                editingStop = null
            },
            title = {
                Text(if (isEditing) "שינוי שם התחנה" else "שמירת תחנה")
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stopName)
                    Text(
                        "מספר תחנה: $stopId",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = customName,
                        onValueChange = { customName = it },
                        label = { Text("שם אישי, למשל בית או עבודה") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = customName.trim()
                        if (name.isNotEmpty()) {
                            val updated = if (isEditing) {
                                savedStops.map {
                                    if (it.stopId == stopId) it.copy(customName = name) else it
                                }
                            } else {
                                savedStops.filterNot { it.stopId == stopId } + SavedStop(
                                    stopId = stopId,
                                    stopName = stopName,
                                    customName = name
                                )
                            }
                            persist(updated)
                        }
                        dialogStop = null
                        editingStop = null
                    },
                    enabled = customName.trim().isNotEmpty()
                ) {
                    Text("שמירה")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        dialogStop = null
                        editingStop = null
                    }
                ) {
                    Text("ביטול")
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
                "התחנות שלי",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            Button(onClick = onBack) {
                Text("חזרה")
            }
        }

        Text("שמור תחנות חשובות כדי למצוא אותן ולתכנן אליהן מסלולים במהירות.")

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("חיפוש תחנה לפי שם או מספר להוספה") },
            singleLine = true
        )

        if (!database.isDatabaseAvailable()) {
            Text("יש להתקין תחילה מאגר נתונים במסך עדכון נתונים.")
        } else if (searching) {
            CircularProgressIndicator()
        } else if (query.isNotBlank()) {
            if (searchResults.isEmpty()) {
                Text("לא נמצאו תחנות מתאימות.")
            }

            searchResults.forEach { stop ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(stop.name, fontWeight = FontWeight.Bold)
                        Text("מספר תחנה: ${stop.stopId}", style = MaterialTheme.typography.bodySmall)

                        val alreadySaved = savedStops.any { it.stopId == stop.stopId }

                        Button(
                            onClick = {
                                customName = stop.name
                                dialogStop = stop
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (alreadySaved) "עדכון השם האישי" else "הוספה לתחנות שלי")
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            "התחנות השמורות",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        if (savedStops.isEmpty()) {
            Text("עדיין לא שמרת תחנות. חפש תחנה למעלה והוסף אותה לרשימה.")
        }

        savedStops.forEach { stop ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        stop.customName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Text(stop.stopName)
                    Text("מספר תחנה: ${stop.stopId}", style = MaterialTheme.typography.bodySmall)

                    // כפתורי ניתוב מהירים
                    val transitStop = TransitRepository.TransitStop(
                        stopId = stop.stopId,
                        name = stop.stopName,
                        latitude = null,
                        longitude = null
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (onPlanRouteFrom != null) {
                            Button(
                                onClick = { onPlanRouteFrom(transitStop) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("מסלול מכאן", fontSize = 13.sp)
                            }
                        }

                        if (onPlanRouteTo != null) {
                            Button(
                                onClick = { onPlanRouteTo(transitStop) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("מסלול לכאן", fontSize = 13.sp)
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                editingStop = stop
                                customName = stop.customName
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("שינוי שם")
                        }

                        OutlinedButton(
                            onClick = {
                                persist(savedStops.filterNot { it.stopId == stop.stopId })
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("הסרה")
                        }
                    }
                }
            }
        }
    }
}
