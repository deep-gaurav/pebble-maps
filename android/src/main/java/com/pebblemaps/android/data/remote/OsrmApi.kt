package com.pebblemaps.android.data.remote

import com.pebblemaps.android.domain.model.Geometry
import com.pebblemaps.android.domain.model.LatLng
import com.pebblemaps.android.domain.model.Leg
import com.pebblemaps.android.domain.model.Maneuver
import com.pebblemaps.android.domain.model.Route
import com.pebblemaps.android.domain.model.RouteProfile
import com.pebblemaps.android.domain.model.Step
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import org.osmdroid.util.GeoPoint

class OsrmApi(private val client: HttpClient) {

    private val baseUrl = "https://router.project-osrm.org/route/v1"

    suspend fun getRoute(
        origin: GeoPoint,
        destination: GeoPoint,
        profile: RouteProfile = RouteProfile.CYCLING
    ): Route {
        val coordinates = "${origin.longitude},${origin.latitude};${destination.longitude},${destination.latitude}"
        val response = client.get("$baseUrl/${profile.osrmValue}/$coordinates") {
            parameter("overview", "full")
            parameter("geometries", "geojson")
            parameter("steps", "true")
        }.body<OsrmResponse>()
        return response.toRoute()
    }
}

@kotlinx.serialization.Serializable
private data class OsrmResponse(
    val code: String,
    val routes: List<OsrmRoute>
)

@kotlinx.serialization.Serializable
private data class OsrmRoute(
    val geometry: OsrmGeometry,
    val legs: List<OsrmLeg>
)

@kotlinx.serialization.Serializable
private data class OsrmGeometry(
    val coordinates: List<List<Double>>
)

@kotlinx.serialization.Serializable
private data class OsrmLeg(
    val steps: List<OsrmStep>
)

@kotlinx.serialization.Serializable
private data class OsrmStep(
    val maneuver: OsrmManeuver,
    val distance: Double,
    val duration: Double,
    val geometry: OsrmGeometry
)

@kotlinx.serialization.Serializable
private data class OsrmManeuver(
    val type: String,
    val modifier: String? = null,
    val location: List<Double>
)

private fun OsrmResponse.toRoute(): Route {
    val osrmRoute = routes.firstOrNull() ?: throw Exception("No route found: $code")
    return Route(
        geometry = Geometry(
            coordinates = osrmRoute.geometry.coordinates.map {
                LatLng(it[1], it[0])
            }
        ),
        legs = osrmRoute.legs.map { leg ->
            Leg(
                steps = leg.steps.map { step ->
                    Step(
                        maneuver = Maneuver(
                            type = step.maneuver.type,
                            modifier = step.maneuver.modifier,
                            location = LatLng(
                                step.maneuver.location[1],
                                step.maneuver.location[0]
                            )
                        ),
                        distance = step.distance,
                        duration = step.duration,
                        geometry = Geometry(
                            coordinates = step.geometry.coordinates.map {
                                LatLng(it[1], it[0])
                            }
                        )
                    )
                }
            )
        }
    )
}