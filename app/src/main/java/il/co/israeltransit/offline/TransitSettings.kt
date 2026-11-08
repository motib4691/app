package il.co.israeltransit.offline

import android.content.Context
import androidx.compose.ui.graphics.Color

enum class AppThemeMode {
    SYSTEM,
    LIGHT,
    DARK
}

enum class AppColorTheme(
    val title: String,
    val primary: Color,
    val secondary: Color,
    val backgroundLight: Color = Color(0xFFF4F6F9),
    val backgroundDark: Color = Color(0xFF121212)
) {
    BLUE(
        title = "כחול תחבורה",
        primary = Color(0xFF1769AA),
        secondary = Color(0xFF168477)
    ),
    TEAL(
        title = "טורקיז ירוק",
        primary = Color(0xFF0D7C66),
        secondary = Color(0xFF41B3A2)
    ),
    INDIGO(
        title = "אינדיגו עמוק",
        primary = Color(0xFF4338CA),
        secondary = Color(0xFF6366F1)
    ),
    AMBER(
        title = "כתום חם",
        primary = Color(0xFFD97706),
        secondary = Color(0xFFB45309)
    )
}

class TransitSettings(context: Context) {

    private val preferences = context.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    companion object {
        private const val PREFERENCES_NAME = "israel_transit_settings"
        private const val KEY_MAX_TRANSFERS = "max_transfers"
        private const val KEY_MIN_TRANSFER_MINUTES = "min_transfer_minutes"
        private const val KEY_ALLOW_WALKING_TRANSFERS = "allow_walking_transfers"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_COLOR_THEME = "color_theme"

        const val DEFAULT_MAX_TRANSFERS = 3
        const val DEFAULT_MIN_TRANSFER_MINUTES = 2
        const val DEFAULT_ALLOW_WALKING_TRANSFERS = true
    }

    var maxTransfers: Int
        get() = preferences.getInt(KEY_MAX_TRANSFERS, DEFAULT_MAX_TRANSFERS).coerceIn(0, 5)
        set(value) {
            preferences.edit().putInt(KEY_MAX_TRANSFERS, value.coerceIn(0, 5)).apply()
        }

    var minTransferMinutes: Int
        get() = preferences.getInt(KEY_MIN_TRANSFER_MINUTES, DEFAULT_MIN_TRANSFER_MINUTES).coerceIn(1, 15)
        set(value) {
            preferences.edit().putInt(KEY_MIN_TRANSFER_MINUTES, value.coerceIn(1, 15)).apply()
        }

    var allowWalkingTransfers: Boolean
        get() = preferences.getBoolean(KEY_ALLOW_WALKING_TRANSFERS, DEFAULT_ALLOW_WALKING_TRANSFERS)
        set(value) {
            preferences.edit().putBoolean(KEY_ALLOW_WALKING_TRANSFERS, value).apply()
        }

    var themeMode: AppThemeMode
        get() {
            val name = preferences.getString(KEY_THEME_MODE, AppThemeMode.SYSTEM.name)
            return try {
                AppThemeMode.valueOf(name ?: AppThemeMode.SYSTEM.name)
            } catch (_: Exception) {
                AppThemeMode.SYSTEM
            }
        }
        set(value) {
            preferences.edit().putString(KEY_THEME_MODE, value.name).apply()
        }

    var colorTheme: AppColorTheme
        get() {
            val name = preferences.getString(KEY_COLOR_THEME, AppColorTheme.BLUE.name)
            return try {
                AppColorTheme.valueOf(name ?: AppColorTheme.BLUE.name)
            } catch (_: Exception) {
                AppColorTheme.BLUE
            }
        }
        set(value) {
            preferences.edit().putString(KEY_COLOR_THEME, value.name).apply()
        }
}
