package il.co.israeltransit.offline.database

import il.co.israeltransit.offline.ui.OperatorTheme
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
        val routeType: String,
        val originName: String = "",
        val destinationName: String = ""
    )

    data class TransitStop(
        val stopId: String,
        val name: String,
        val latitude: Double?,
        val longitude: Double?,
        val stopCode: String = stopId
    )

    data class DualStopGroup(
        val baseName: String,
        val stop1: TransitStop,
        val stop2: TransitStop? = null
    )

    data class RouteStop(
        val stopId: String,
        val name: String,
        val sequence: Int,
        val arrivalTime: Int?,
        val departureTime: Int?,
        val stopCode: String = stopId
    )

    fun searchLines(searchText: String, limit: Int = 100): List<TransitLine> {
        val query = searchText.trim()
        if (query.isEmpty()) return emptyList()

        val normalized = OperatorTheme.normalizeHebrew(query)
        val db = databaseProvider.openReadOnly() ?: return emptyList()

        return try {
            val sql = """
                SELECT
                    r.route_id,
                    COALESCE(r.route_short_name, ''),
                    COALESCE(r.route_long_name, ''),
                    COALESCE(r.agency_id, ''),
                    COALESCE(a.agency_name, ''),
                    COALESCE(r.route_type, '')
                FROM routes r
                LEFT JOIN agency a ON a.agency_id = r.agency_id
                WHERE r.route_short_name LIKE ? OR r.route_long_name LIKE ?
                ORDER BY
                    CASE WHEN r.route_short_name = ? THEN 0 ELSE 1 END,
                    r.route_short_name
                LIMIT ?
            """.trimIndent()

            db.rawQuery(
                sql,
                arrayOf("$query%", "%$query%", query, limit.coerceIn(1, 500).toString())
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val routeId = cursor.getString(0) ?: ""
                        val shortName = cursor.getString(1) ?: ""
                        val longName = cursor.getString(2) ?: ""
                        val agencyId = cursor.getString(3) ?: ""
                        val agencyName = cursor.getString(4) ?: ""
                        val routeType = cursor.getString(5) ?: ""

                        // חילוץ מוצא ויעד מתוך השם המלא של הקו
                        val parts = longName.split("<->", "⟷", "-", "–")
                        val origin = parts.getOrNull(0)?.trim() ?: ""
                        val dest = parts.getOrNull(1)?.trim() ?: ""

                        add(
                            TransitLine(
                                routeId = routeId,
                                lineNumber = shortName,
                                name = longName,
                                agencyId = agencyId,
                                agencyName = agencyName,
                                routeType = routeType,
                                originName = origin,
                                destinationName = dest
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
     * חיפוש תחנות ורחובות עם נירמול עברי מלא (מתעלם מגרשיים ומרכאות)
     */
    fun searchStops(searchText: String, limit: Int = 100): List<TransitStop> {
        val raw = searchText.trim()
        if (raw.isEmpty()) return emptyList()

        val cleanQuery = raw.replace("[\"'״׳]".toRegex(), "")
        val db = databaseProvider.openReadOnly() ?: return emptyList()

        return try {
            val sql = """
                SELECT
                    stop_id,
                    COALESCE(NULLIF(stop_code, ''), stop_id) AS real_stop_code,
                    COALESCE(stop_name, ''),
                    stop_lat,
                    stop_lon
                FROM stops
                WHERE stop_name LIKE ? OR stop_name LIKE ? OR stop_code LIKE ? OR stop_id LIKE ?
                ORDER BY
                    CASE
                        WHEN stop_code = ? THEN 0
                        WHEN stop_id = ? THEN 1
                        WHEN stop_name LIKE ? THEN 2
                        ELSE 3
                    END,
                    stop_name
                LIMIT ?
            """.trimIndent()

            db.rawQuery(
                sql,
                arrayOf(
                    "%$raw%",
                    "%$cleanQuery%",
                    "$raw%",
                    "$raw%",
                    raw,
                    raw,
                    "$raw%",
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
     * איחוד תחנות נגדיות באותו שם לקבוצה אחת
     */
    fun searchGroupedStops(searchText: String, limit: Int = 50): List<DualStopGroup> {
        val stops = searchStops(searchText, limit * 2)
        val isCodeSearch = searchText.trim().all { it.isDigit() }

        if (isCodeSearch) {
            return stops.map { DualStopGroup(baseName = it.name, stop1 = it, stop2 = null) }
        }

        val grouped = linkedMapOf<String, MutableList<TransitStop>>()
        for (stop in stops) {
            val normName = OperatorTheme.normalizeHebrew(stop.name)
            grouped.getOrPut(normName) { mutableListOf() }.add(stop)
        }

        return grouped.values.map { list ->
            DualStopGroup(
                baseName = list[0].name,
                stop1 = list[0],
                stop2 = list.getOrNull(1)
            )
        }.take(limit)
    }

    /**
     * איתור התחנה הנגדית בעלת אותו השם
     */
    fun findOppositeStop(stop: TransitStop): TransitStop? {
        val db = databaseProvider.openReadOnly() ?: return null
        return try {
            val sql = """
                SELECT
                    stop_id,
                    COALESCE(NULLIF(stop_code, ''), stop_id) AS real_stop_code,
                    COALESCE(stop_name, ''),
                    stop_lat,
                    stop_lon
                FROM stops
                WHERE stop_name = ? AND stop_id != ?
                LIMIT 1
            """.trimIndent()

            db.rawQuery(sql, arrayOf(stop.name, stop.stopId)).use { cursor ->
                if (cursor.moveToFirst()) {
                    TransitStop(
                        stopId = cursor.getString(0) ?: "",
                        stopCode = cursor.getString(1) ?: "",
                        name = cursor.getString(2) ?: "",
                        latitude = if (cursor.isNull(3)) null else cursor.getDouble(3),
                        longitude = if (cursor.isNull(4)) null else cursor.getDouble(4)
                    )
                } else null
            }
        } finally {
            db.close()
        }
    }

    fun findNearestStop(latitude: Double, longitude: Double, maxDistanceMeters: Double = 3000.0): TransitStop? {
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
                WHERE stop_lat BETWEEN ? AND ? AND stop_lon BETWEEN ? AND ?
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

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadius = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).let { it * it } +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).let { it * it }
        val c = 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
        return earthRadius * c
    }

    fun getLinesForStop(stopId: String, limit: Int = 100): List<TransitLine> {
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
                LIMIT ?
                """.trimIndent(),
                arrayOf(stopId, limit.coerceIn(1, 500).toString())
            ).use { cursor ->
                val lines = mutableListOf<TransitLine>()
                while (cursor.moveToNext()) {
                    val longName = cursor.getString(2) ?: ""
                    val parts = longName.split("<->", "⟷", "-", "–")
                    val origin = parts.getOrNull(0)?.trim() ?: ""
                    val dest = parts.getOrNull(1)?.trim() ?: ""

                    lines.add(
                        TransitLine(
                            routeId = cursor.getString(0) ?: "",
                            lineNumber = cursor.getString(1) ?: "",
                            name = longName,
                            agencyId = cursor.getString(3) ?: "",
                            agencyName = cursor.getString(4) ?: "",
                            routeType = cursor.getString(5) ?: "",
                            originName = origin,
                            destinationName = dest
                        )
                    )
                }

                // מיון עירוני תחילה ואז לפי מספר הקו
                lines.sortedWith(
                    compareBy<TransitLine> { line ->
                        val isUrban = line.originName.isNotBlank() && line.destinationName.isNotBlank() &&
                                OperatorTheme.normalizeHebrew(line.originName) == OperatorTheme.normalizeHebrew(line.destinationName)
                        if (isUrban) 0 else 1
                    }.thenBy { it.lineNumber.toIntOrNull() ?: Int.MAX_VALUE }
                     .thenBy { it.lineNumber }
                )
            }
        } finally {
            db.close()
        }
    }
}
