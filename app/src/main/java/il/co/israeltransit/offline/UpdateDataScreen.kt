
package il.co.israeltransit.offline

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import il.co.israeltransit.offline.database.TransitStorage
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

    val scope = rememberCoroutineScope()

    var importing by remember {
        mutableStateOf(false)
    }

    var statusMessage by remember {
        mutableStateOf(
            "בחר קובץ נתונים כדי להתקין או לעדכן את המאגר."
        )
    }

    var importSucceeded by remember {
        mutableStateOf(false)
    }

    var lastUpdate by remember {
        mutableStateOf(database.getLastUpdateDate())
    }

    var databaseAvailable by remember {
        mutableStateOf(database.isDatabaseAvailable())
    }

    var storageLabel by remember {
        mutableStateOf(storage.getSelectedLocationLabel())
    }

    var availableSpace by remember {
        mutableStateOf(
            storage.getSelectedLocation()?.availableBytes ?: 0L
        )
    }

    val dateFormatter = remember {
        SimpleDateFormat(
            "dd/MM/yyyy HH:mm",
            Locale.getDefault()
        )
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null && !importing) {
            scope.launch {
                importing = true
                importSucceeded = false

                statusMessage =
                    "הקובץ נבחר. מתבצע ייבוא ובדיקת תקינות. " +
                    "התהליך עשוי להימשך מספר דקות."

                val result = withContext(Dispatchers.IO) {
                    database.importDatabase(uri)
                }

                databaseAvailable = withContext(Dispatchers.IO) {
                    database.isDatabaseAvailable()
                }

                lastUpdate = withContext(Dispatchers.IO) {
                    database.getLastUpdateDate()
                }

                storageLabel = storage.getSelectedLocationLabel()

                availableSpace =
                    storage.getSelectedLocation()?.availableBytes ?: 0L

                importing = false
                importSucceeded = result.success
                statusMessage = result.message
            }
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
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "מיקום אחסון",
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = storageLabel,
                    color = MaterialTheme.colorScheme.primary
                )

                Text(
                    text = "מקום פנוי: ${formatStorageSize(availableSpace)}"
                )

                Text(
                    text = "הנתונים נשמרים בתיקייה הייעודית של האפליקציה."
                )
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "מצב המאגר במכשיר",
                    fontWeight = FontWeight.Bold
                )

                if (databaseAvailable) {
                    Text(
                        text = "מאגר נתונים מותקן",
                        color = MaterialTheme.colorScheme.primary
                    )

                    if (lastUpdate > 0L) {
                        Text(
                            text = "עדכון אחרון: " +
                                dateFormatter.format(Date(lastUpdate))
                        )
                    }
                } else {
                    Text("עדיין לא הותקן מאגר נתונים.")
                }
            }
        }

        Button(
            onClick = {
                filePicker.launch(
                    arrayOf(
                        "application/gzip",
                        "application/x-gzip",
                        "application/octet-stream",
                        "application/vnd.sqlite3",
                        "application/x-sqlite3",
                        "*/*"
                    )
                )
            },
            enabled = !importing,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("בחירת קובץ נתונים מהמכשיר")
        }

        Text(
            text = "ניתן לבחור קובץ israel-transit.sqlite.gz " +
                "או קובץ SQLite לא דחוס.",
            style = MaterialTheme.typography.bodySmall
        )

        if (importing) {
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CircularProgressIndicator()

                    Text("מייבא נתונים. אין לסגור את האפליקציה.")

                    Text(
                        "המאגר גדול, ולכן הפעולה עשויה להימשך זמן רב.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        } else {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (importSucceeded) {
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
            enabled = !importing,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("חזרה למסך הבית")
        }
    }
}

private fun formatStorageSize(bytes: Long): String {
    if (bytes <= 0L) {
        return "לא ידוע"
    }

    val gigabyte = 1024.0 * 1024.0 * 1024.0
    val megabyte = 1024.0 * 1024.0

    return when {
        bytes >= gigabyte -> {
            String.format(
                Locale.getDefault(),
                "%.2f GB",
                bytes / gigabyte
            )
        }

        else -> {
            String.format(
                Locale.getDefault(),
                "%.0f MB",
                bytes / megabyte
            )
        }
    }
}
