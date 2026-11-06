
package il.co.israeltransit.offline.database

import android.content.Context
import android.os.Environment
import android.os.StatFs
import java.io.File

class TransitStorage(context: Context) {

    private val appContext = context.applicationContext

    private val preferences = appContext.getSharedPreferences(
        "transit_storage",
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
        private const val DIRECTORY_NAME = "transit"
        private const val KEY_SELECTED_DIRECTORY = "selected_directory"
    }

    private fun internalDirectory(): File =
        File(appContext.filesDir, DIRECTORY_NAME)

    fun availableLocations(): List<StorageLocation> {
        val locations = mutableListOf<StorageLocation>()

        val internal = internalDirectory()

        if (!internal.exists()) {
            internal.mkdirs()
        }

        if (internal.exists()) {
            locations += createLocation(
                internal,
                "האחסון הפנימי",
                false
            )
        }

        appContext.getExternalFilesDirs(null)
            .filterNotNull()
            .forEach { root ->
                try {
                    if (
                        Environment.getExternalStorageState(root) !=
                        Environment.MEDIA_MOUNTED
                    ) {
                        return@forEach
                    }

                    if (!Environment.isExternalStorageRemovable(root)) {
                        return@forEach
                    }

                    val directory = File(root, DIRECTORY_NAME)

                    if (!directory.exists() && !directory.mkdirs()) {
                        return@forEach
                    }

                    val location = createLocation(
                        directory,
                        "כרטיס זיכרון חיצוני",
                        true
                    )

                    if (locations.none { it.id == location.id }) {
                        locations += location
                    }
                } catch (_: Exception) {
                    // Ignore unavailable storage.
                }
            }

        return locations
    }

    private fun createLocation(
        directory: File,
        label: String,
        removable: Boolean
    ): StorageLocation {
        val id = try {
            directory.canonicalPath
        } catch (_: Exception) {
            directory.absolutePath
        }

        val available = try {
            StatFs(directory.absolutePath).availableBytes
        } catch (_: Exception) {
            0L
        }

        return StorageLocation(
            id = id,
            label = label,
            directory = directory,
            removable = removable,
            availableBytes = available
        )
    }

    /**
     * Internal storage is the default.
     * A saved external selection is used only while available.
     */
    fun getSelectedDirectory(): File {
        val savedId = preferences.getString(
            KEY_SELECTED_DIRECTORY,
            null
        )

        if (savedId != null) {
            val selected = availableLocations().firstOrNull {
                it.id == savedId
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

    fun getSelectedLocation(): StorageLocation? {
        val selectedDirectory = getSelectedDirectory()

        val selectedId = try {
            selectedDirectory.canonicalPath
        } catch (_: Exception) {
            selectedDirectory.absolutePath
        }

        return availableLocations().firstOrNull {
            it.id == selectedId
        }
    }

    fun getSelectedLocationLabel(): String =
        getSelectedLocation()?.label ?: "האחסון הפנימי"

    fun isExternalStorageSelected(): Boolean =
        getSelectedLocation()?.removable == true

    /**
     * Saves an explicit user selection.
     * No automatic switching or migration is performed here.
     */
    fun selectLocation(locationId: String): Boolean {
        val location = availableLocations().firstOrNull {
            it.id == locationId
        } ?: return false

        return preferences.edit()
            .putString(KEY_SELECTED_DIRECTORY, location.id)
            .commit()
    }

    fun selectInternalStorage(): Boolean {
        return preferences.edit()
            .remove(KEY_SELECTED_DIRECTORY)
            .commit()
    }
}
