package il.co.israeltransit.offline

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import il.co.israeltransit.offline.database.TransitRepository
import il.co.israeltransit.offline.maps.OfflineMapImportScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TransitApp()
        }
    }
}

@Composable
fun TransitApp() {
    val context = LocalContext.current
    var settingsVersion by remember { mutableIntStateOf(0) }
    val settings = remember(context, settingsVersion) {
        TransitSettings(context)
    }

    val isDark = when (settings.themeMode) {
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
    }

    val colorTheme = settings.colorTheme
    val colorScheme = if (isDark) {
        darkColorScheme(
            primary = colorTheme.primary,
            secondary = colorTheme.secondary,
            background = colorTheme.backgroundDark,
            surface = Color(0xFF1E1E1E)
        )
    } else {
        lightColorScheme(
            primary = colorTheme.primary,
            secondary = colorTheme.secondary,
            background = colorTheme.backgroundLight,
            surface = Color.White
        )
    }

    var currentScreen by remember { mutableStateOf("home") }

    var routePlannerInitialOrigin by remember { mutableStateOf<TransitRepository.TransitStop?>(null) }
    var routePlannerInitialDestination by remember { mutableStateOf<TransitRepository.TransitStop?>(null) }

    BackHandler(enabled = currentScreen != "home") {
        currentScreen = "home"
    }

    CompositionLocalProvider(
        LocalLayoutDirection provides LayoutDirection.Rtl
    ) {
        MaterialTheme(colorScheme = colorScheme) {
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
                        onBack = { currentScreen = "home" },
                        onPlanRouteFrom = { stop ->
                            routePlannerInitialOrigin = stop
                            routePlannerInitialDestination = null
                            currentScreen = "route"
                        },
                        onPlanRouteTo = { stop ->
                            routePlannerInitialOrigin = null
                            routePlannerInitialDestination = stop
                            currentScreen = "route"
                        }
                    )

                    "station" -> StopSearchScreen(
                        onBack = { currentScreen = "home" },
                        onPlanRouteFrom = { stop ->
                            routePlannerInitialOrigin = stop
                            routePlannerInitialDestination = null
                            currentScreen = "route"
                        },
                        onPlanRouteTo = { stop ->
                            routePlannerInitialOrigin = null
                            routePlannerInitialDestination = stop
                            currentScreen = "route"
                        }
                    )

                    "saved" -> SavedStopsScreen(
                        onBack = { currentScreen = "home" },
                        onPlanRouteFrom = { stop ->
                            routePlannerInitialOrigin = stop
                            routePlannerInitialDestination = null
                            currentScreen = "route"
                        },
                        onPlanRouteTo = { stop ->
                            routePlannerInitialOrigin = null
                            routePlannerInitialDestination = stop
                            currentScreen = "route"
                        }
                    )

                    "route" -> RoutePlannerScreen(
                        onBack = { currentScreen = "home" },
                        initialOrigin = routePlannerInitialOrigin,
                        initialDestination = routePlannerInitialDestination,
                        onClearInitialStops = {
                            routePlannerInitialOrigin = null
                            routePlannerInitialDestination = null
                        }
                    )

                    "map" -> OfflineMapImportScreen(
                        onBack = { currentScreen = "home" },
                        onPlanRouteFrom = { stop ->
                            routePlannerInitialOrigin = stop
                            routePlannerInitialDestination = null
                            currentScreen = "route"
                        },
                        onPlanRouteTo = { stop ->
                            routePlannerInitialOrigin = null
                            routePlannerInitialDestination = stop
                            currentScreen = "route"
                        }
                    )

                    "settings" -> SettingsScreen(
                        onBack = { currentScreen = "home" },
                        onSettingsChanged = { settingsVersion++ }
                    )

                    else -> HomeScreen(
                        onNavigate = { currentScreen = it }
                    )
                }
            }
        }
    }
}

private data class HomeItemData(
    val title: String,
    val description: String,
    val screenKey: String,
    val iconSymbol: String
)

@Composable
private fun HomeScreen(
    onNavigate: (String) -> Unit
) {
    val items = listOf(
        HomeItemData(
            title = "תכנון מסלול",
            description = "מציאת מסלול נסיעה בין שתי נקודות",
            screenKey = "route",
            iconSymbol = "🧭"
        ),
        HomeItemData(
            title = "חיפוש קו",
            description = "חיפוש קווים לפי מספר ומפעיל",
            screenKey = "line",
            iconSymbol = "🚌"
        ),
        HomeItemData(
            title = "חיפוש תחנה",
            description = "איתור תחנות וקווים העוברים בהן",
            screenKey = "station",
            iconSymbol = "🚏"
        ),
        HomeItemData(
            title = "התחנות שלי",
            description = "התחנות ששמרת לשימוש מהיר",
            screenKey = "saved",
            iconSymbol = "⭐"
        ),
        HomeItemData(
            title = "עדכון נתונים",
            description = "ייבוא ועדכון מאגר התחבורה",
            screenKey = "update",
            iconSymbol = "🔄"
        ),
        HomeItemData(
            title = "מפה לא מקוונת",
            description = "התקנת מפה ותצוגת תחנות התחבורה",
            screenKey = "map",
            iconSymbol = "🗺️"
        ),
        HomeItemData(
            title = "הגדרות",
            description = "התאמת כמות החלפות, מרווח ביטחון ומראה",
            screenKey = "settings",
            iconSymbol = "⚙️"
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

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "תכנון נסיעות וחיפוש מידע גם ללא חיבור לאינטרנט",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))

        items.forEach { item ->
            HomeMenuItem(
                title = item.title,
                description = item.description,
                iconSymbol = item.iconSymbol,
                onClick = { onNavigate(item.screenKey) }
            )

            Spacer(modifier = Modifier.height(12.dp))
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "מאגר נתונים מקומי ללא צורך באינטרנט",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.outline
        )
    }
}

@Composable
private fun HomeMenuItem(
    title: String,
    description: String,
    iconSymbol: String,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 2.dp
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = iconSymbol,
                        fontSize = 24.sp
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = title,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(3.dp))

                Text(
                    text = description,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(32.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "←",
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
