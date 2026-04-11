package com.pebblemaps.android.data.remote

import com.pebblemaps.android.domain.model.LatLng
import com.pebblemaps.android.domain.model.RoadClass
import com.pebblemaps.android.domain.model.RoadSegment
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.serialization.Serializable
import kotlin.math.cos

class OverpassApi(private val client: HttpClient) {

    private val baseUrl = "https://overpass-api.de/api/interpreter"

    suspend fun fetchRoads(center: LatLng, radiusMeters: Double): List<RoadSegment> {
        val deltaLat = radiusMeters / 111320.0
        val deltaLng = radiusMeters / (111320.0 * cos(Math.toRadians(center.lat)))
        val south = center.lat - deltaLat
        val north = center.lat + deltaLat
        val west = center.lng - deltaLng
        val east = center.lng + deltaLng

        val query = "[out:json];way[\"highway\"]($south,$west,$north,$east);out geom;"

        return try {
            val response = client.get(baseUrl) {
                parameter("data", query)
            }.body<OverpassResponse>()

            response.elements
                .filter { it.type == "way" && it.geometry.size >= 2 }
                .map { element ->
                    RoadSegment(
                        points = element.geometry.map { coord -> LatLng(coord.lat, coord.lon) },
                        roadClass = RoadClass.fromHighwayTag(element.tags["highway"])
                    )
                }
        } catch (e: Exception) {
            emptyList()
        }
    }
}

@Serializable
private data class OverpassResponse(
    val elements: List<OverpassElement> = emptyList()
)

@Serializable
private data class OverpassElement(
    val type: String = "",
    val geometry: List<OverpassCoord> = emptyList(),
    val tags: Map<String, String> = emptyMap()
)

@Serializable
private data class OverpassCoord(
    val lat: Double = 0.0,
    val lon: Double = 0.0
)
