package il.co.israeltransit.offline

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

private const val TRANSIT_DATA_URL = "https://github.com/motibollag/israel-offline-transit/releases/download/transit-data/israel-transit.sqlite.gz"
private const val OFFLINE_MAP_URL = "https://github.com/motibollag/israel-offline-transit/releases/download/offline-map/israel-basemap.pmtiles"

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onSettingsChanged: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val settings = remember(context) { TransitSettings(context) }

    var maxTransfers by remember { mutableIntStateOf(settings.maxTransfers) }
    var minTransferMinutes by remember { mutableIntStateOf(settings.minTransferMinutes) }
    var allowWalkingTransfers by remember { mutableStateOf(settings.allowWalkingTransfers) }
    var transitMode by remember { mutableStateOf(settings.transitModePreference) }
    var alightVibration by remember { mutableStateOf(settings.alightVibrationEnabled) }
    var themeMode by remember { mutableStateOf(settings.themeMode) }
    var colorTheme by remember { mutableStateOf(settings.colorTheme) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("הגדרות", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            OutlinedButton(onClick = onBack, shape = RoundedCornerShape(20.dp)) { Text("חזרה") }
        }

        // תכנון מסלול והעדפות
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(1.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("העדפות תכנון מסלול", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)

                Text("העדפת אמצעי תחבורה:", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        TransitModePreference.ALL to "כל האמצעים",
                        TransitModePreference.PREFER_TRAIN to "העדפת רכבת",
                        TransitModePreference.BUS_ONLY to "אוטובוסים בלבד"
                    ).forEach { (mode, label) ->
                        FilterChip(
                            selected = transitMode == mode,
                            onClick = {
                                transitMode = mode
                                settings.transitModePreference = mode
                                onSettingsChanged()
                            },
                            label = { Text(label, fontSize = 12.sp) },
                            shape = RoundedCornerShape(20.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text("כמות החלפות מרבית: ${if (maxTransfers == 0) "ישיר בלבד" else "$maxTransfers החלפות"}", fontWeight = FontWeight.Medium)
                Slider(
                    value = maxTransfers.toFloat(),
                    onValueChange = { maxTransfers = it.roundToInt() },
                    onValueChangeFinished = { settings.maxTransfers = maxTransfers; onSettingsChanged() },
                    valueRange = 0f..5f,
                    steps = 4
                )

                Text("מרווח ביטחון מינימלי: $minTransferMinutes דקות", fontWeight = FontWeight.Medium)
                Slider(
                    value = minTransferMinutes.toFloat(),
                    onValueChange = { minTransferMinutes = it.roundToInt() },
                    onValueChangeFinished = { settings.minTransferMinutes = minTransferMinutes; onSettingsChanged() },
                    valueRange = 1f..15f,
                    steps = 13
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("מעבר רגלי בין תחנות סמוכות", fontWeight = FontWeight.Bold)
                        Text("מאפשר הליכה קצרה (עד 350 מטר) בין תחנות בהחלפה", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(
                        checked = allowWalkingTransfers,
                        onCheckedChange = {
                            allowWalkingTransfers = it
                            settings.allowWalkingTransfers = it
                            onSettingsChanged()
                        }
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                // מתג התראת ירידה
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("התראת רטט לפני תחנת ירידה", fontWeight = FontWeight.Bold)
                        Text("מרטיט את המכשיר בקרבת תחנת היעד (פעיל רק במכשיר עם רכיב GPS)", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(
                        checked = alightVibration,
                        onCheckedChange = {
                            alightVibration = it
                            settings.alightVibrationEnabled = it
                            onSettingsChanged()
                        }
                    )
                }
            }
        }

        // עיצוב ומראה
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(1.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("עיצוב ומראה", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(AppThemeMode.SYSTEM to "מערכת", AppThemeMode.LIGHT to "בהיר", AppThemeMode.DARK to "כהה").forEach { (mode, label) ->
                        FilterChip(
                            selected = themeMode == mode,
                            onClick = { themeMode = mode; settings.themeMode = mode; onSettingsChanged() },
                            label = { Text(label) },
                            shape = RoundedCornerShape(20.dp)
                        )
                    }
                }

                Text("צבע נושא ראשי:", fontWeight = FontWeight.Medium)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AppColorTheme.entries.forEach { theme ->
                        val isSelected = colorTheme == theme
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(theme.primary)
                                .border(if (isSelected) 3.dp else 1.dp, if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant, CircleShape)
                                .clickable { colorTheme = theme; settings.colorTheme = theme; onSettingsChanged() }
                        )
                    }
                }
            }
        }

        // קישורים להורדה
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(1.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("קישורים לקובצי נתונים", fontWeight = FontWeight.Bold)
                FilledTonalButton(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(TRANSIT_DATA_URL))
                        Toast.makeText(context, "הקישור הועתק ללוח", Toast.LENGTH_SHORT).show()
                    },
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("העתק קישור למאגר תחבורה (israel-transit.sqlite.gz)")
                }

                FilledTonalButton(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(OFFLINE_MAP_URL))
                        Toast.makeText(context, "הקישור הועתק ללוח", Toast.LENGTH_SHORT).show()
                    },
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("העתק קישור לקובץ מפה (israel-basemap.pmtiles)")
                }
            }
        }
    }
}
