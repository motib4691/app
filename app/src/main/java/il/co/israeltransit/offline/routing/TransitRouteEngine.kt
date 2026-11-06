
package il.co.israeltransit.offline.routing

import il.co.israeltransit.offline.database.TransitDatabase
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * מנוע חישוב מסלולים בתחבורה ציבורית.
 *
 * מבוסס על Route Patterns ועל לוחות הזמנים המקוריים של GTFS.
 * תומך בנסיעות ישירות ובהחלפות בין קווים.
 */
class TransitRouteEngine(
    private val databaseProvider: TransitDatabase
) {

    data class RouteLeg(
        val tripId: String,
        val routeId: String,
        val lineNumber: String,
        val lineName: String,
        val agencyName: String,
        val headsign: String,
        val fromStopId: String,
        val fromStopName: String,
        val toStopId: String,
        val toStopName: String,
        val departureAt: LocalDateTime,
        val arrivalAt: LocalDateTime
    )

    data class Journey(
        val legs: List<RouteLeg>,
        val departureAt: LocalDateTime,
        val arrivalAt: LocalDateTime,
        val durationMinutes: Long,
        val transfers: Int
    )

    data class SearchResult(
        val journeys: List<Journey>,
        val error: String? = null
    )

    private data class Pattern(
        val id: Int,
        val routeId: String,
        val stops: List<String>
    )

    private data class BoardPosition(
        val patternId: Int,
        val index: Int
    )

    private data class TripChoice(
        val tripId: String,
        val departureSeconds: Int,
        val serviceDate: LocalDate,
        val routeId: String,
        val lineNumber: String,
        val lineName: String,
        val agencyName: String,
        val headsign: String
    ) {
        val departureAt: LocalDateTime
            get() = serviceDate.atStartOfDay()
                .plusSeconds(departureSeconds.toLong())
    }

    private data class TripStopTime(
        val stopId: String,
        val stopName: String,
        val arrivalSeconds: Int?,
        val departureSeconds: Int?
    )

    private data class Label(
        val arrivalAt: LocalDateTime,
        val previous: Label?,
        val leg: RouteLeg?
    )

    private val weekdayColumns = listOf(
        "monday",
        "tuesday",
        "wednesday",
        "thursday",
        "friday",
        "saturday",
        "sunday"
    )

    private fun weekdayColumn(date: LocalDate): String {
        return weekdayColumns[date.dayOfWeek.value - 1]
    }

    private fun dateKey(date: LocalDate): String {
        return date.toString().replace("-", "")
    }

    /**
     * מחפש מסלולים בין שתי תחנות.
     *
     * maxTransfers הוא מספר ההחלפות המרבי.
     * ברירת המחדל: עד 3 החלפות.
     */
    fun findJourneys(
        originStopId: String,
        destinationStopId: String,
        departureTime: LocalDateTime = LocalDateTime.now(),
        maxTransfers: Int = 3,
        maxResults: Int = 5
    ): SearchResult {

        if (originStopId.isBlank() || destinationStopId.isBlank()) {
            return SearchResult(
                emptyList(),
                "יש לבחור תחנת מוצא ותחנת יעד."
            )
        }

        if (originStopId == destinationStopId) {
            return SearchResult(
                emptyList(),
                "תחנת המוצא ותחנת היעד זהות."
            )
        }

        if (maxTransfers !in 0..5) {
            return SearchResult(
                emptyList(),
                "מספר ההחלפות המבוקש אינו תקין."
            )
        }

        val db = databaseProvider.openReadOnly()
            ?: return SearchResult(
                emptyList(),
                "לא נמצא מסד נתונים. יש לייבא תחילה את קובץ הנתונים."
            )

        return try {
            val patterns = loadPatterns(db)

            if (patterns.first.isEmpty()) {
                return SearchResult(
                    emptyList(),
                    "לא נמצאו נתוני מסלולים. יש לייבא קובץ נתונים מעודכן."
                )
            }

            val patternsById = patterns.first
            val positionsByStop = patterns.second

            if (!positionsByStop.containsKey(originStopId)) {
                return SearchResult(
                    emptyList(),
                    "תחנת המוצא אינה מופיעה במסלולי התחבורה."
                )
            }

            if (!positionsByStop.containsKey(destinationStopId)) {
                return SearchResult(
                    emptyList(),
                    "תחנת היעד אינה מופיעה במסלולי התחבורה."
                )
            }

            calculateJourneys(
                db = db,
                patternsById = patternsById,
                positionsByStop = positionsByStop,
                originStopId = originStopId,
                destinationStopId = destinationStopId,
                departureTime = departureTime,
                maxTransfers = maxTransfers,
                maxResults = maxResults
            )

        } catch (e: Exception) {
            SearchResult(
                emptyList(),
                "אירעה שגיאה בחישוב המסלול: ${e.message ?: "שגיאה לא ידועה"}"
            )
        } finally {
            db.close()
        }
    }

    /**
     * טוען את כל המסלולים ואת רשימות התחנות שלהם.
     */
    private fun loadPatterns(
        db: android.database.sqlite.SQLiteDatabase
    ): Pair<
        Map<Int, Pattern>,
        Map<String, List<BoardPosition>>
    > {

        val routeIds = mutableMapOf<Int, String>()
        val stopsByPattern = mutableMapOf<Int, MutableList<String>>()

        db.rawQuery(
            """
            SELECT pattern_id, route_id
            FROM route_patterns
            """.trimIndent(),
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                routeIds[cursor.getInt(0)] =
                    cursor.getString(1) ?: ""
            }
        }

        db.rawQuery(
            """
            SELECT pattern_id, stop_id
            FROM route_pattern_stops
            ORDER BY pattern_id, stop_sequence
            """.trimIndent(),
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val patternId = cursor.getInt(0)
                val stopId = cursor.getString(1) ?: ""

                stopsByPattern
                    .getOrPut(patternId) { mutableListOf() }
                    .add(stopId)
            }
        }

        val patterns = mutableMapOf<Int, Pattern>()
        val positionsByStop =
            mutableMapOf<String, MutableList<BoardPosition>>()

        for ((patternId, stopIds) in stopsByPattern) {
            val routeId = routeIds[patternId] ?: continue

            patterns[patternId] = Pattern(
                id = patternId,
                routeId = routeId,
                stops = stopIds
            )

            stopIds.forEachIndexed { index, stopId ->
                positionsByStop
                    .getOrPut(stopId) { mutableListOf() }
                    .add(
                        BoardPosition(
                            patternId = patternId,
                            index = index
                        )
                    )
            }
        }

        return patterns to positionsByStop
    }

    /**
     * אלגוריתם חישוב בסבבים:
     * בכל סבב מתווספת לכל היותר נסיעה אחת למסלול.
     */
    private fun calculateJourneys(
        db: android.database.sqlite.SQLiteDatabase,
        patternsById: Map<Int, Pattern>,
        positionsByStop: Map<String, List<BoardPosition>>,
        originStopId: String,
        destinationStopId: String,
        departureTime: LocalDateTime,
        maxTransfers: Int,
        maxResults: Int
    ): SearchResult {

        var labels = mutableMapOf<String, Label>()

        labels[originStopId] = Label(
            arrivalAt = departureTime,
            previous = null,
            leg = null
        )

        val foundJourneys = mutableListOf<Journey>()

        val maximumRounds = maxTransfers + 1

        for (round in 1..maximumRounds) {

            val previousLabels = labels
            val nextLabels = previousLabels.toMutableMap()

            val patternsToScan = previousLabels.keys
                .flatMap { positionsByStop[it].orEmpty() }
                .map { it.patternId }
                .toSet()

            var changed = false

            for (patternId in patternsToScan) {

                val pattern = patternsById[patternId] ?: continue

                for (boardIndex in pattern.stops.indices) {

                    val boardStopId = pattern.stops[boardIndex]
                    val boardLabel = previousLabels[boardStopId]
                        ?: continue

                    val choice = findEarliestTrip(
                        db = db,
                        patternId = patternId,
                        boardIndex = boardIndex,
                        stopId = boardStopId,
                        earliestDeparture = boardLabel.arrivalAt
                    ) ?: continue

                    val tripStops = loadTripStops(
                        db = db,
                        tripId = choice.tripId
                    )

                    if (tripStops.size != pattern.stops.size) {
                        continue
                    }

                    val matchesPattern = tripStops.indices.all { index ->
                        tripStops[index].stopId == pattern.stops[index]
                    }

                    if (!matchesPattern) {
                        continue
                    }

                    val actualDepartureSeconds =
                        tripStops[boardIndex].departureSeconds
                            ?: tripStops[boardIndex].arrivalSeconds
                            ?: continue

                    val actualDepartureAt =
                        choice.serviceDate.atStartOfDay()
                            .plusSeconds(actualDepartureSeconds.toLong())

                    if (actualDepartureAt.isBefore(boardLabel.arrivalAt)) {
                        continue
                    }

                    for (alightIndex in (boardIndex + 1) until tripStops.size) {

                        val alightStop = tripStops[alightIndex]

                        val arrivalSeconds =
                            alightStop.arrivalSeconds
                                ?: alightStop.departureSeconds
                                ?: continue

                        val arrivalAt =
                            choice.serviceDate.atStartOfDay()
                                .plusSeconds(arrivalSeconds.toLong())

                        if (arrivalAt.isBefore(actualDepartureAt)) {
                            continue
                        }

                        if (Duration.between(
                                departureTime,
                                arrivalAt
                            ).toMinutes() > 720
                        ) {
                            continue
                        }

                        val oldLabel = nextLabels[alightStop.stopId]

                        if (oldLabel != null &&
                            !arrivalAt.isBefore(oldLabel.arrivalAt)
                        ) {
                            continue
                        }

                        val leg = RouteLeg(
                            tripId = choice.tripId,
                            routeId = choice.routeId,
                            lineNumber = choice.lineNumber,
                            lineName = choice.lineName,
                            agencyName = choice.agencyName,
                            headsign = choice.headsign,
                            fromStopId = boardStopId,
                            fromStopName = tripStops[boardIndex].stopName,
                            toStopId = alightStop.stopId,
                            toStopName = alightStop.stopName,
                            departureAt = actualDepartureAt,
                            arrivalAt = arrivalAt
                        )

                        nextLabels[alightStop.stopId] = Label(
                            arrivalAt = arrivalAt,
                            previous = boardLabel,
                            leg = leg
                        )

                        changed = true
                    }
                }
            }

            labels = nextLabels

            val destinationLabel = labels[destinationStopId]

            if (destinationLabel != null) {
                val journey = buildJourney(
                    destinationLabel
                )

                if (journey.legs.isNotEmpty()) {
                    foundJourneys.add(journey)
                }
            }

            if (!changed) {
                break
            }
        }

        val journeys = foundJourneys
            .distinctBy { journey ->
                journey.legs.joinToString("|") { it.tripId }
            }
            .sortedWith(
                compareBy<Journey> { it.arrivalAt }
                    .thenBy { it.transfers }
            )
            .take(maxResults.coerceIn(1, 20))

        return if (journeys.isEmpty()) {
            SearchResult(
                emptyList(),
                "לא נמצא מסלול מתאים במועד המבוקש."
            )
        } else {
            SearchResult(journeys)
        }
    }

    /**
     * מוצא את הנסיעה המוקדמת ביותר שאפשר לעלות עליה
     * בתחנה ובמיקום המסוים במסלול.
     *
     * נבדק גם שירות שהחל ביום הקודם,
     * עבור נסיעות שממשיכות אחרי חצות.
     */
    private fun findEarliestTrip(
        db: android.database.sqlite.SQLiteDatabase,
        patternId: Int,
        boardIndex: Int,
        stopId: String,
        earliestDeparture: LocalDateTime
    ): TripChoice? {

        val serviceDates = listOf(
            earliestDeparture.toLocalDate(),
            earliestDeparture.toLocalDate().minusDays(1)
        )

        var best: TripChoice? = null

        for (serviceDate in serviceDates) {

            val secondsFromServiceStart =
                Duration.between(
                    serviceDate.atStartOfDay(),
                    earliestDeparture
                ).seconds

            if (secondsFromServiceStart < 0 ||
                secondsFromServiceStart > Int.MAX_VALUE
            ) {
                continue
            }

            val weekday = weekdayColumn(serviceDate)
            val date = dateKey(serviceDate)

            val sql = """
                SELECT
                    tp.trip_id,
                    st.departure_time,
                    r.route_id,
                    COALESCE(r.route_short_name, ''),
                    COALESCE(r.route_long_name, ''),
                    COALESCE(a.agency_name, ''),
                    COALESCE(t.trip_headsign, '')
                FROM trip_patterns tp
                JOIN stop_times st
                    ON st.trip_id = tp.trip_id
                JOIN route_pattern_stops ps
                    ON ps.pattern_id = tp.pattern_id
                   AND ps.stop_id = st.stop_id
                JOIN trips t
                    ON t.trip_id = tp.trip_id
                JOIN routes r
                    ON r.route_id = t.route_id
                LEFT JOIN agency a
                    ON a.agency_id = r.agency_id
                JOIN calendar c
                    ON c.service_id = tp.service_id
                WHERE tp.pattern_id = ?
                  AND ps.stop_sequence = ?
                  AND st.stop_id = ?
                  AND st.departure_time >= ?
                  AND c.start_date <= ?
                  AND c.end_date >= ?
                  AND c.$weekday = '1'
                ORDER BY st.departure_time ASC
                LIMIT 1
            """.trimIndent()

            db.rawQuery(
                sql,
                arrayOf(
                    patternId.toString(),
                    (boardIndex + 1).toString(),
                    stopId,
                    secondsFromServiceStart.toString(),
                    date,
                    date
                )
            ).use { cursor ->

                if (cursor.moveToFirst()) {

                    val candidate = TripChoice(
                        tripId = cursor.getString(0) ?: "",
                        departureSeconds = cursor.getInt(1),
                        serviceDate = serviceDate,
                        routeId = cursor.getString(2) ?: "",
                        lineNumber = cursor.getString(3) ?: "",
                        lineName = cursor.getString(4) ?: "",
                        agencyName = cursor.getString(5) ?: "",
                        headsign = cursor.getString(6) ?: ""
                    )

                    if (!candidate.departureAt.isBefore(earliestDeparture)) {
                        if (best == null ||
                            candidate.departureAt.isBefore(best!!.departureAt)
                        ) {
                            best = candidate
                        }
                    }
                }
            }
        }

        return best
    }

    /**
     * טוען את זמני כל התחנות בנסיעה מסוימת.
     */
    private fun loadTripStops(
        db: android.database.sqlite.SQLiteDatabase,
        tripId: String
    ): List<TripStopTime> {

        val result = mutableListOf<TripStopTime>()

        db.rawQuery(
            """
            SELECT
                st.stop_id,
                COALESCE(s.stop_name, ''),
                st.arrival_time,
                st.departure_time
            FROM stop_times st
            LEFT JOIN stops s
                ON s.stop_id = st.stop_id
            WHERE st.trip_id = ?
            ORDER BY st.stop_sequence
            """.trimIndent(),
            arrayOf(tripId)
        ).use { cursor ->

            while (cursor.moveToNext()) {
                result.add(
                    TripStopTime(
                        stopId = cursor.getString(0) ?: "",
                        stopName = cursor.getString(1) ?: "",
                        arrivalSeconds =
                            if (cursor.isNull(2)) null
                            else cursor.getInt(2),
                        departureSeconds =
                            if (cursor.isNull(3)) null
                            else cursor.getInt(3)
                    )
                )
            }
        }

        return result
    }

    /**
     * משחזר את רצף הנסיעות מתחנת היעד בחזרה למוצא.
     */
    private fun buildJourney(
        destinationLabel: Label
    ): Journey {

        val legs = mutableListOf<RouteLeg>()

        var current: Label? = destinationLabel

        while (current != null) {
            current.leg?.let { legs.add(it) }
            current = current.previous
        }

        legs.reverse()

        val first = legs.first()
        val last = legs.last()

        val duration = Duration.between(
            first.departureAt,
            last.arrivalAt
        ).toMinutes()

        return Journey(
            legs = legs,
            departureAt = first.departureAt,
            arrivalAt = last.arrivalAt,
            durationMinutes = duration,
            transfers = (legs.size - 1).coerceAtLeast(0)
        )
    }
}
