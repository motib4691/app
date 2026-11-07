package il.co.israeltransit.offline.maps

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Handler
import android.os.Looper
import com.google.gson.JsonObject
import il.co.israeltransit.offline.database.TransitDatabase
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textHaloColor
import org.maplibre.android.style.layers.PropertyFactory.textHaloWidth
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.min

/**
 * Displays transit stops within the currently visible map bounds.
 *
 * Database queries and GeoJSON preparation run in the background.
 * MapLibre style updates run on the main thread.
 */
object TransitStopMapLayer {

    private const val SOURCE_ID = "transit-stops-source"
    private const val CLUSTER_LAYER_ID = "transit-stops-clusters"
    private const val CLUSTER_COUNT_LAYER_ID =
        "transit-stops-cluster-count"
    private const val STOP_LAYER_ID = "transit-stops-individual"

    private const val MAX_CLUSTER_ZOOM = 14
    private const val CLUSTER_RADIUS = 50

    private const val LATITUDE_PADDING_FACTOR = 0.10
    private const val LONGITUDE_PADDING_FACTOR = 0.10

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val cameraListeners =
        WeakHashMap<MapLibreMap, MapLibreMap.OnCameraIdleListener>()

    private val requestSequences =
        WeakHashMap<MapLibreMap, AtomicLong>()

    private data class ViewBounds(
        val south: Double,
        val north: Double,
        val west: Double,
        val east: Double
    )

    /**
     * Installs the stop source and layers, registers camera updates,
     * and starts loading stops for the visible area.
     */
    fun install(
        context: Context,
        style: Style,
        map: MapLibreMap,
        onComplete: (Int) -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        val appContext = context.applicationContext

        try {
            if (style.getSource(SOURCE_ID) == null) {
                installLayers(style)
            }

            registerCameraListener(
                context = appContext,
                style = style,
                map = map,
                onError = onError
            )

            // The source and layers are ready.
            // Stop data is loaded asynchronously afterwards.
            onComplete(0)

            refreshViewport(
                context = appContext,
                style = style,
                map = map,
                onError = onError
            )

        } catch (exception: Exception) {
            onError(
                exception.message
                    ?: "לא ניתן להתקין את שכבת התחנות"
            )
        }
    }

    private fun registerCameraListener(
        context: Context,
        style: Style,
        map: MapLibreMap,
        onError: (String) -> Unit
    ) {
        synchronized(cameraListeners) {
            if (cameraListeners.containsKey(map)) {
                return
            }

            val mapReference = WeakReference(map)

            val listener = object :
                MapLibreMap.OnCameraIdleListener {

                override fun onCameraIdle() {
                    val currentMap = mapReference.get() ?: return

                    refreshViewport(
                        context = context,
                        style = style,
                        map = currentMap,
                        onError = onError
                    )
                }
            }

            cameraListeners[map] = listener
            map.addOnCameraIdleListener(listener)
        }
    }

    private fun getRequestSequence(
        map: MapLibreMap
    ): AtomicLong {
        synchronized(requestSequences) {
            return requestSequences.getOrPut(map) {
                AtomicLong(0)
            }
        }
    }

    /**
     * Reads the visible map bounds and starts a background query.
     * Older requests are discarded when a newer camera position exists.
     */
    private fun refreshViewport(
        context: Context,
        style: Style,
        map: MapLibreMap,
        onError: (String) -> Unit
    ) {
        val bounds = try {
            val coordinates = DoubleArray(4)

            // Order: north latitude, east longitude,
            // south latitude, west longitude.
            map.projection.getVisibleCoordinateBounds(coordinates)

            createPaddedBounds(coordinates)
        } catch (exception: Exception) {
            onError(
                exception.message
                    ?: "לא ניתן לקרוא את גבולות המפה"
            )
            return
        }

        val sequence = getRequestSequence(map)
        val requestId = sequence.incrementAndGet()

        executor.execute {
            try {
                val features = loadStopFeatures(
                    context = context,
                    bounds = bounds,
                    sequence = sequence,
                    requestId = requestId
                ) ?: return@execute

                if (sequence.get() != requestId) {
                    return@execute
                }

                val collection = FeatureCollection.fromFeatures(
                    features.toTypedArray()
                )

                mainHandler.post {
                    if (sequence.get() != requestId) {
                        return@post
                    }

                    try {
                        val source =
                            style.getSource(SOURCE_ID) as? GeoJsonSource
                                ?: throw IllegalStateException(
                                    "מקור התחנות אינו זמין במפה"
                                )

                        source.setGeoJson(collection)

                    } catch (exception: Exception) {
                        if (sequence.get() == requestId) {
                            onError(
                                exception.message
                                    ?: "לא ניתן לעדכן את התחנות במפה"
                            )
                        }
                    }
                }

            } catch (exception: Exception) {
                if (sequence.get() == requestId) {
                    mainHandler.post {
                        if (sequence.get() == requestId) {
                            onError(
                                exception.message
                                    ?: "טעינת התחנות נכשלה"
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Expands the visible rectangle slightly so that stops near
     * the screen edges are not repeatedly removed and reloaded.
     */
    private fun createPaddedBounds(
        coordinates: DoubleArray
    ): ViewBounds {
        require(coordinates.size >= 4) {
            "גבולות המפה אינם תקינים"
        }

        val north = coordinates[0]
        val east = coordinates[1]
        val south = coordinates[2]
        val west = coordinates[3]

        require(
            north.isFinite() &&
                east.isFinite() &&
                south.isFinite() &&
                west.isFinite()
        ) {
            "התקבלו גבולות מפה לא תקינים"
        }

        val latitudeSpan = abs(north - south)
        val latitudePadding =
            latitudeSpan * LATITUDE_PADDING_FACTOR

        val paddedNorth =
            (maxOf(north, south) + latitudePadding)
                .coerceAtMost(90.0)

        val paddedSouth =
            (minOf(north, south) - latitudePadding)
                .coerceAtLeast(-90.0)

        val normalizedWest = normalizeLongitude(west)
        val normalizedEast = normalizeLongitude(east)

        val longitudeSpan = if (east >= west) {
            east - west
        } else {
            east + 360.0 - west
        }

        val padding =
            longitudeSpan * LONGITUDE_PADDING_FACTOR

        val paddedSpan = min(
            360.0,
            longitudeSpan + padding * 2.0
        )

        val resultWest: Double
        val resultEast: Double

        if (paddedSpan >= 359.999) {
            resultWest = -180.0
            resultEast = 180.0
        } else {
            val center = normalizedWest + longitudeSpan / 2.0

            resultWest = normalizeLongitude(
                center - paddedSpan / 2.0
            )

            resultEast = normalizeLongitude(
                center + paddedSpan / 2.0
            )
        }

        return ViewBounds(
            south = paddedSouth,
            north = paddedNorth,
            west = resultWest,
            east = resultEast
        )
    }

    private fun normalizeLongitude(value: Double): Double {
        return ((value + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
    }

    /**
     * Loads only stops whose coordinates fall inside the requested
     * geographic rectangle.
     */
    private fun loadStopFeatures(
        context: Context,
        bounds: ViewBounds,
        sequence: AtomicLong,
        requestId: Long
    ): List<Feature>? {

        val databaseProvider = TransitDatabase(context)

        val database: SQLiteDatabase =
            databaseProvider.openReadOnly()
                ?: error("מסד הנתונים אינו זמין")

        return database.use { db ->

            val longitudeCondition =
                if (bounds.west <= bounds.east) {
                    "stop_lon BETWEEN ? AND ?"
                } else {
                    "(stop_lon >= ? OR stop_lon <= ?)"
                }

            val sql = """
                SELECT
                    stop_id,
                    COALESCE(stop_name, '') AS stop_name,
                    stop_lat,
                    stop_lon
                FROM stops
                WHERE
                    stop_lat BETWEEN ? AND ?
                    AND $longitudeCondition
                    AND stop_lat IS NOT NULL
                    AND stop_lon IS NOT NULL
                    AND stop_lat BETWEEN -90 AND 90
                    AND stop_lon BETWEEN -180 AND 180
            """.trimIndent()

            val arguments = arrayOf(
                bounds.south.toString(),
                bounds.north.toString(),
                bounds.west.toString(),
                bounds.east.toString()
            )

            db.rawQuery(sql, arguments).use { cursor ->

                val stopIdColumn =
                    cursor.getColumnIndexOrThrow("stop_id")

                val stopNameColumn =
                    cursor.getColumnIndexOrThrow("stop_name")

                val latitudeColumn =
                    cursor.getColumnIndexOrThrow("stop_lat")

                val longitudeColumn =
                    cursor.getColumnIndexOrThrow("stop_lon")

                val features = ArrayList<Feature>()

                while (cursor.moveToNext()) {

                    // Stop obsolete database work as soon as possible.
                    if (sequence.get() != requestId) {
                        return@use null
                    }

                    if (
                        cursor.isNull(latitudeColumn) ||
                        cursor.isNull(longitudeColumn)
                    ) {
                        continue
                    }

                    val latitude =
                        cursor.getDouble(latitudeColumn)

                    val longitude =
                        cursor.getDouble(longitudeColumn)

                    if (
                        !latitude.isFinite() ||
                        !longitude.isFinite() ||
                        latitude !in -90.0..90.0 ||
                        longitude !in -180.0..180.0
                    ) {
                        continue
                    }

                    val stopId =
                        cursor.getString(stopIdColumn)
                            ?: continue

                    val stopName =
                        cursor.getString(stopNameColumn) ?: ""

                    val properties = JsonObject().apply {
                        addProperty("stop_id", stopId)
                        addProperty("stop_name", stopName)
                    }

                    features.add(
                        Feature.fromGeometry(
                            Point.fromLngLat(
                                longitude,
                                latitude
                            ),
                            properties
                        )
                    )
                }

                features
            }
        }
    }

    private fun installLayers(style: Style) {
        check(style.getSource(SOURCE_ID) == null) {
            "מקור התחנות כבר קיים במפה"
        }

        val options = GeoJsonOptions()
            .withCluster(true)
            .withClusterRadius(CLUSTER_RADIUS)
            .withClusterMaxZoom(MAX_CLUSTER_ZOOM)

        // Start with an empty source.
        // The visible stops are loaded asynchronously.
        style.addSource(
            GeoJsonSource(
                SOURCE_ID,
                FeatureCollection.fromFeatures(
                    emptyArray<Feature>()
                ),
                options
            )
        )

        val clusterLayer = CircleLayer(
            CLUSTER_LAYER_ID,
            SOURCE_ID
        ).withFilter(
            Expression.has("point_count")
        ).withProperties(
            circleColor("#245B85"),
            circleRadius(14f),
            circleStrokeColor("#FFFFFF"),
            circleStrokeWidth(2f)
        )

        style.addLayer(clusterLayer)

        val clusterCountLayer = SymbolLayer(
            CLUSTER_COUNT_LAYER_ID,
            SOURCE_ID
        ).withFilter(
            Expression.has("point_count")
        ).withProperties(
            textField(
                Expression.get("point_count_abbreviated")
            ),
            textSize(11f),
            textColor("#FFFFFF"),
            textHaloColor("#245B85"),
            textHaloWidth(0.5f)
        )

        style.addLayer(clusterCountLayer)

        val individualStopsLayer = CircleLayer(
            STOP_LAYER_ID,
            SOURCE_ID
        ).withFilter(
            Expression.not(
                Expression.has("point_count")
            )
        ).withProperties(
            circleColor("#1769AA"),
            circleRadius(4f),
            circleStrokeColor("#FFFFFF"),
            circleStrokeWidth(1.2f)
        )

        style.addLayer(individualStopsLayer)
        TransitStopLabelsLayer.install(style)
    }
}
