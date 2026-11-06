
package il.co.israeltransit.offline.maps

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Builds a minimal, fully offline MapLibre style.
 *
 * Expected tileset:
 * Protomaps Basemap v4, vector PMTiles archive.
 *
 * Visible content:
 * - Simple background
 * - Main roads
 * - Secondary and local roads
 * - Walking paths and pedestrian ways
 *
 * Buildings, land use, POIs, labels and decorative layers
 * are intentionally excluded.
 */
object OfflineMapStyle {

    private const val SOURCE_ID = "israel-roads"
    private const val SOURCE_LAYER = "roads"

    fun create(pmtilesFile: File): String {
        require(pmtilesFile.exists()) {
            "Map archive does not exist: ${pmtilesFile.absolutePath}"
        }

        require(pmtilesFile.isFile) {
            "Map archive path is not a file"
        }

        require(pmtilesFile.canRead()) {
            "Map archive is not readable"
        }

        require(pmtilesFile.length() > 0L) {
            "Map archive is empty"
        }

        val localArchiveUri = Uri.fromFile(pmtilesFile).toString()
        val pmtilesUrl = "pmtiles://$localArchiveUri"

        val source = JSONObject()
            .put("type", "vector")
            .put("url", pmtilesUrl)
            .put("maxzoom", 15)
            .put(
                "attribution",
                "© OpenStreetMap contributors"
            )

        val sources = JSONObject()
            .put(SOURCE_ID, source)

        val layers = JSONArray()
            .put(backgroundLayer())
            .put(
                roadLayer(
                    id = "main-roads",
                    kind = "highway",
                    color = "#777777",
                    width = 2.4
                )
            )
            .put(
                roadLayer(
                    id = "major-roads",
                    kind = "major_road",
                    color = "#858585",
                    width = 1.9
                )
            )
            .put(
                roadLayer(
                    id = "local-roads",
                    kind = "minor_road",
                    color = "#999999",
                    width = 1.35
                )
            )
            .put(
                roadLayer(
                    id = "walking-paths",
                    kind = "path",
                    color = "#777777",
                    width = 1.15
                )
            )

        return JSONObject()
            .put("version", 8)
            .put("name", "Israel Minimal Offline Map")
            .put("sources", sources)
            .put("layers", layers)
            .toString()
    }

    private fun backgroundLayer(): JSONObject {
        return JSONObject()
            .put("id", "map-background")
            .put("type", "background")
            .put(
                "paint",
                JSONObject()
                    .put("background-color", "#F5F5F2")
            )
    }

    private fun roadLayer(
        id: String,
        kind: String,
        color: String,
        width: Double
    ): JSONObject {
        val filter = JSONArray()
            .put("==")
            .put(
                JSONArray()
                    .put("get")
                    .put("kind")
            )
            .put(kind)

        val layout = JSONObject()
            .put("line-cap", "round")
            .put("line-join", "round")

        val paint = JSONObject()
            .put("line-color", color)
            .put("line-width", width)
            .put("line-opacity", 0.95)

        return JSONObject()
            .put("id", id)
            .put("type", "line")
            .put("source", SOURCE_ID)
            .put("source-layer", SOURCE_LAYER)
            .put("filter", filter)
            .put("layout", layout)
            .put("paint", paint)
    }
}
