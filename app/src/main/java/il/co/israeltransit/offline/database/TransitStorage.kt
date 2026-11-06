
package il.co.israeltransit.offline.database

import android.content.Context
import android.os.Environment
import android.os.StatFs
import java.io.File

/**
 * Manages the app's database storage locations.
 *
 * Supports internal storage and removable app-specific external storage
 * such as an SD card, when the device exposes one.
 */
class TransitStorage(context: Context) {

    private val appContext = context.applicationContext

    private val preferences = appContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    data class StorageLocation(
        val id: String,
        val label: String,
        val directory: File,
        val removable: Boolean,
        val availableBytes: Long
    )

    companion object {
        private const val PREFERENCES_NAME = "transit_storage"
        private const val KEY_SELECTED_DIRECTORY = "selected_directory"
        private const val DIRECTORY_NAME = "transit"
    }

    private fun internalDirectory(): File {
        return File(appContext.filesDir, DIRECTORY_NAME)
    }

    /**
     * Returns all currently available storage locations.
     * External locations are app-specific directories and do not
     * require broad external-storage permissions.
     */
    fun availableLocations(): List<StorageLocation> {
        val locations = mutableListOf<StorageLocation>()

        val internal = internalDirectory()
        if (internal.exists() || internal.mkdirs()) {
            locations += createLocation(
                directory = internal,
                label = "האחסון הפנימי",
                removable = false
            )
        }

        val externalRoots = appContext.getExternalFilesDirs(null)

        externalRoots
            .filterNotNull()
            .forEach { appSpecificRoot ->
                try {
                    val state = Environment.getExternalStorageState(
                        appSpecificRoot
                    )

                    if (state != Environment.MEDIA_MOUNTED) {
                        return@forEach
                    }

                    val removable = Environment.isExternalStorageRemovable(
                        appSpecificRoot
                    )

                    if (!removable) {
                        return@forEach
                    }

                    val directory = File(
                        appSpecificRoot,
                        DIRECTORY_NAME
                    )

                    if (!directory.exists() && !directory.mkdirs()) {
                        return@forEach
                    }

                    val canonicalPath =
                        directory.canonicalPath

                    if (locations.any {
                            it.directory.canonicalPath == canonicalPath
                        }) {
                        return@forEach
                    }

                    locations += createLocation(
                        directory = directory,
                        label = "כרטיס זיכרון חיצוני",
                        removable = true
                    )
                } catch (_: Exception) {
                    // Ignore unavailable or inaccessible volumes.
                }
            }

        return locations
    }

    private fun createLocation(
        directory: File,
        label: String,
        removable: Boolean
    ): StorageLocation {
        val availableBytes = try {
            StatFs(directory.absolutePath).availableBytes
        } catch (_: Exception) {
            0L
        }

        val canonicalPath = try {
            directory.canonicalPath
        } catch (_: Exception) {
            directory.absolutePath
        }

        return StorageLocation(
            id = canonicalPath,
            label = label,
            directory = directory,
            removable = removable,
            availableBytes = availableBytes
        )
    }

    /**
     * Returns the currently selected directory.
     * If the selected removable volume is unavailable,
     * internal storage is used temporarily.
     */
    fun getSelectedDirectory(): File {
        val selectedPath = preferences.getString(
            KEY_SELECTED_DIRECTORY,
            null
        )

        if (selectedPath != null) {
            val selected = availableLocations().firstOrNull {
                it.id == selectedPath
            }

            if (selected != null) {
                return selected.directory
            }
        }

        return internalDirectory().also {
            if (!it.exists()) {
                it.mkdirs()
            }
        }
    }

    /**
     * Saves a user's storage selection.
     * The location must currently be available.
     */
    fun selectLocation(locationId: String): Boolean {
        val location = availableLocations().firstOrNull {
            it.id == locationId
        } ?: return false

        if (!location.directory.exists() &&
            !location.directory.mkdirs()
        ) {
            return false
        }

        return preferences.edit()
            .putString(KEY_SELECTED_DIRECTORY, location.id)
            .commit()
    }

    fun getSelectedLocation(): StorageLocation? {
        val selectedDirectory = getSelectedDirectory()

        return availableLocations().firstOrNull {
            try {
                it.directory.canonicalPath ==
                    selectedDirectory.canonicalPath
            } catch (_: Exception) {
                false
            }
        }
    }

    fun getSelectedLocationLabel(): String {
        return getSelectedLocation()?.label
            ?: "האחסון הפנימי"
    }

    fun isExternalStorageSelected(): Boolean {
        return getSelectedLocation()?.removable == true
    }
}
