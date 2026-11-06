
package il.co.israeltransit.offline.routing

import android.database.sqlite.SQLiteDatabase
import il.co.israeltransit.offline.database.TransitDatabase
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.LinkedHashMap

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

    private data class BoardingCandidate(
        val index: Int,
        val label: Label
    )

    private data class TripStopTime(
        val stopId: String,
        val stopName: String,
        val arrivalSeconds: Int?,
        val departureSeconds: Int?
    )

    private data class TripRun(
        val tripId: String,
        val routeId: String,
        val lineNumber: String,
        val lineName: String,
        val agencyName: String,
        val headsign: String,
        val serviceDate: LocalDate,
        val stops: List<TripStopTime>
    )

    private data class RunCacheKey(
        val patternId: Int,
        val serviceDate: LocalDate
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

    /*
     * זמן החלפה זמני.
     * בהמשך יוחלף בחישוב הליכה אמיתי.
     */
    private val minimumTransferMinutes = 2L

    /*
     * הגבלת משך החיפוש ל-12 שעות.
     */
    private val maximumJourneyMinutes = 720L

    /*
     * מטמון מוגבל כדי לצמצם צריכת זיכרון.
     */
    private val maximumCachedPatternRuns = 6

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

        if (maxResults !in 1..20) {
            return SearchResult(
                emptyList(),
                "מספר התוצאות המבוקש אינו תקין."
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

            if (originStopId !in positionsByStop) {
                return SearchResult(
                    emptyList(),
                    "תחנת המוצא אינה מופיעה במסלולי התחבורה."
                )
            }

            if (destinationStopId !in positionsByStop) {
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
     * טעינת תבניות המסלולים ומיקומי התחנות.
     *
     * כל תחנה מקושרת ישירות לתבנית ולמיקום המדויק שלה.
     */
    private fun loadPatterns(
        db: SQLiteDatabase
    ): Pair<
        Map<Int, Pattern>,
        Map<String, List<BoardPosition>>
    > {

        val routeIds = mutableMapOf<Int, String>()
        val stopsByPattern =
            mutableMapOf<Int, MutableList<String>>()

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

            if (stopIds.size < 2) continue

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
     * חישוב המסלולים בסבבים.
     *
     * סבב ראשון: נסיעה אחת.
     * סבב שני: שתי נסיעות.
     * סבב שלישי: שלוש נסיעות.
     *
     * בכל סבב נשמרת תווית נפרדת לכל תחנה.
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
         * מטמון LRU מוגבל.
         * נשמרות בו תוצאות הטעינה האחרונות בלבד.
         */
        val runsCache =
            object : LinkedHashMap<RunCacheKey, List<TripRun>>(
                16,
                0.75f,
                true
            ) {
                override fun removeEldestEntry(
                    eldest: MutableMap.MutableEntry<
                        RunCacheKey,
                        List<TripRun>
                    >?
                ): Boolean {
                    return size > maximumCachedPatternRuns
                }
            }

        for (round in 1..(maxTransfers + 1)) {

            val nextRound = mutableMapOf<String, Label>()

            /*
             * קיבוץ כל נקודות העלייה האפשריות לפי תבנית.
             */
            val boardPositionsByPattern =
                mutableMapOf<Int, MutableList<BoardingCandidate>>()

            for ((stopId, label) in previousRound) {

                val positions = positionsByStop[stopId].orEmpty()

                for (position in positions) {

                    val pattern =
                        patternsById[position.patternId] ?: continue

                    if (position.index >= pattern.stops.lastIndex) {
                        continue
                    }

                    /*
                     * בדיקת התאמה ישירה בין התחנה למיקום בתבנית.
                     */
                    if (pattern.stops[position.index] != stopId) {
                        continue
                    }

                    boardPositionsByPattern
                        .getOrPut(position.patternId) { mutableListOf() }
                        .add(
                            BoardingCandidate(
                                index = position.index,
                                label = label
                            )
                        )
                }
            }

            var changed = false

            /*
             * עוברים רק על תבניות שבהן קיימת תחנה
             * שניתן להגיע אליה בסבב הקודם.
             */
            for ((patternId, boardCandidates) in boardPositionsByPattern) {

                val pattern = patternsById[patternId] ?: continue

                /*
                 * מחשבים את תאריכי השירות הדרושים לתבנית.
                 * בודקים גם את היום הקודם בגלל נסיעות
                 * ששעותיהן עוברות את חצות.
                 */
                val serviceDates = mutableSetOf<LocalDate>()

                for (candidate in boardCandidates) {

                    val requiredBoardingTime =
                        if (candidate.label.leg == null) {
                            candidate.label.arrivalAt
                        } else {
                            candidate.label.arrivalAt.plusMinutes(
                                minimumTransferMinutes
                            )
                        }

                    serviceDates.add(
                        requiredBoardingTime.toLocalDate()
                    )

                    serviceDates.add(
                        requiredBoardingTime.toLocalDate().minusDays(1)
                    )
                }

                for (serviceDate in serviceDates) {

                    val cacheKey = RunCacheKey(
                        patternId = patternId,
                        serviceDate = serviceDate
                    )

                    val runs = runsCache[cacheKey]
                        ?: loadPatternRuns(
                            db = db,
                            pattern = pattern,
                            serviceDate = serviceDate
                        ).also {
                            runsCache[cacheKey] = it
                        }

                    /*
                     * כל הנסיעות בתבנית ובתאריך השירות נבדקות.
                     */
                    for (run in runs) {

                        if (!matchesPattern(run, pattern)) {
                            continue
                        }

                        for (candidate in boardCandidates) {

                            val boardIndex = candidate.index

                            if (boardIndex >= run.stops.lastIndex) {
                                continue
                            }

                            val boardLabel = candidate.label
                            val boardStop = run.stops[boardIndex]

                            val earliestBoarding =
                                if (boardLabel.leg == null) {
                                    boardLabel.arrivalAt
                                } else {
                                    boardLabel.arrivalAt.plusMinutes(
                                        minimumTransferMinutes
                                    )
                                }

                            val departureSeconds =
                                boardStop.departureSeconds
                                    ?: boardStop.arrivalSeconds
                                    ?: continue

                            val actualDepartureAt =
                                run.serviceDate.atStartOfDay()
                                    .plusSeconds(
                                        departureSeconds.toLong()
                                    )

                            if (actualDepartureAt.isBefore(earliestBoarding)) {
                                continue
                            }

                            /*
                             * מונעים חזרה על אותה נסיעה
                             * שכבר מופיעה בשרשרת המסלול.
                             */
                            if (
                                containsTripOccurrence(
                                    label = boardLabel,
                                    tripId = run.tripId,
                                    departureAt = actualDepartureAt
                                )
                            ) {
                                continue
                            }

                            /*
                             * בודקים את כל תחנות הירידה האפשריות
                             * בהמשך הנסיעה.
                             */
                            for (
                                alightIndex in
                                (boardIndex + 1) until run.stops.size
                            ) {

                                val alightStop = run.stops[alightIndex]

                                val arrivalSeconds =
                                    alightStop.arrivalSeconds
                                        ?: alightStop.departureSeconds
                                        ?: continue

                                val arrivalAt =
                                    run.serviceDate.atStartOfDay()
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

                                if (
                                    totalDuration < 0 ||
                                    totalDuration > maximumJourneyMinutes
                                ) {
                                    continue
                                }

                                val oldLabel =
                                    nextRound[alightStop.stopId]

                                /*
                                 * באותו מספר נסיעות נשמרת ההגעה
                                 * המוקדמת ביותר לכל תחנה.
                                 */
                                if (
                                    oldLabel != null &&
                                    !arrivalAt.isBefore(oldLabel.arrivalAt)
                                ) {
                                    continue
                                }

                                val leg = RouteLeg(
                                    tripId = run.tripId,
                                    routeId = run.routeId,
                                    lineNumber = run.lineNumber,
                                    lineName = run.lineName,
                                    agencyName = run.agencyName,
                                    headsign = run.headsign,
                                    fromStopId = boardStop.stopId,
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

            /*
             * אם היעד הושג בסבב הנוכחי,
             * שומרים את התוצאה בנפרד מהסבבים האחרים.
             */
            val destinationLabel =
                nextRound[destinationStopId]

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
                journey.legs.joinToString("|") {
                    "${it.tripId}@${it.departureAt}"
                }
            }
            .sortedWith(
                compareBy<Journey> { it.arrivalAt }
                    .thenBy { it.transfers }
            )
            .take(maxResults)

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
     * בדיקה אם נסיעה מסוימת כבר מופיעה בשרשרת המסלול.
     */
    private fun containsTripOccurrence(
        label: Label,
        tripId: String,
        departureAt: LocalDateTime
    ): Boolean {

        var current: Label? = label

        while (current != null) {

            val leg = current.leg

            if (
                leg != null &&
                leg.tripId == tripId &&
                leg.departureAt == departureAt
            ) {
                return true
            }

            current = current.previous
        }

        return false
    }

    /**
     * בדיקת התאמה מלאה בין נסיעה לתבנית המסלול.
     */
    private fun matchesPattern(
        run: TripRun,
        pattern: Pattern
    ): Boolean {

        if (run.routeId != pattern.routeId) {
            return false
        }

        if (run.stops.size != pattern.stops.size) {
            return false
        }

        return run.stops.indices.all { index ->
            run.stops[index].stopId == pattern.stops[index]
        }
    }

    /**
     * טעינת כל הנסיעות הפעילות בתבנית מסוימת
     * ובתאריך שירות מסוים.
     *
     * זמני כל התחנות נטענים בשאילתה משולבת אחת,
     * ולא בשאילתה נפרדת לכל trip.
     */
    private fun loadPatternRuns(
        db: SQLiteDatabase,
        pattern: Pattern,
        serviceDate: LocalDate
    ): List<TripRun> {

        val weekday = weekdayColumn(serviceDate)
        val date = dateKey(serviceDate)

        class RunBuilder(
            val tripId: String,
            val routeId: String,
            val lineNumber: String,
            val lineName: String,
            val agencyName: String,
            val headsign: String
        ) {
            val stops = mutableListOf<TripStopTime>()
        }

        val builders = LinkedHashMap<String, RunBuilder>()

        val sql = """
            SELECT
                tp.trip_id,
                r.route_id,
                COALESCE(r.route_short_name, ''),
                COALESCE(r.route_long_name, ''),
                COALESCE(a.agency_name, ''),
                COALESCE(t.trip_headsign, ''),
                st.stop_id,
                COALESCE(s.stop_name, ''),
                st.arrival_time,
                st.departure_time
            FROM trip_patterns tp
            JOIN trips t
                ON t.trip_id = tp.trip_id
            JOIN routes r
                ON r.route_id = t.route_id
            LEFT JOIN agency a
                ON a.agency_id = r.agency_id
            JOIN calendar c
                ON c.service_id = tp.service_id
            JOIN stop_times st
                ON st.trip_id = tp.trip_id
            LEFT JOIN stops s
                ON s.stop_id = st.stop_id
            WHERE tp.pattern_id = ?
              AND r.route_id = ?
              AND c.start_date <= ?
              AND c.end_date >= ?
              AND c.$weekday = '1'
            ORDER BY tp.trip_id, st.stop_sequence
        """.trimIndent()

        db.rawQuery(
            sql,
            arrayOf(
                pattern.id.toString(),
                pattern.routeId,
                date,
                date
            )
        ).use { cursor ->

            while (cursor.moveToNext()) {

                val tripId = cursor.getString(0) ?: continue

                val builder = builders.getOrPut(tripId) {

                    RunBuilder(
                        tripId = tripId,
                        routeId = cursor.getString(1) ?: "",
                        lineNumber = cursor.getString(2) ?: "",
                        lineName = cursor.getString(3) ?: "",
                        agencyName = cursor.getString(4) ?: "",
                        headsign = cursor.getString(5) ?: ""
                    )
                }

                builder.stops.add(
                    TripStopTime(
                        stopId = cursor.getString(6) ?: "",
                        stopName = cursor.getString(7) ?: "",
                        arrivalSeconds =
                            if (cursor.isNull(8)) {
                                null
                            } else {
                                cursor.getInt(8)
                            },
                        departureSeconds =
                            if (cursor.isNull(9)) {
                                null
                            } else {
                                cursor.getInt(9)
                            }
                    )
                )
            }
        }

        return builders.values.map { builder ->

            TripRun(
                tripId = builder.tripId,
                routeId = builder.routeId,
                lineNumber = builder.lineNumber,
                lineName = builder.lineName,
                agencyName = builder.agencyName,
                headsign = builder.headsign,
                serviceDate = serviceDate,
                stops = builder.stops
            )
        }
    }

    /**
     * שחזור המסלול המלא מתוך שרשרת התוויות.
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
