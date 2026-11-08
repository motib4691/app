package il.co.israeltransit.offline

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

private const val TRANSIT_DATA_URL =
    "https://github.com/motibollag/israel-offline-transit/releases/download/transit-data/israel-transit.sqlite.gz"

private const val OFFLINE_MAP_URL =
    "https://github.com/motibollag/israel-offline-transit/releases/download/offline-map/israel-basemap.pmtiles"

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
    var themeMode by remember { mutableStateOf(settings.themeMode) }
    var colorTheme by remember { mutableStateOf(settings.colorTheme) }

    var transferSliderValue by remember { mutableFloatStateOf(maxTransfers.toFloat()) }
    var bufferSliderValue by remember { mutableFloatStateOf(minTransferMinutes.toFloat()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "הגדרות",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            OutlinedButton(onClick = onBack) {
                Text("חזרה")
            }
        }

        Text(
            text = "התאמת אופן תכנון המסלול, הוראות הורדה ונראות האפליקציה.",
            style = MaterialTheme.typography.bodyMedium
        )

        // תכנון מסלול
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "תכנון נסיעה",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                Text(
                    text = "כמות החלפות מרבית מבוקשת:",
                    fontWeight = FontWeight.Medium
                )

                Text(
                    text = if (maxTransfers == 0) "נסיעה ישירה בלבד (ללא החלפות)"
                           else "$maxTransfers החלפות לכל היותר",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.secondary,
                    fontWeight = FontWeight.Bold
                )

                Slider(
                    value = transferSliderValue,
                    onValueChange = {
                        transferSliderValue = it
                        maxTransfers = it.roundToInt()
                    },
                    onValueChangeFinished = {
                        val rounded = transferSliderValue.roundToInt()
                        maxTransfers = rounded
                        settings.maxTransfers = rounded
                        onSettingsChanged()
                    },
                    valueRange = 0f..5f,
                    steps = 4
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("ישיר", style = MaterialTheme.typography.bodySmall)
                    Text("1", style = MaterialTheme.typography.bodySmall)
                    Text("2", style = MaterialTheme.typography.bodySmall)
                    Text("3 (ברירת מחדל)", style = MaterialTheme.typography.bodySmall)
                    Text("4", style = MaterialTheme.typography.bodySmall)
                    Text("5", style = MaterialTheme.typography.bodySmall)
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "מרווח ביטחון מינימלי להחלפת קו:",
                    fontWeight = FontWeight.Medium
                )

                Text(
                    text = "$minTransferMinutes דקות המתנה רצויה",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.secondary,
                    fontWeight = FontWeight.Bold
                )

                Slider(
                    value = bufferSliderValue,
                    onValueChange = {
                        bufferSliderValue = it
                        minTransferMinutes = it.roundToInt()
                    },
                    onValueChangeFinished = {
                        val rounded = bufferSliderValue.roundToInt()
                        minTransferMinutes = rounded
                        settings.minTransferMinutes = rounded
                        onSettingsChanged()
                    },
                    valueRange = 1f..15f,
                    steps = 13
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("1 דק'", style = MaterialTheme.typography.bodySmall)
                    Text("2 (ברירת מחדל)", style = MaterialTheme.typography.bodySmall)
                    Text("15 דק'", style = MaterialTheme.typography.bodySmall)
                }

                Spacer(modifier = Modifier.height(6.dp))

                // מתג חסימת/אישור הליכה בין תחנות בהחלפה
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "אפשר הליכה בין תחנות בהחלפה",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = if (allowWalkingTransfers)
                                "מאפשר מעבר רגלי קצר (עד 350 מטר) בין תחנות סמוכות"
                            else
                                "יוצגו רק מסלולים ישירים או החלפות באותה תחנה בלבד",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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
            }
        }

        // חלונית הוראות הורדת הקבצים וקישורים ישירים
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "הורדת קובצי נתונים ומפה",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                Text(
                    text = "הנחיות לעדכון המאגרים:",
                    fontWeight = FontWeight.Medium
                )

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "• קובץ המפה (PMTiles): יציב מאוד ומכיל את כל רשת הדרכים והשבילים בארץ. כמעט ואין צורך לעדכן אותו (אלא פעם בתקופה ממושכת מאוד).",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = "• מאגר התחבורה (GTFS): מתעדכן בתחילת כל חודש עם לוחות זמנים ומסלולים חדשים. מומלץ לעדכן אותו פעם בחודש.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                Text(
                    text = "קישורים ישירים להורדה:",
                    fontWeight = FontWeight.Bold
                )

                // קישור נתוני תחבורה
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "מאגר נתוני תחבורה (israel-transit.sqlite.gz)",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = TRANSIT_DATA_URL,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                        FilledTonalButton(
                            onClick = {
                                clipboardManager.setText(AnnotatedString(TRANSIT_DATA_URL))
                                Toast.makeText(context, "קישור נתוני התחבורה הועתק ללוח", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("העתק קישור להורדה")
                        }
                    }
                }

                // קישור מפה לא מקוונת
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "מפה לא מקוונת (israel-basemap.pmtiles)",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = OFFLINE_MAP_URL,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                        FilledTonalButton(
                            onClick = {
                                clipboardManager.setText(AnnotatedString(OFFLINE_MAP_URL))
                                Toast.makeText(context, "קישור המפה הועתק ללוח", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("העתק קישור להורדה")
                        }
                    }
                }
            }
        }

        // מראה ונראות
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "עיצוב ומראה",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                Text(
                    text = "מצב תצוגה:",
                    fontWeight = FontWeight.Medium
                )

                Column {
                    listOf(
                        AppThemeMode.SYSTEM to "לפי הגדרות המכשיר",
                        AppThemeMode.LIGHT to "מצב בהיר",
                        AppThemeMode.DARK to "מצב כהה"
                    ).forEach { (mode, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    themeMode = mode
                                    settings.themeMode = mode
                                    onSettingsChanged()
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = themeMode == mode,
                                onClick = {
                                    themeMode = mode
                                    settings.themeMode = mode
                                    onSettingsChanged()
                                }
                            )
                            Text(
                                text = label,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "צבע נושא ראשי:",
                    fontWeight = FontWeight.Medium
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    AppColorTheme.entries.forEach { theme ->
                        val isSelected = colorTheme == theme
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    colorTheme = theme
                                    settings.colorTheme = theme
                                    onSettingsChanged()
                                }
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(theme.primary)
                                    .border(
                                        width = if (isSelected) 3.dp else 1.dp,
                                        color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant,
                                        shape = CircleShape
                                    )
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = theme.title,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }
        }

        // אודות
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "אודות האפליקציה",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "IsraelOfflineTransit — גרסה 1.0.0",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "האפליקציה פועלת באופן לא מקוון ומאפשרת שימוש מלא ללא תלות ברשת או ב-GPS.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedButton(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("חזרה למסך הבית")
        }
    }
}
