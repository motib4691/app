
package il.co.israeltransit.offline

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import il.co.israeltransit.offline.database.TransitDatabase
import il.co.israeltransit.offline.database.TransitStorage
import il.co.israeltransit.offline.database.TransitStorageMigration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun UpdateDataScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current

    val database = remember(context) {
        TransitDatabase(context)
    }

    val storage = remember(context) {
        TransitStorage(context)
    }

    val migration = remember(context) {
        TransitStorageMigration(context)
    }

    val scope = rememberCoroutineScope()

    var busy by remember { mutableStateOf(false) }
    var statusMessage by remember {
        mutableStateOf("בחר קובץ נתונים כדי להתקין או לעדכן את המאגר.")
    }
    var operationSucceeded by remember { mutableStateOf(false) }

    var refreshVersion by remember { mutableStateOf(0) }

    val locations = remember(refreshVersion) {
        storage.availableLocations()
    }

    val selectedLocation = remember(refreshVersion) {
        storage.getSelectedLocation()
    }

    val selectedLocationId = remember(refreshVersion) {
        storage.getConfiguredLocationId()
            ?: storage.availableLocations()
                .firstOrNull { !it.removable }?.id
    }

    val unavailableLocation =
        storage.hasUnavailableSelectedLocation()

    val databaseAvailable = remember(refreshVersion) {
        database.isDatabaseAvailable()
    }

    val lastUpdate = remember(refreshVersion) {
        database.getLastUpdateDate()
    }

    val dateFormatter = remember {
        SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null && !busy) {
            scope.launch {
                busy = true
                operationSucceeded = false
                statusMessage =
                    "הקובץ נבחר. מתבצע ייבוא ובדיקת תקינות. התהליך עשוי להימשך מספר דקות."

                val result = withContext(Dispatchers.IO) {
                    database.importDatabase(uri)
                }

                busy = false
                operationSucceeded = result.success
                statusMessage = result.message
                refreshVersion++
            }
        }
    }

    fun selectStorage(location: TransitStorage.StorageLocation) {
        if (busy) return

        if (selectedLocationId == location.id) {
            return
        }

        scope.launch {
            busy = true
            operationSucceeded = false

            statusMessage = if (databaseAvailable) {
                "מעביר את המאגר למיקום החדש. אין לסגור את האפליקציה."
            } else {
                "מעדכן את מיקום האחסון."
            }

            val result = withContext(Dispatchers.IO) {
                if (unavailableLocation) {
                    false to "מיקום האחסון הקודם אינו זמין. לא בוצעה העברה."
                } else if (database.isDatabaseAvailable()) {
                    val migrationResult = migration.moveTo(location.id)
                    migrationResult.success to migrationResult.message
                } else {
                    val selected = storage.selectLocation(location.id)
                    selected to if (selected) {
                        "מיקום האחסון עודכן בהצלחה. ניתן לייבא אליו מאגר נתונים."
                    } else {
                        "לא ניתן לבחור את מיקום האחסון."
                    }
                }
            }

            busy = false
            operationSucceeded = result.first
            statusMessage = result.second
            refreshVersion++
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "עדכון נתונים",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Text(
            text = "האפליקציה משתמשת בקובץ נתונים מקומי. " +
                "היא אינה מורידה נתונים מ-GitHub ואינה זקוקה " +
                "לחיבור לאינטרנט לצורך הייבוא או השימוש במאגר.",
            style = MaterialTheme.typography.bodyMedium
        )

        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "מצב המאגר במכשיר",
                    fontWeight = FontWeight.Bold
                )

                if (unavailableLocation) {
                    Text(
                        text = "כרטיס הזיכרון שנבחר אינו זמין. " +
                            "חבר אותו מחדש כדי להשתמש במאגר.",
                        color = MaterialTheme.colorScheme.error
                    )
                } else if (databaseAvailable) {
                    Text(
                        text = "מאגר נתונים מותקן",
                        color = MaterialTheme.colorScheme.primary
                    )

                    Text(
                        text = "עדכון אחרון: " +
                            dateFormatter.format(Date(lastUpdate))
                    )
                } else {
                    Text("עדיין לא הותקן מאגר נתונים.")
                }

                Text(
                    text = "מיקום פעיל: " +
                        (selectedLocation?.label ?: "לא זמין")
                )
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "מיקום אחסון הנתונים",
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "ברירת המחדל היא האחסון הפנימי. " +
                        "אפשר לבחור בכרטיס SD כדי להעביר אליו את המאגר " +
                        "ולפנות מקום באחסון הפנימי."
                )

                if (locations.isEmpty()) {
                    Text("לא נמצאו מיקומי אחסון זמינים.")
                }

                locations.forEach { location ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedLocationId == location.id,
                            onClick = {
                                selectStorage(location)
                            },
                            enabled = !busy && !unavailableLocation
                        )

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 8.dp)
                        ) {
                            Text(
                                text = location.label,
                                fontWeight = FontWeight.Medium
                            )

                            Text(
                                text = "מקום פנוי: " +
                                    formatStorageSize(location.availableBytes),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                if (busy) {
                    CircularProgressIndicator()
                    Text(
                        "הפעולה מתבצעת. אין לסגור את האפליקציה.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "התקנת מאגר נתונים",
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "בחר קובץ israel-transit.sqlite.gz " +
                        "או קובץ SQLite לא דחוס מהמכשיר."
                )

                Button(
                    onClick = {
                        filePicker.launch(arrayOf("*/*"))
                    },
                    enabled = !busy && !unavailableLocation,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("בחירת קובץ נתונים מהמכשיר")
                }
            }
        }

        if (!busy) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (operationSucceeded) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    }
                )
            ) {
                Text(
                    text = statusMessage,
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedButton(
            onClick = onBack,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("חזרה למסך הבית")
        }
    }
}

private fun formatStorageSize(bytes: Long): String {
    if (bytes < 0L) return "לא ידוע"

    val units = listOf("B", "KB", "MB", "GB", "TB")
    var size = bytes.toDouble()
    var unitIndex = 0

    while (size >= 1024.0 && unitIndex < units.lastIndex) {
        size /= 1024.0
        unitIndex++
    }

    return String.format(
        Locale.getDefault(),
        "%.1f %s",
        size,
        units[unitIndex]
    )
}
