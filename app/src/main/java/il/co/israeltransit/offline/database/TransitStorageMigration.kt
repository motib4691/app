
package il.co.israeltransit.offline.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class TransitStorageMigration(context: Context) {

    private val appContext = context.applicationContext
    private val storage = TransitStorage(appContext)

    companion object {
        private const val DATABASE_NAME = "israel-transit.sqlite"
        private const val TEMP_NAME = "israel-transit.migration"
        private const val BACKUP_NAME = "israel-transit.previous"
    }

    data class MigrationResult(
        val success: Boolean,
        val message: String
    )

    /**
     * Copies the current database to the selected destination.
     * The source is never deleted.
     */
    @Synchronized
    fun moveTo(locationId: String): MigrationResult {
        var temporary: File? = null
        var backup: File? = null
        var destination: File? = null

        return try {
            val locations = storage.availableLocations()
            val target = locations.firstOrNull {
                it.id == locationId
            } ?: return MigrationResult(
                false,
                "מיקום האחסון שנבחר אינו זמין"
            )

            val sourceDirectory = storage.getSelectedDirectory()
            val source = File(sourceDirectory, DATABASE_NAME)

            destination = File(target.directory, DATABASE_NAME)

            if (source.canonicalPath == destination.canonicalPath) {
                return MigrationResult(
                    storage.selectLocation(locationId),
                    "מיקום האחסון עודכן"
                )
            }

            if (!source.exists()) {
                val selected = storage.selectLocation(locationId)

                return MigrationResult(
                    selected,
                    if (selected) {
                        "מיקום האחסון עודכן. עדיין אין מאגר נתונים להעברה."
                    } else {
                        "לא ניתן לשמור את בחירת מיקום האחסון"
                    }
                )
            }

            check(target.directory.exists() || target.directory.mkdirs()) {
                "לא ניתן ליצור את תיקיית היעד"
            }

            val sourceSize = source.length()
            val available = target.availableBytes

            check(available > sourceSize + 50L * 1024L * 1024L) {
                "אין מספיק מקום פנוי ביעד להעברת המאגר"
            }

            temporary = File(target.directory, TEMP_NAME)
            backup = File(target.directory, BACKUP_NAME)

            temporary.delete()

            copyFile(source, temporary)

            check(temporary.length() == source.length()) {
                "גודל העותק אינו תואם למקור"
            }

            validateDatabase(temporary)

            backup.delete()

            val hadDestination = destination.exists()

            if (hadDestination) {
                check(destination.renameTo(backup)) {
                    "לא ניתן לגבות את המאגר הקודם ביעד"
                }
            }

            if (!temporary.renameTo(destination)) {
                if (hadDestination && backup.exists()) {
                    backup.renameTo(destination)
                }

                error("לא ניתן להתקין את המאגר במיקום החדש")
            }

            val selected = storage.selectLocation(locationId)

            if (!selected) {
                destination.delete()

                if (hadDestination && backup.exists()) {
                    backup.renameTo(destination)
                }

                error("לא ניתן לשמור את בחירת מיקום האחסון")
            }

            MigrationResult(
                true,
                "המאגר הועתק ונבדק בהצלחה. המקור נשמר."
            )

        } catch (error: Exception) {
            temporary?.delete()

            MigrationResult(
                false,
                "העברת המאגר נכשלה: ${error.message ?: "שגיאה לא ידועה"}"
            )
        }
    }

    private fun copyFile(source: File, destination: File) {
        FileInputStream(source).use { input ->
            FileOutputStream(destination).use { output ->
                input.copyTo(output, bufferSize = 1024 * 1024)
                output.fd.sync()
            }
        }
    }

    private fun validateDatabase(file: File) {
        require(file.exists() && file.length() > 100L) {
            "קובץ המאגר ריק או לא תקין"
        }

        FileInputStream(file).use { input ->
            val header = ByteArray(16)

            require(input.read(header) == 16) {
                "לא ניתן לקרוא את כותרת המאגר"
            }

            val expected = "SQLite format 3\u0000".toByteArray(
                Charsets.US_ASCII
            )

            require(header.contentEquals(expected)) {
                "הקובץ אינו מסד נתונים SQLite"
            }
        }

        val database = SQLiteDatabase.openDatabase(
            file.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY
        )

        try {
            database.rawQuery("PRAGMA integrity_check", null).use {
                cursor ->
                require(cursor.moveToFirst() && cursor.getString(0) == "ok") {
                    "בדיקת תקינות SQLite נכשלה"
                }
            }

            val requiredTables = listOf(
                "agency",
                "routes",
                "trips",
                "stops",
                "stop_times",
                "calendar",
                "route_patterns",
                "route_pattern_stops",
                "trip_patterns"
            )

            for (table in requiredTables) {
                database.rawQuery(
                    """
                    SELECT name FROM sqlite_master
                    WHERE type = 'table' AND name = ?
                    """.trimIndent(),
                    arrayOf(table)
                ).use { cursor ->
                    require(cursor.moveToFirst()) {
                        "חסרה טבלה חיונית: $table"
                    }
                }
            }
        } finally {
            database.close()
        }
    }
}
