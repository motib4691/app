
package il.co.israeltransit.offline.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.StatFs
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class TransitStorageMigration(context: Context) {

    private val appContext = context.applicationContext
    private val storage = TransitStorage(appContext)

    data class MigrationResult(
        val success: Boolean,
        val message: String
    )

    companion object {
        private const val DATABASE_NAME = "israel-transit.sqlite"
        private const val TEMP_NAME = "israel-transit.migration"
        private const val BACKUP_NAME = "israel-transit.previous"
        private const val EXTRA_SPACE = 50L * 1024L * 1024L
    }

    @Synchronized
    fun moveTo(locationId: String): MigrationResult {
        val locations = storage.availableLocations()

        val sourceLocation = storage.getSelectedLocation()
            ?: return failure("לא ניתן לזהות את מיקום המאגר הנוכחי")

        val targetLocation = locations.firstOrNull {
            it.id == locationId
        } ?: return failure("מיקום האחסון שנבחר אינו זמין")

        val source = File(sourceLocation.directory, DATABASE_NAME)
        val target = File(targetLocation.directory, DATABASE_NAME)
        val temporary = File(targetLocation.directory, TEMP_NAME)
        val backup = File(targetLocation.directory, BACKUP_NAME)

        if (sourceLocation.id == targetLocation.id) {
            return MigrationResult(true, "המאגר כבר נמצא במיקום שנבחר")
        }

        return try {
            if (!source.exists()) {
                if (!target.exists()) {
                    return failure("לא נמצא מאגר נתונים במיקום הנוכחי או החדש")
                }

                validateDatabase(target)

                check(storage.selectLocation(locationId)) {
                    "לא ניתן לשמור את מיקום האחסון החדש"
                }

                return MigrationResult(true, "מיקום האחסון עודכן בהצלחה")
            }

            validateDatabase(source)

            val targetDirectory = targetLocation.directory
            if (!targetDirectory.exists() && !targetDirectory.mkdirs()) {
                return failure("לא ניתן ליצור תיקייה במיקום החדש")
            }

            val availableBytes = StatFs(
                targetDirectory.absolutePath
            ).availableBytes

            if (availableBytes < source.length() + EXTRA_SPACE) {
                return failure("אין מספיק מקום פנוי במיקום החדש")
            }

            temporary.delete()
            backup.delete()

            copyDatabase(source, temporary)
            validateDatabase(temporary)

            check(temporary.length() == source.length()) {
                "גודל המאגר שהועתק אינו תואם למקור"
            }

            val hadTarget = target.exists()

            if (hadTarget) {
                validateDatabase(target)

                check(target.renameTo(backup)) {
                    "לא ניתן לשמור זמנית את המאגר הקיים במיקום החדש"
                }
            }

            if (!temporary.renameTo(target)) {
                if (hadTarget && backup.exists()) {
                    backup.renameTo(target)
                }
                error("לא ניתן להתקין את המאגר במיקום החדש")
            }

            validateDatabase(target)

            check(storage.selectLocation(locationId)) {
                "לא ניתן לבחור את מיקום האחסון החדש"
            }

            // Delete the old database only after the new one
            // has been installed, validated and selected.
            if (!source.delete() || source.exists()) {
                storage.selectLocation(sourceLocation.id)

                target.delete()

                if (hadTarget && backup.exists()) {
                    backup.renameTo(target)
                }

                error("לא ניתן למחוק את המאגר מהמיקום הישן. ההעברה בוטלה.")
            }

            // The old source is gone. Remove any temporary backup.
            if (backup.exists() && !backup.delete()) {
                // The active database is already safe at the new location.
                return MigrationResult(
                    true,
                    "המאגר הועבר בהצלחה והמקור נמחק. נותר קובץ גיבוי זמני במיקום החדש."
                )
            }

            temporary.delete()

            MigrationResult(
                true,
                "המאגר הועבר בהצלחה. המאגר הישן נמחק ונשאר עותק פעיל אחד בלבד."
            )

        } catch (e: Exception) {
            temporary.delete()

            if (!target.exists() && backup.exists()) {
                backup.renameTo(target)
            }

            failure(
                "העברת המאגר נכשלה: ${e.message ?: "שגיאה לא ידועה"}. המאגר המקורי נשמר."
            )
        }
    }

    private fun copyDatabase(source: File, destination: File) {
        FileInputStream(source).use { input ->
            FileOutputStream(destination).use { output ->
                input.copyTo(output, bufferSize = 1024 * 1024)
                output.fd.sync()
            }
        }
    }

    private fun validateDatabase(file: File) {
        require(file.exists() && file.length() > 100L) {
            "קובץ המאגר חסר או קטן מדי"
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
                "הקובץ אינו מסד נתונים SQLite תקין"
            }
        }

        val database = SQLiteDatabase.openDatabase(
            file.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY
        )

        try {
            database.rawQuery("PRAGMA integrity_check", null).use { cursor ->
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

            database.rawQuery(
                """
                SELECT
                    (SELECT COUNT(*) FROM trips),
                    (SELECT COUNT(*) FROM trip_patterns),
                    (
                        SELECT COUNT(*)
                        FROM trips t
                        LEFT JOIN trip_patterns tp
                            ON tp.trip_id = t.trip_id
                        WHERE tp.trip_id IS NULL
                    )
                """.trimIndent(),
                null
            ).use { cursor ->
                require(cursor.moveToFirst()) {
                    "לא ניתן לבדוק את שיוך הנסיעות"
                }

                require(
                    cursor.getLong(0) > 0L &&
                    cursor.getLong(0) == cursor.getLong(1) &&
                    cursor.getLong(2) == 0L
                ) {
                    "שיוך הנסיעות למסלולים אינו תקין"
                }
            }
        } finally {
            database.close()
        }
    }

    private fun failure(message: String) =
        MigrationResult(
            success = false,
            message = message
        )
}
