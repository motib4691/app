package il.co.israeltransit.offline.routing

import android.database.sqlite.SQLiteDatabase
import il.co.israeltransit.offline.database.TransitDatabase
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * מנוע חישוב מסלולים בתחבורה ציבורית.
 *
 * כל סבב מייצג נסיעה אחת באוטובוס/רכבת.
 * נשמרת תווית נפרדת לכל מספר נסיעות, כדי שמסלול
 * עם פחות החלפות לא יימחק בגלל מסלול מהיר יותר.
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
        val routeId: String,
        val lineNumber: String,
        val lineName: String,
        val agencyName: String,
        val headsign: String,
        val serviceDate: LocalDate
    )

    private data class TripStopTime(
        val stopId: String,
        val stopName: String,
        val arrivalSeconds: Int?,
        val departureSeconds: Int?
    )

    /**
     * תווית במסלול.
     *
     * previous ו-leg מאפשרים לשחזר את המסלול המלא.
     * כל תווית שייכת לסבב מסוים, כלומר למספר נסיעות מסוים.
     */
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

    /**
     * זמן מינימלי זמני להחלפה בין שתי נסיעות.
     *
     * בהמשך יוחלף בחישוב זמן הליכה אמיתי בין רציפים
     * ובין תחנות, על בסיס נתוני הליכה.
     */
    private val minimumTransferMinutes = 2L

    private val maximumJourneyMinutes = 720L

    private fun weekdayColumn(date: LocalDate): String {
        return weekdayColumns[date.dayOfWeek.value - 1]
    }

    private fun dateKey(date: LocalDate): String {
        return date.toString().replace("-", "")
    }

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
            val loaded = loadPatterns(db)
            val patternsById = loaded.first
            val positionsByStop = loaded.second

            if (patternsById.isEmpty()) {
                return SearchResult(
                    emptyList(),
                    "לא נמצאו נתוני מסלולים. יש לייבא קובץ נתונים מעודכן."
                )
            }

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
                "אירעה שגיאה בחישוב המסלול: " +
                    (e.message ?: "שגיאה לא ידועה")
            )
        } finally {
            db.close()
        }
    }

    /**
     * טוען את כל המסלולים ואת מיקומי התחנות בהם.
     */
    private fun loadPatterns(
        db: SQLiteDatabase
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
     * מחשב מסלולים בסבבים.
     *
     * סבב ראשון: נסיעה אחת.
     * סבב שני: שתי נסיעות.
     * וכן הלאה.
     *
     * בכל סבב נשמרת ההגעה המוקדמת ביותר לכל תחנה
     * עבור אותו מספר נסיעות בדיוק.
     */
    private fun calculateJourneys(
        db: SQLiteDatabase,
        patternsById: Map<Int, Pattern>,
        positionsByStop: Map<String, List<BoardPosition>>,
        originStopId: String,
        destinationStopId: String,
        departureTime: LocalDateTime,
        maxTransfers: Int,
        maxResults: Int
    ): SearchResult {

        var previousRound = mutableMapOf<String, Label>()

        previousRound[originStopId] = Label(
            arrivalAt = departureTime,
            previous = null,
            leg = null
        )

        val foundJourneys = mutableListOf<Journey>()

        /*
         * מטמונים מקומיים לחיפוש הנוכחי בלבד.
         * הם נמחקים אוטומטית בסיום החיפוש.
         */
        val activeTripsCache =
            mutableMapOf<Pair<Int, LocalDate>, List<TripChoice>>()

        val tripStopsCache =
            mutableMapOf<String, List<TripStopTime>>()

        val patternValidationCache =
            mutableMapOf<String, Boolean>()

        val maximumRounds = maxTransfers + 1

        for (round in 1..maximumRounds) {
            val nextRound = mutableMapOf<String, Label>()

            val patternsToScan = previousRound.keys
                .flatMap { positionsByStop[it].orEmpty() }
                .map { it.patternId }
                .toSet()

            var changed = false

            for (patternId in patternsToScan) {
                val pattern = patternsById[patternId] ?: continue

                for (boardIndex in pattern.stops.indices) {
                    val boardStopId = pattern.stops[boardIndex]
                    val boardLabel = previousRound[boardStopId]
                        ?: continue

                    if (boardIndex >= pattern.stops.lastIndex) {
                        continue
                    }

                    val earliestBoarding = if (boardLabel.leg == null) {
                        boardLabel.arrivalAt
                    } else {
                        boardLabel.arrivalAt.plusMinutes(
                            minimumTransferMinutes
                        )
                    }

                    val serviceDates = listOf(
                        earliestBoarding.toLocalDate(),
                        earliestBoarding.toLocalDate().minusDays(1)
                    )

                    for (serviceDate in serviceDates) {
                        val cacheKey = patternId to serviceDate

                        val activeTrips = activeTripsCache.getOrPut(cacheKey) {
                            loadActiveTrips(
                                db = db,
                                patternId = patternId,
                                serviceDate = serviceDate
                            )
                        }

                        for (choice in activeTrips) {
                            /*
                             * אין צורך לרדת מאותה נסיעה ולעלות עליה שוב
                             * כאילו הייתה החלפה.
                             */
                            if (boardLabel.leg?.tripId == choice.tripId) {
                                continue
                            }

                            val tripStops = tripStopsCache.getOrPut(
                                choice.tripId
                            ) {
                                loadTripStops(
                                    db = db,
                                    tripId = choice.tripId
                                )
                            }

                            if (tripStops.size != pattern.stops.size) {
                                continue
                            }

                            val matchesPattern =
                                patternValidationCache.getOrPut(
                                    choice.tripId
                                ) {
                                    tripStops.indices.all { index ->
                                        tripStops[index].stopId ==
                                            pattern.stops[index]
                                    }
                                }

                            if (!matchesPattern) {
                                continue
                            }

                            /*
                             * בודקים את שעת היציאה לפי המיקום המדויק
                             * של התחנה בנסיעה עצמה.
                             */
                            val boardStop = tripStops[boardIndex]

                            if (boardStop.stopId != boardStopId) {
                                continue
                            }

                            val departureSeconds =
                                boardStop.departureSeconds
                                    ?: boardStop.arrivalSeconds
                                    ?: continue

                            val actualDepartureAt =
                                choice.serviceDate.atStartOfDay()
                                    .plusSeconds(
                                        departureSeconds.toLong()
                                    )

                            if (actualDepartureAt.isBefore(earliestBoarding)) {
                                continue
                            }

                            /*
                             * כל התחנות שאפשר להגיע אליהן באותה נסיעה
                             * נבדקות, ולא רק תחנת היעד הקרובה ביותר.
                             */
                            for (alightIndex in
                                (boardIndex + 1) until tripStops.size
                            ) {
                                val alightStop = tripStops[alightIndex]

                                val arrivalSeconds =
                                    alightStop.arrivalSeconds
                                        ?: alightStop.departureSeconds
                                        ?: continue

                                val arrivalAt =
                                    choice.serviceDate.atStartOfDay()
                                        .plusSeconds(
                                            arrivalSeconds.toLong()
                                        )

                                if (arrivalAt.isBefore(actualDepartureAt)) {
                                    continue
                                }

                                val totalDuration = Duration.between(
                                    departureTime,
                                    arrivalAt
                                ).toMinutes()

                                if (totalDuration < 0 ||
                                    totalDuration > maximumJourneyMinutes
                                ) {
                                    continue
                                }

                                val oldLabel =
                                    nextRound[alightStop.stopId]

                                /*
                                 * באותו סבב, כלומר באותו מספר נסיעות,
                                 * שומרים את זמן ההגעה הטוב ביותר.
                                 */
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
                                    fromStopName = boardStop.stopName,
                                    toStopId = alightStop.stopId,
                                    toStopName = alightStop.stopName,
                                    departureAt = actualDepartureAt,
                                    arrivalAt = arrivalAt
                                )

                                nextRound[alightStop.stopId] = Label(
                                    arrivalAt = arrivalAt,
                                    previous = boardLabel,
                                    leg = leg
                                )

                                changed = true
                            }
                        }
                    }
                }
            }

            val destinationLabel = nextRound[destinationStopId]

            if (destinationLabel != null) {
                val journey = buildJourney(destinationLabel)

                if (journey.legs.isNotEmpty()) {
                    foundJourneys.add(journey)
                }
            }

            if (!changed) {
                break
            }

            previousRound = nextRound
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
     * מחזיר את כל הנסיעות הפעילות של מסלול מסוים
     * בתאריך השירות המבוקש.
     *
     * אין LIMIT 1: כל הנסיעות נבדקות בהמשך לפי זמני
     * התחנות האמיתיים שלהן.
     */
    private fun loadActiveTrips(
        db: SQLiteDatabase,
        patternId: Int,
        serviceDate: LocalDate
    ): List<TripChoice> {
        val result = mutableListOf<TripChoice>()

        val weekday = weekdayColumn(serviceDate)
        val date = dateKey(serviceDate)

        val sql = """
            SELECT
                tp.trip_id,
                r.route_id,
                COALESCE(r.route_short_name, ''),
                COALESCE(r.route_long_name, ''),
                COALESCE(a.agency_name, ''),
                COALESCE(t.trip_headsign, '')
            FROM trip_patterns tp
            JOIN trips t
                ON t.trip_id = tp.trip_id
            JOIN routes r
                ON r.route_id = t.route_id
            LEFT JOIN agency a
                ON a.agency_id = r.agency_id
            JOIN calendar c
                ON c.service_id = tp.service_id
            WHERE tp.pattern_id = ?
              AND c.start_date <= ?
              AND c.end_date >= ?
              AND c.$weekday = '1'
        """.trimIndent()

        db.rawQuery(
            sql,
            arrayOf(
                patternId.toString(),
                date,
                date
            )
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result.add(
                    TripChoice(
                        tripId = cursor.getString(0) ?: "",
                        routeId = cursor.getString(1) ?: "",
                        lineNumber = cursor.getString(2) ?: "",
                        lineName = cursor.getString(3) ?: "",
                        agencyName = cursor.getString(4) ?: "",
                        headsign = cursor.getString(5) ?: "",
                        serviceDate = serviceDate
                    )
                )
            }
        }

        return result
    }

    /**
     * טוען את כל זמני התחנות של נסיעה מסוימת.
     */
    private fun loadTripStops(
        db: SQLiteDatabase,
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

        if (legs.isEmpty()) {
            throw IllegalStateException(
                "לא ניתן לשחזר את פרטי המסלול."
            )
        }

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
