
package il.co.israeltransit.offline.maps

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Handler
import android.os.Looper
import com.google.gson.JsonObject
import il.co.israeltransit.offline.database.TransitDatabase
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
import java.util.concurrent.Executors

/**
 * Offline public transport stop layer.
 *
 * Reads stops from the imported SQLite database,
 * prepares clustered GeoJSON in the background,
 * and installs the layers on the main thread.
 */
object TransitStopMapLayer {

    private const val SOURCE_ID = "transit-stops-source"
    private const val CLUSTER_LAYER_ID = "transit-stops-clusters"
    private const val CLUSTER_COUNT_LAYER_ID = "transit-stops-cluster-count"
    private const val STOP_LAYER_ID = "transit-stops-individual"

    private const val MAX_CLUSTER_ZOOM = 14
    private const val CLUSTER_RADIUS = 50

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Installs the stop source and its three visual layers.
     *
     * Call only after the map style has finished loading.
     */
    fun install(
        context: Context,
        style: Style,
        onComplete: (Int) -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        val appContext = context.applicationContext

        executor.execute {
            try {
                val features = loadStopFeatures(appContext)

                require(features.isNotEmpty()) {
                    "לא נמצאו תחנות עם קואורדינטות תקינות במאגר"
                }

                val collection =
                    FeatureCollection.fromFeatures(features.toTypedArray())

                mainHandler.post {
                    try {
                        require(style.getSource(SOURCE_ID) == null) {
                            "שכבת התחנות כבר קיימת במפה"
                        }

                        installLayers(style, collection)
                        onComplete(features.size)
                    } catch (exception: Exception) {
                        onError(
                            exception.message
                                ?: "לא ניתן להציג את התחנות במפה"
                        )
                    }
                }
            } catch (exception: Exception) {
                mainHandler.post {
                    onError(
                        exception.message
                            ?: "טעינת התחנות נכשלה"
                    )
                }
            }
        }
    }

    private fun loadStopFeatures(
        context: Context
    ): List<Feature> {
        val databaseProvider = TransitDatabase(context)

        val database: SQLiteDatabase =
            databaseProvider.openReadOnly()
                ?: error("מסד הנתונים אינו זמין")

        database.use { db ->
            db.rawQuery(
                """
                SELECT
                    stop_id,
                    COALESCE(stop_name, '') AS stop_name,
                    stop_lat,
                    stop_lon
                FROM stops
                WHERE
                    stop_lat IS NOT NULL
                    AND stop_lon IS NOT NULL
                    AND stop_lat BETWEEN -90 AND 90
                    AND stop_lon BETWEEN -180 AND 180
                """.trimIndent(),
                null
            ).use { cursor ->

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
                        cursor.getString(stopIdColumn) ?: continue

                    val stopName =
                        cursor.getString(stopNameColumn) ?: ""

                    val properties = JsonObject().apply {
                        addProperty("stop_id", stopId)
                        addProperty("stop_name", stopName)
                    }

                    features.add(
                        Feature.fromGeometry(
                            Point.fromLngLat(longitude, latitude),
                            properties
                        )
                    )
                }

                return features
            }
        }
    }

    private fun installLayers(
        style: Style,
        collection: FeatureCollection
    ) {
        val options = GeoJsonOptions()
            .withCluster(true)
            .withClusterRadius(CLUSTER_RADIUS)
            .withClusterMaxZoom(MAX_CLUSTER_ZOOM)

        val source = GeoJsonSource(
            SOURCE_ID,
            collection,
            options
        )

        style.addSource(source)

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
    }
}
