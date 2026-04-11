package com.pebblemaps.android.domain.model

data class WatchRenderConfig(
    val width: Int = 176,
    val height: Int = 176
)

data class WatchFrame(
    val routePoints: List<LatLng>,
    val currentLocation: LatLng,
    val turnDirection: TurnDirection,
    val distanceToNextTurn: Double,
    val distanceRemaining: Double,
    val streetName: String?,
    val bearing: Float = 0f,
    val viewportMeters: Double = 150.0,
    val nearbyRoads: List<List<LatLng>> = emptyList()
)

enum class TurnDirection {
    NONE, STRAIGHT, SLIGHT_LEFT, LEFT, SHARP_LEFT,
    SLIGHT_RIGHT, RIGHT, SHARP_RIGHT, UTURN
}
