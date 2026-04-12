package com.pebblemaps.android.data.remote

import android.util.Log
import com.pebblemaps.android.domain.model.LatLng
import com.pebblemaps.android.domain.model.RoadClass
import com.pebblemaps.android.domain.model.RoadSegment
import com.wdtinc.mapbox_vector_tile.adapt.jts.MvtReader
import com.wdtinc.mapbox_vector_tile.adapt.jts.TagKeyValueMapConverter
import com.wdtinc.mapbox_vector_tile.adapt.jts.model.JtsLayer
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.LineString
import org.locationtech.jts.geom.MultiLineString
import java.io.ByteArrayInputStream
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

class ProtomapsTileApi(private val client: HttpClient) {

    private val apiKey = "REPLACE_ME_FROM_ENV"
    private val baseUrl = "https://api.protomaps.com/tiles/v3"
    private val geometryFactory = GeometryFactory()

    data class TileDebugInfo(
        val z: Int, val x: Int, val y: Int,
        val layerNames: List<String>,
        val featureCountByLayer: Map<String, Int>,
        val highwayTagValues: List<String>,
        val roadCount: Int,
        val fetchError: String? = null
    )

    suspend fun fetchRoads(center: LatLng, zoom: Int = 15): List<RoadSegment> {
        val (tileX, tileY) = latLngToTile(center, zoom)
        return fetchRoadsForTile(zoom, tileX, tileY)
    }

    suspend fun fetchDebugTileInfo(z: Int, x: Int, y: Int): TileDebugInfo {
        val url = "$baseUrl/$z/$x/$y.mvt?key=$apiKey"
        return try {
            val bytes: ByteArray = client.get(url).body()
            val inputStream = ByteArrayInputStream(bytes)
            val jtsMvt = MvtReader.loadMvt(inputStream, geometryFactory, TagKeyValueMapConverter())

            val layerNames = jtsMvt.layers.map { it.name }
            val featureCountByLayer = jtsMvt.layers.associate { it.name to it.geometries.size }

            val highwayTags = mutableListOf<String>()
            var roadCount = 0
            val roadsLayer = jtsMvt.layers.find { it.name == "roads" }
            roadsLayer?.geometries?.forEach { geom ->
                if (geom is LineString) {
                    roadCount++
                    @Suppress("UNCHECKED_CAST")
                    val attributes = geom.userData as? Map<String, Any?>
                    val highway = attributes?.get("highway") ?: attributes?.get("pmap:kind")
                    if (highway != null) highwayTags.add(highway.toString())
                }
            }

            TileDebugInfo(
                z = z, x = x, y = y,
                layerNames = layerNames,
                featureCountByLayer = featureCountByLayer,
                highwayTagValues = highwayTags.distinct(),
                roadCount = roadCount
            )
        } catch (e: Exception) {
            Log.e("PebbleMapsRoads", "Protomaps debug failed for $z/$x/$y: ${e.javaClass.simpleName}: ${e.message}")
            TileDebugInfo(z, x, y, emptyList(), emptyMap(), emptyList(), 0, e.message)
        }
    }

    suspend fun fetchRoadsForTile(z: Int, x: Int, y: Int): List<RoadSegment> {
        val url = "$baseUrl/$z/$x/$y.mvt?key=$apiKey"
        return try {
            val bytes: ByteArray = client.get(url).body()
            val inputStream = ByteArrayInputStream(bytes)
            val jtsMvt = MvtReader.loadMvt(inputStream, geometryFactory, TagKeyValueMapConverter())

            val segments = mutableListOf<RoadSegment>()
            val layer: JtsLayer? = jtsMvt.layers.find { it.name == "roads" }

            var lineStringCount = 0
            var multiLineStringCount = 0
            var otherGeomCount = 0
            var skippedNonRoad = 0

            layer?.geometries?.forEach { geom ->
                // Extract line strings from both LineString and MultiLineString geometries
                val lineStrings: List<LineString> = when (geom) {
                    is LineString -> {
                        lineStringCount++
                        listOf(geom)
                    }
                    is MultiLineString -> {
                        multiLineStringCount++
                        (0 until geom.numGeometries).map { geom.getGeometryN(it) as LineString }
                    }
                    else -> {
                        otherGeomCount++
                        return@forEach
                    }
                }

                @Suppress("UNCHECKED_CAST")
                val attributes = geom.userData as? Map<String, Any?> ?: emptyMap()
                
                // Filter out non-road features (railways, paths, etc.)
                val kind = attributes["pmap:kind"] as? String
                    ?: attributes["kind"] as? String
                    ?: ""
                if (kind in NON_ROAD_KINDS) {
                    skippedNonRoad++
                    return@forEach
                }

                val roadClass = classifyRoad(attributes)

                for (line in lineStrings) {
                    val points = line.coordinates.map { coord ->
                        tileToLatLng(coord.x, coord.y, x, y, z)
                    }
                    if (points.size < 2) continue
                    segments.add(RoadSegment(points = points, roadClass = roadClass))
                }
            }

            Log.d("PebbleMapsRoads", "Tile $z/$x/$y: ${segments.size} roads (LS=$lineStringCount MLS=$multiLineStringCount other=$otherGeomCount skippedNonRoad=$skippedNonRoad totalGeoms=${layer?.geometries?.size ?: 0})")
            if (segments.size < 5 && layer != null) {
                // Log sample attributes when few roads found, for debugging
                val sampleAttrs = layer.geometries.take(3).mapNotNull { g ->
                    @Suppress("UNCHECKED_CAST")
                    (g.userData as? Map<String, Any?>)?.entries?.joinToString(", ") { "${it.key}=${it.value}" }
                }
                Log.d("PebbleMapsRoads", "  Sample attrs: $sampleAttrs")
            }
            segments
        } catch (e: Exception) {
            Log.e("PebbleMapsRoads", "Protomaps failed for $z/$x/$y: ${e.javaClass.simpleName}: ${e.message}")
            emptyList()
        }
    }

    private fun latLngToTile(latLng: LatLng, zoom: Int): Pair<Int, Int> {
        val n = 1 shl zoom
        val x = ((latLng.lng + 180.0) / 360.0 * n).toInt()
        val latRad = Math.toRadians(latLng.lat)
        val y = ((1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / Math.PI) / 2.0 * n).toInt()
        return x to y
    }

    private fun tileToLatLng(tileX: Double, tileY: Double, tileCol: Int, tileRow: Int, zoom: Int): LatLng {
        val extent = 4096.0
        val n = 1 shl zoom
        val x = tileCol + tileX / extent
        val y = tileRow + tileY / extent
        val lon = x / n * 360.0 - 180.0
        val latRad = atan(sinh(Math.PI * (1 - 2 * y / n)))
        val lat = Math.toDegrees(latRad)
        return LatLng(lat, lon)
    }

    private val NON_ROAD_KINDS = setOf(
        "rail", "subway", "light_rail", "tram", "monorail", "funicular",
        "narrow_gauge", "preserved", "miniature", "disused",
        "path", "steps", "corridor", "bridleway",
        "pier", "runway", "taxiway", "cable_car",
        "ferry"
    )

    private fun classifyRoad(attributes: Map<String, Any?>): RoadClass {
        // Protomaps v4 uses 'kind' for general type and 'kind_detail' for OSM-level detail
        // Older versions or some tiles may use 'pmap:kind'
        val kind = attributes["pmap:kind"] as? String
            ?: attributes["kind"] as? String
            ?: ""
        val kindDetail = attributes["pmap:kind_detail"] as? String
            ?: attributes["kind_detail"] as? String
            ?: ""
        
        // Try kind_detail first (more specific: motorway, trunk, primary, etc.)
        if (kindDetail.isNotEmpty()) {
            return when (kindDetail) {
                "motorway", "trunk", "motorway_link", "trunk_link" -> RoadClass.MAJOR
                "primary", "secondary", "primary_link", "secondary_link" -> RoadClass.MEDIUM
                "tertiary", "residential", "unclassified", "tertiary_link" -> RoadClass.STANDARD
                else -> RoadClass.MINOR
            }
        }
        
        // Fall back to kind (generalized: highway, major_road, minor_road, etc.)
        return when (kind) {
            "highway" -> RoadClass.MAJOR
            "major_road" -> RoadClass.MEDIUM
            "minor_road" -> RoadClass.STANDARD
            else -> RoadClass.MINOR
        }
    }
}
