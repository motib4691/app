
package il.co.israeltransit.offline.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.PushbackInputStream
import java.util.zip.GZIPInputStream

class TransitDatabase(context: Context) {

    private val appContext = context.applicationContext
    private val storage = TransitStorage(appContext)

    private val databaseDirectory: File
        get() = storage.getSelectedDirectory()

    private val databaseFile: File
        get() = File(databaseDirectory, DATABASE_NAME)

    private val temporaryFile: File
        get() = File(databaseDirectory, TEMPORARY_NAME)

    private val backupFile: File
        get() = File(databaseDirectory, BACKUP_NAME)

    data class ImportResult(
        val success: Boolean,
        val message: String
    )

    companion object {
        private const val DATABASE_NAME = "israel-transit.sqlite"
        private const val TEMPORARY_NAME = "israel-transit.tmp"
        private const val BACKUP_NAME = "israel-transit.backup"
        private const val MINIMUM_DATABASE_SIZE = 100L
    }

    init {
        databaseDirectory.mkdirs()
    }

    @Synchronized
    fun importDatabase(uri: Uri): ImportResult {
        return try {
            check(
                databaseDirectory.exists() ||
                    databaseDirectory.mkdirs()
            ) {
                "לא ניתן ליצור את תיקיית מסד הנתונים"
            }

            temporaryFile.delete()

            val input = appContext.contentResolver.openInputStream(uri)
                ?: error("לא ניתן לקרוא את הקובץ שנבחר")

            input.use { sourceInput ->
                PushbackInputStream(sourceInput, 2).use { pushback ->
                    val signature = ByteArray(2)
                    val count = pushback.read(signature)

                    if (count > 0) {
                        pushback.unread(signature, 0, count)
                    }

                    val isGzip =
                        count == 2 &&
                            signature[0] == 0x1f.toByte() &&
                            signature[1] == 0x8b.toByte()

                    val decodedInput = if (isGzip) {
                        GZIPInputStream(pushback)
                    } else {
                        pushback
                    }

                    decodedInput.use { decoded ->
                        FileOutputStream(temporaryFile).use { output ->
                            decoded.copyTo(output, bufferSize = 1024 * 1024)
                            output.fd.sync()
                        }
                    }
                }
            }

            validateDatabase(temporaryFile)

            backupFile.delete()

            val hadPreviousDatabase = databaseFile.exists()

            if (hadPreviousDatabase) {
                check(databaseFile.renameTo(backupFile)) {
                    "לא ניתן ליצור גיבוי של המאגר הקודם"
                }
            }

            if (!temporaryFile.renameTo(databaseFile)) {
                if (hadPreviousDatabase && backupFile.exists()) {
                    backupFile.renameTo(databaseFile)
                }

                error("לא ניתן להתקין את המאגר החדש")
            }

            try {
                validateDatabase(databaseFile)
            } catch (e: Exception) {
                databaseFile.delete()

                if (hadPreviousDatabase && backupFile.exists()) {
                    backupFile.renameTo(databaseFile)
                }

                throw e
            }

            // The new database is valid and installed.
            // The previous backup is no longer needed.
            backupFile.delete()

            ImportResult(
                success = true,
                message = "מאגר הנתונים הותקן בהצלחה"
            )

        } catch (e: Exception) {
            temporaryFile.delete()

            if (!databaseFile.exists() && backupFile.exists()) {
                backupFile.renameTo(databaseFile)
            }

            ImportResult(
                success = false,
                message = "ייבוא המאגר נכשל: ${e.message ?: "שגיאה לא ידועה"}"
            )
        }
    }

    private fun validateDatabase(file: File) {
        require(
            file.exists() &&
                file.length() > MINIMUM_DATABASE_SIZE
        ) {
            "הקובץ ריק או קטן מדי"
        }

        FileInputStream(file).use { input ->
            val header = ByteArray(16)

            require(input.read(header) == 16) {
                "קובץ SQLite לא תקין"
            }

            val expected = "SQLite format 3\u0000".toByteArray(
                Charsets.US_ASCII
            )

            require(header.contentEquals(expected)) {
                "הקובץ אינו מסד נתונים SQLite תקין"
            }
        }

        val database = SQLiteDatabase.openDatabase(
            file.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY
        )

        try {
            database.rawQuery(
                "PRAGMA integrity_check",
                null
            ).use { cursor ->
                require(
                    cursor.moveToFirst() &&
                        cursor.getString(0) == "ok"
                ) {
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
                    SELECT name
                    FROM sqlite_master
                    WHERE type = 'table' AND name = ?
                    """.trimIndent(),
                    arrayOf(table)
                ).use { cursor ->
                    require(cursor.moveToFirst()) {
                        "חסרה טבלה חיונית: $table"
                    }
                }
            }

            fun countRows(table: String): Long {
                database.rawQuery(
                    "SELECT COUNT(*) FROM $table",
                    null
                ).use { cursor ->
                    require(cursor.moveToFirst()) {
                        "לא ניתן לבדוק את הטבלה $table"
                    }

                    return cursor.getLong(0)
                }
            }

            val tripCount = countRows("trips")
            val patternCount = countRows("route_patterns")
            val patternStopCount = countRows("route_pattern_stops")
            val tripPatternCount = countRows("trip_patterns")

            require(tripCount > 0) {
                "טבלת הנסיעות ריקה"
            }

            require(patternCount > 0 && patternStopCount > 0) {
                "טבלאות המסלולים ריקות"
            }

            require(tripPatternCount == tripCount) {
                "מספר שיוכי הנסיעות למסלולים אינו תואם למספר הנסיעות"
            }

            database.rawQuery(
                """
                SELECT COUNT(*)
                FROM trips t
                LEFT JOIN trip_patterns tp
                    ON tp.trip_id = t.trip_id
                WHERE tp.trip_id IS NULL
                """.trimIndent(),
                null
            ).use { cursor ->
                require(cursor.moveToFirst() && cursor.getLong(0) == 0L) {
                    "קיימות נסיעות ללא שיוך למסלול"
                }
            }

            database.rawQuery(
                """
                SELECT COUNT(*)
                FROM trip_patterns tp
                LEFT JOIN trips t
                    ON t.trip_id = tp.trip_id
                WHERE t.trip_id IS NULL
                """.trimIndent(),
                null
            ).use { cursor ->
                require(cursor.moveToFirst() && cursor.getLong(0) == 0L) {
                    "נמצאו שיוכי מסלולים ללא נסיעה מקורית"
                }
            }

        } finally {
            database.close()
        }
    }

    @Synchronized
    fun isDatabaseAvailable(): Boolean {
        return databaseFile.exists() &&
            databaseFile.length() > MINIMUM_DATABASE_SIZE
    }

    @Synchronized
    fun getDatabasePath(): String? {
        return if (isDatabaseAvailable()) {
            databaseFile.absolutePath
        } else {
            null
        }
    }

    @Synchronized
    fun getLastUpdateDate(): Long {
        return if (isDatabaseAvailable()) {
            databaseFile.lastModified()
        } else {
            0L
        }
    }

    @Synchronized
    fun openReadOnly(): SQLiteDatabase? {
        if (!isDatabaseAvailable()) {
            return null
        }

        return try {
            SQLiteDatabase.openDatabase(
                databaseFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY
            )
        } catch (_: Exception) {
            null
        }
    }
}
