package il.co.israeltransit.offline

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import il.co.israeltransit.offline.database.TransitDatabase
import il.co.israeltransit.offline.database.TransitStorage
import il.co.israeltransit.offline.database.TransitStorageMigration
import il.co.israeltransit.offline.maps.OfflineMapStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun UpdateDataScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val database = remember(context) { TransitDatabase(context) }
    val mapStorage = remember(context) { OfflineMapStorage(context) }
    val storage = remember(context) { TransitStorage(context) }
    val migration = remember(context) { TransitStorageMigration(context) }
    val scope = rememberCoroutineScope()

    var busy by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("בחר קובץ נתונים או קובץ מפה להתקנה.") }
    var refreshVersion by remember { mutableIntStateOf(0) }

    val locations = remember(refreshVersion) { storage.availableLocations() }
    val selectedLocationId = remember(refreshVersion) {
        storage.getConfiguredLocationId() ?: storage.availableLocations().firstOrNull { !it.removable }?.id
    }
    val databaseAvailable = remember(refreshVersion) { database.isDatabaseAvailable() }
    val mapAvailable = remember(refreshVersion) { mapStorage.isMapAvailable() }
    val lastUpdate = remember(refreshVersion) { database.getLastUpdateDate() }
    val dateFormatter = remember { SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()) }

    val transitFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !busy) {
            scope.launch {
                busy = true
                statusMessage = "מתבצע ייבוא מהיר של מאגר התחבורה..."
                val result = withContext(Dispatchers.IO) { database.importDatabase(uri) }
                busy = false
                statusMessage = result.message
                refreshVersion++
            }
        }
    }

    val mapFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !busy) {
            scope.launch {
                busy = true
                statusMessage = "מתבצע ייבוא ואימות של קובץ המפה הלא-מקוונת..."
                val result = withContext(Dispatchers.IO) { mapStorage.importArchive(uri) }
                busy = false
                statusMessage = result.message
                refreshVersion++
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("ייבוא ועדכון נתונים", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            OutlinedButton(onClick = onBack, shape = RoundedCornerShape(20.dp)) { Text("חזרה") }
        }

        Text("מרכז ניהול קובצי הנתונים והמפה של האפליקציה לפעילות מלאה ללא אינטרנט.", style = MaterialTheme.typography.bodySmall)

        // כרטיס מאגר תחבורה GTFS
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(1.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("מאגר נתוני תחבורה (GTFS)", fontWeight = FontWeight.Bold)
                if (databaseAvailable) {
                    Text("מותקן במכשיר", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Text("עדכון אחרון: ${dateFormatter.format(Date(lastUpdate))}", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("עדיין לא הותקן מאגר נתונים", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                FilledTonalButton(
                    onClick = { transitFilePicker.launch(arrayOf("*/*")) },
                    enabled = !busy,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("ייבוא קובץ נתוני תחבורה (israel-transit.sqlite.gz)")
                }
            }
        }

        // כרטיס מפה וקטורית PMTiles
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(1.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("קובץ מפה לא מקוונת (PMTiles)", fontWeight = FontWeight.Bold)
                if (mapAvailable) {
                    Text("קובץ מפה מותקן ותקין", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                } else {
                    Text("עדיין לא הותקן קובץ מפה (אופציונלי)", style = MaterialTheme.typography.bodySmall)
                }
                FilledTonalButton(
                    onClick = { mapFilePicker.launch(arrayOf("*/*")) },
                    enabled = !busy,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("ייבוא קובץ מפה (israel-basemap.pmtiles)")
                }
            }
        }

        // כרטיס בחירת אחסון
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(1.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("מיקום אחסון הקבצים", fontWeight = FontWeight.Bold)
                locations.forEach { loc ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedLocationId == loc.id,
                            onClick = {
                                if (!busy && selectedLocationId != loc.id) {
                                    scope.launch {
                                        busy = true
                                        withContext(Dispatchers.IO) { migration.moveTo(loc.id) }
                                        busy = false
                                        refreshVersion++
                                    }
                                }
                            }
                        )
                        Text(loc.label, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }

        if (busy) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator()
            }
        }

        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(statusMessage, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
        }
    }
}
