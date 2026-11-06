package il.co.israeltransit.offline

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import il.co.israeltransit.offline.maps.OfflineMapImportScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TransitApp()
        }
    }
}

private val TransitColors = lightColorScheme(
    primary = Color(0xFF1769AA),
    secondary = Color(0xFF168477),
    background = Color(0xFFF4F6F9),
    surface = Color.White
)

@Composable
fun TransitApp() {
    var currentScreen by remember {
        mutableStateOf("home")
    }

    BackHandler(enabled = currentScreen != "home") {
        currentScreen = "home"
    }

    CompositionLocalProvider(
        LocalLayoutDirection provides LayoutDirection.Rtl
    ) {
        MaterialTheme(colorScheme = TransitColors) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                when (currentScreen) {
                    "home" -> HomeScreen(
                        onNavigate = { currentScreen = it }
                    )

                    "update" -> UpdateDataScreen(
                        onBack = { currentScreen = "home" }
                    )

                    "line" -> LineSearchScreen(
                        onBack = { currentScreen = "home" }
                    )

                    "station" -> StopSearchScreen(
                        onBack = { currentScreen = "home" }
                    )

                    "saved" -> SavedStopsScreen(
                        onBack = { currentScreen = "home" }
                    )

                    "route" -> RoutePlannerScreen(
                        onBack = { currentScreen = "home" }
                    )

                    "map" -> OfflineMapImportScreen(
                        onBack = { currentScreen = "home" }
                    )

                    else -> HomeScreen(
                        onNavigate = { currentScreen = it }
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(
    onNavigate: (String) -> Unit
) {
    val items = listOf(
        Triple(
            "תכנון מסלול",
            "מציאת מסלול נסיעה בין שתי נקודות",
            "route"
        ),
        Triple(
            "חיפוש קו",
            "חיפוש קווים לפי מספר ומפעיל",
            "line"
        ),
        Triple(
            "חיפוש תחנה",
            "איתור תחנות וקווים העוברים בהן",
            "station"
        ),
        Triple(
            "התחנות שלי",
            "התחנות ששמרת לשימוש מהיר",
            "saved"
        ),
        Triple(
            "עדכון נתונים",
            "ייבוא ועדכון מאגר התחבורה",
            "update"
        ),
        Triple(
            "מפה לא מקוונת",
            "התקנת מפה ותצוגת תחנות התחבורה",
            "map"
        )
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "תחבורה ציבורית בישראל",
            fontSize = 25.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "תכנון נסיעות וחיפוש מידע גם ללא חיבור לאינטרנט",
            fontSize = 14.sp,
            color = Color.DarkGray
        )

        Spacer(modifier = Modifier.height(30.dp))

        items.forEachIndexed { index, item ->
            HomeMenuItem(
                number = index + 1,
                title = item.first,
                description = item.second,
                onClick = { onNavigate(item.third) }
            )

            Spacer(modifier = Modifier.height(13.dp))
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "מאגר נתונים מקומי",
            fontSize = 12.sp,
            color = Color.Gray
        )
    }
}

@Composable
private fun HomeMenuItem(
    number: Int,
    title: String,
    description: String,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(15.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.White
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 2.dp
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color(0xFFE7F0FA)
            ) {
                Text(
                    text = number.toString(),
                    modifier = Modifier.padding(
                        horizontal = 14.dp,
                        vertical = 10.dp
                    ),
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = title,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = description,
                    fontSize = 12.sp,
                    color = Color.Gray
                )
            }

            Text(
                text = "‹",
                fontSize = 27.sp,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
