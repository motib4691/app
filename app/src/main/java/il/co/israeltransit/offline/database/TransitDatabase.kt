
package il.co.israeltransit.offline.database

import android.content.Context
import android.net.Uri
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.PushbackInputStream
import java.util.zip.GZIPInputStream

class TransitDatabase(context: Context) {

    private val appContext = context.applicationContext

    private val databaseDirectory =
        File(appContext.filesDir, "transit")

    private val databaseFile =
        File(databaseDirectory, "israel-transit.sqlite")

    private val temporaryFile =
        File(databaseDirectory, "israel-transit.tmp")

    private val backupFile =
        File(databaseDirectory, "israel-transit.backup")

    data class ImportResult(
        val success: Boolean,
        val message: String
    )

    init {
        databaseDirectory.mkdirs()
    }

    @Synchronized
    fun importDatabase(uri: Uri): ImportResult {
        return try {
            databaseDirectory.mkdirs()
            temporaryFile.delete()

            appContext.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) {
                    "לא ניתן לקרוא את הקובץ שנבחר"
                }

                PushbackInputStream(input, 2).use { pushback ->
                    val signature = ByteArray(2)
                    val count = pushback.read(signature)

                    if (count > 0) {
                        pushback.unread(signature, 0, count)
                    }

                    val isGzip =
                        count == 2 &&
                        signature[0] == 0x1f.toByte() &&
                        signature[1] == 0x8b.toByte()

                    val decodedInput =
                        if (isGzip) GZIPInputStream(pushback)
                        else pushback

                    decodedInput.use { source ->
                        FileOutputStream(temporaryFile).use { output ->
                            source.copyTo(output)
                            output.fd.sync()
                        }
                    }
                }
            }

            validateDatabase(temporaryFile)

            backupFile.delete()

            if (databaseFile.exists()) {
                check(databaseFile.renameTo(backupFile)) {
                    "לא ניתן ליצור גיבוי של המאגר הקודם"
                }
            }

            val installed = temporaryFile.renameTo(databaseFile)

            if (!installed) {
                if (backupFile.exists()) {
                    backupFile.renameTo(databaseFile)
                }

                error("לא ניתן להתקין את המאגר החדש")
            }

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
        require(file.exists() && file.length() > 100) {
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
                require(cursor.moveToFirst()) {
                    "לא ניתן לבדוק את תקינות המאגר"
                }

                require(cursor.getString(0) == "ok") {
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
                require(cursor.moveToFirst()) {
                    "לא ניתן לבדוק שיוך נסיעות למסלולים"
                }

                require(cursor.getLong(0) == 0L) {
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
                require(cursor.moveToFirst()) {
                    "לא ניתן לבדוק שיוכי מסלולים"
                }

                require(cursor.getLong(0) == 0L) {
                    "נמצאו שיוכי מסלולים ללא נסיעה מקורית"
                }
            }

        } finally {
            database.close()
        }
    }

    @Synchronized
    fun isDatabaseAvailable(): Boolean {
        return databaseFile.exists() && databaseFile.length() > 100
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
        return if (databaseFile.exists()) {
            databaseFile.lastModified()
        } else {
            0L
        }
    }

    @Synchronized
    fun openReadOnly(): SQLiteDatabase? {
        if (!isDatabaseAvailable()) return null

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
