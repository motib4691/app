package il.co.israeltransit.offline

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import il.co.israeltransit.offline.maps.OfflineMapScreen

sealed class AppScreen {
    data object Home : AppScreen()
    data object StopSearch : AppScreen()
    data class LineSearch(val initialLineNumber: String? = null) : AppScreen()
    data object RoutePlanner : AppScreen()
    data object SavedStops : AppScreen()
    data object OfflineMap : AppScreen()
    data object UpdateData : AppScreen()
    data object Settings : AppScreen()
}

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
    val settings = remember(context, settingsVersion) { TransitSettings(context) }

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

    val backStack = remember { mutableStateListOf<AppScreen>(AppScreen.Home) }
    val currentScreen = backStack.lastOrNull() ?: AppScreen.Home

    fun navigateTo(screen: AppScreen) {
        backStack.add(screen)
    }

    fun navigateBack() {
        if (backStack.size > 1) {
            backStack.removeAt(backStack.lastIndex)
        }
    }

    BackHandler(enabled = backStack.size > 1) {
        navigateBack()
    }

    var plannerOrigin by remember { mutableStateOf<TransitRepository.TransitStop?>(null) }
    var plannerDestination by remember { mutableStateOf<TransitRepository.TransitStop?>(null) }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(colorScheme = colorScheme) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                when (val screen = currentScreen) {
                    is AppScreen.Home -> HomeScreen(onNavigate = { navigateTo(it) })
                    is AppScreen.StopSearch -> StopSearchScreen(
                        onBack = { navigateBack() },
                        onNavigateToLine = { lineNumber -> navigateTo(AppScreen.LineSearch(lineNumber)) },
                        onPlanRouteFrom = {
                            plannerOrigin = it
                            plannerDestination = null
                            navigateTo(AppScreen.RoutePlanner)
                        },
                        onPlanRouteTo = {
                            plannerOrigin = null
                            plannerDestination = it
                            navigateTo(AppScreen.RoutePlanner)
                        }
                    )
                    is AppScreen.LineSearch -> LineSearchScreen(
                        initialLine = screen.initialLineNumber,
                        onBack = { navigateBack() },
                        onNavigateToStop = { stop ->
                            navigateTo(AppScreen.StopSearch)
                        },
                        onPlanRouteFrom = {
                            plannerOrigin = it
                            plannerDestination = null
                            navigateTo(AppScreen.RoutePlanner)
                        },
                        onPlanRouteTo = {
                            plannerOrigin = null
                            plannerDestination = it
                            navigateTo(AppScreen.RoutePlanner)
                        }
                    )
                    is AppScreen.RoutePlanner -> RoutePlannerScreen(
                        onBack = { navigateBack() },
                        initialOrigin = plannerOrigin,
                        initialDestination = plannerDestination,
                        onClearInitialStops = {
                            plannerOrigin = null
                            plannerDestination = null
                        }
                    )
                    is AppScreen.SavedStops -> SavedStopsScreen(
                        onBack = { navigateBack() },
                        onPlanRouteFrom = {
                            plannerOrigin = it
                            plannerDestination = null
                            navigateTo(AppScreen.RoutePlanner)
                        },
                        onPlanRouteTo = {
                            plannerOrigin = null
                            plannerDestination = it
                            navigateTo(AppScreen.RoutePlanner)
                        }
                    )
                    is AppScreen.OfflineMap -> OfflineMapScreen(
                        onBack = { navigateBack() },
                        onOpenStopDetails = { stop ->
                            navigateTo(AppScreen.StopSearch)
                        }
                    )
                    is AppScreen.UpdateData -> UpdateDataScreen(onBack = { navigateBack() })
                    is AppScreen.Settings -> SettingsScreen(
                        onBack = { navigateBack() },
                        onSettingsChanged = { settingsVersion++ }
                    )
                }
            }
        }
    }
}

private data class HomeMenuItemData(
    val title: String,
    val description: String,
    val screen: AppScreen,
    val iconSymbol: String
)

@Composable
private fun HomeScreen(onNavigate: (AppScreen) -> Unit) {
    val items = listOf(
        HomeMenuItemData("תכנון מסלול", "מציאת מסלול נסיעה בין שתי נקודות", AppScreen.RoutePlanner, "🧭"),
        HomeMenuItemData("חיפוש קו", "חיפוש קווים לפי מספר ומפעיל", AppScreen.LineSearch(), "🚌"),
        HomeMenuItemData("חיפוש תחנה", "איתור תחנות וקווים העוברים בהן", AppScreen.StopSearch, "🚏"),
        HomeMenuItemData("מפה לא מקוונת", "מפה אינטראקטיבית, איתור תחנות וערי ישראל", AppScreen.OfflineMap, "🗺️"),
        HomeMenuItemData("התחנות שלי", "התחנות ששמרת לשימוש מהיר", AppScreen.SavedStops, "⭐"),
        HomeMenuItemData("עדכון נתונים", "ייבוא ועדכון מאגר התחבורה וקובץ המפה", AppScreen.UpdateData, "🔄"),
        HomeMenuItemData("הגדרות", "התאמת החלפות, מרווחים, רטט בירידה ומראה", AppScreen.Settings, "⚙️")
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "IsraelOfflineTransit",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "תחבורה ציבורית לא-מקוונת בישראל",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(20.dp))

        items.forEach { item ->
            Card(
                onClick = { onNavigate(item.screen) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 5.dp),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                        modifier = Modifier.size(44.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(text = item.iconSymbol, fontSize = 22.sp)
                        }
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.title,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = item.description,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.size(28.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = "←",
                                fontSize = 14.sp,
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
