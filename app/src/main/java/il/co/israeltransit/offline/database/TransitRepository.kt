package il.co.israeltransit.offline.database

import android.database.sqlite.SQLiteDatabase
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class TransitRepository(
    private val databaseProvider: TransitDatabase
) {

    data class TransitLine(
        val routeId: String,
        val lineNumber: String,
        val name: String,
        val agencyId: String,
        val agencyName: String,
        val routeType: String
    )

    data class TransitStop(
        val stopId: String,
        val name: String,
        val latitude: Double?,
        val longitude: Double?,
        val stopCode: String = stopId
    )

    data class RouteStop(
        val stopId: String,
        val name: String,
        val sequence: Int,
        val arrivalTime: Int?,
        val departureTime: Int?,
        val stopCode: String = stopId
    )

    private fun queryLines(
        sql: String,
        args: Array<String>
    ): List<TransitLine> {
        val db = databaseProvider.openReadOnly() ?: return emptyList()

        return try {
            db.rawQuery(sql, args).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            TransitLine(
                                routeId = cursor.getString(0) ?: "",
                                lineNumber = cursor.getString(1) ?: "",
                                name = cursor.getString(2) ?: "",
                                agencyId = cursor.getString(3) ?: "",
                                agencyName = cursor.getString(4) ?: "",
                                routeType = cursor.getString(5) ?: ""
                            )
                        )
                    }
                }
            }
        } finally {
            db.close()
        }
    }

    fun searchLines(
        searchText: String,
        limit: Int = 100
    ): List<TransitLine> {
        val query = searchText.trim()
        if (query.isEmpty()) return emptyList()

        return queryLines(
            """
            SELECT
                r.route_id,
                COALESCE(r.route_short_name, ''),
                COALESCE(r.route_long_name, ''),
                COALESCE(r.agency_id, ''),
                COALESCE(a.agency_name, ''),
                COALESCE(r.route_type, '')
            FROM routes r
            LEFT JOIN agency a ON a.agency_id = r.agency_id
            WHERE
                r.route_short_name LIKE ?
                OR r.route_long_name LIKE ?
            ORDER BY
                CASE WHEN r.route_short_name = ? THEN 0 ELSE 1 END,
                r.route_short_name
            LIMIT ?
            """.trimIndent(),
            arrayOf(
                "$query%",
                "%$query%",
                query,
                limit.coerceIn(1, 500).toString()
            )
        )
    }

    fun getLine(routeId: String): TransitLine? {
        return queryLines(
            """
            SELECT
                r.route_id,
                COALESCE(r.route_short_name, ''),
                COALESCE(r.route_long_name, ''),
                COALESCE(r.agency_id, ''),
                COALESCE(a.agency_name, ''),
                COALESCE(r.route_type, '')
            FROM routes r
            LEFT JOIN agency a ON a.agency_id = r.agency_id
            WHERE r.route_id = ?
            LIMIT 1
            """.trimIndent(),
            arrayOf(routeId)
        ).firstOrNull()
    }

    /**
     * חיפוש תחנות לפי שם או לפי מספר התחנה האמיתי מהשלט (stop_code)
     */
    fun searchStops(
        searchText: String,
        limit: Int = 100
    ): List<TransitStop> {
        val query = searchText.trim()
        if (query.isEmpty()) return emptyList()

        val db = databaseProvider.openReadOnly() ?: return emptyList()

        return try {
            db.rawQuery(
                """
                SELECT
                    stop_id,
                    COALESCE(NULLIF(stop_code, ''), stop_id) AS real_stop_code,
                    COALESCE(stop_name, ''),
                    stop_lat,
                    stop_lon
                FROM stops
                WHERE stop_name LIKE ? OR stop_code LIKE ? OR stop_id LIKE ?
                ORDER BY
                    CASE
                        WHEN stop_code = ? THEN 0
                        WHEN stop_id = ? THEN 1
                        WHEN stop_name LIKE ? THEN 2
                        ELSE 3
                    END,
                    stop_name
                LIMIT ?
                """.trimIndent(),
                arrayOf(
                    "%$query%",
                    "$query%",
                    "$query%",
                    query,
                    query,
                    "$query%",
                    limit.coerceIn(1, 500).toString()
                )
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            TransitStop(
                                stopId = cursor.getString(0) ?: "",
                                stopCode = cursor.getString(1) ?: "",
                                name = cursor.getString(2) ?: "",
                                latitude = if (cursor.isNull(3)) null else cursor.getDouble(3),
                                longitude = if (cursor.isNull(4)) null else cursor.getDouble(4)
                            )
                        )
                    }
                }
            }
        } finally {
            db.close()
        }
    }

    /**
     * איתור התחנה הקרובה ביותר ב-GPS כולל מספר התחנה האמיתי מהשלט
     */
    fun findNearestStop(
        latitude: Double,
        longitude: Double,
        maxDistanceMeters: Double = 3000.0
    ): TransitStop? {
        val db = databaseProvider.openReadOnly() ?: return null
        val deltaLat = maxDistanceMeters / 111000.0
        val cosLat = cos(Math.toRadians(latitude)).coerceAtLeast(0.1)
        val deltaLon = maxDistanceMeters / (111000.0 * cosLat)

        return try {
            db.rawQuery(
                """
                SELECT
                    stop_id,
                    COALESCE(NULLIF(stop_code, ''), stop_id) AS real_stop_code,
                    COALESCE(stop_name, ''),
                    stop_lat,
                    stop_lon
                FROM stops
                WHERE stop_lat BETWEEN ? AND ?
                  AND stop_lon BETWEEN ? AND ?
                LIMIT 60
                """.trimIndent(),
                arrayOf(
                    (latitude - deltaLat).toString(),
                    (latitude + deltaLat).toString(),
                    (longitude - deltaLon).toString(),
                    (longitude + deltaLon).toString()
                )
            ).use { cursor ->
                var closestStop: TransitStop? = null
                var minDistance = Double.MAX_VALUE

                while (cursor.moveToNext()) {
                    if (cursor.isNull(3) || cursor.isNull(4)) continue
                    val sLat = cursor.getDouble(3)
                    val sLon = cursor.getDouble(4)
                    val dist = haversineMeters(latitude, longitude, sLat, sLon)
                    if (dist < minDistance && dist <= maxDistanceMeters) {
                        minDistance = dist
                        closestStop = TransitStop(
                            stopId = cursor.getString(0) ?: "",
                            stopCode = cursor.getString(1) ?: "",
                            name = cursor.getString(2) ?: "",
                            latitude = sLat,
                            longitude = sLon
                        )
                    }
                }
                closestStop
            }
        } finally {
            db.close()
        }
    }

    private fun haversineMeters(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double
    ): Double {
        val earthRadius = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).let { it * it } +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).let { it * it }
        val c = 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
        return earthRadius * c
    }

    fun getStopsForLine(
        routeId: String,
        limit: Int = 500
    ): List<RouteStop> {
        val db = databaseProvider.openReadOnly() ?: return emptyList()

        return try {
            db.rawQuery(
                """
                SELECT
                    s.stop_id,
                    COALESCE(s.stop_name, ''),
                    st.stop_sequence,
                    st.arrival_time,
                    st.departure_time,
                    COALESCE(NULLIF(s.stop_code, ''), s.stop_id) AS real_stop_code
                FROM trips t
                JOIN stop_times st ON st.trip_id = t.trip_id
                JOIN stops s ON s.stop_id = st.stop_id
                WHERE t.route_id = ?
                  AND t.trip_id = (
                      SELECT trip_id
                      FROM trips
                      WHERE route_id = ?
                      ORDER BY trip_id
                      LIMIT 1
                  )
                ORDER BY st.stop_sequence
                LIMIT ?
                """.trimIndent(),
                arrayOf(
                    routeId,
                    routeId,
                    limit.coerceIn(1, 2000).toString()
                )
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            RouteStop(
                                stopId = cursor.getString(0) ?: "",
                                name = cursor.getString(1) ?: "",
                                sequence = cursor.getInt(2),
                                arrivalTime = if (cursor.isNull(3)) null else cursor.getInt(3),
                                departureTime = if (cursor.isNull(4)) null else cursor.getInt(4),
                                stopCode = cursor.getString(5) ?: ""
                            )
                        )
                    }
                }
            }
        } finally {
            db.close()
        }
    }

    fun getLinesForStop(
        stopId: String,
        limit: Int = 100
    ): List<TransitLine> {
        val db = databaseProvider.openReadOnly() ?: return emptyList()

        return try {
            db.rawQuery(
                """
                SELECT DISTINCT
                    r.route_id,
                    COALESCE(r.route_short_name, ''),
                    COALESCE(r.route_long_name, ''),
                    COALESCE(r.agency_id, ''),
                    COALESCE(a.agency_name, ''),
                    COALESCE(r.route_type, '')
                FROM stop_times st
                JOIN trips t ON t.trip_id = st.trip_id
                JOIN routes r ON r.route_id = t.route_id
                LEFT JOIN agency a ON a.agency_id = r.agency_id
                WHERE st.stop_id = ?
                ORDER BY r.route_short_name
                LIMIT ?
                """.trimIndent(),
                arrayOf(
                    stopId,
                    limit.coerceIn(1, 500).toString()
                )
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            TransitLine(
                                routeId = cursor.getString(0) ?: "",
                                lineNumber = cursor.getString(1) ?: "",
                                name = cursor.getString(2) ?: "",
                                agencyId = cursor.getString(3) ?: "",
                                agencyName = cursor.getString(4) ?: "",
                                routeType = cursor.getString(5) ?: ""
                            )
                        )
                    }
                }
            }
        } finally {
            db.close()
        }
    }
}
