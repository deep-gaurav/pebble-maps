package com.pebblemaps.android.domain.model

data class WatchRenderConfig(
    val width: Int = 176,
    val height: Int = 176
)

enum class RoadClass(val wireValue: Int, val previewWidthPx: Float, val watchHalfWidthPx: Int) {
    MINOR(0, 2f, 1),
    STANDARD(1, 4f, 2),
    MEDIUM(2, 6f, 3),
    MAJOR(3, 8f, 4);

    companion object {
        fun fromHighwayTag(tag: String?): RoadClass {
            return when (tag) {
                "motorway", "trunk", "motorway_link", "trunk_link" -> MAJOR
                "primary", "secondary", "primary_link", "secondary_link" -> MEDIUM
                "tertiary", "residential", "unclassified", "tertiary_link" -> STANDARD
                else -> MINOR
            }
        }

        fun fromWireValue(value: Int): RoadClass {
            return entries.firstOrNull { it.wireValue == value } ?: MINOR
        }
    }
}

enum class FeatureType(val wireValue: Int) {
    WATER(0),
    PARK(1),
    BUILDING(2)
}

data class MapFeature(
    val type: FeatureType,
    val bounds: List<LatLng> // 4 corners
)

data class RoadSegment(
    val points: List<LatLng>,
    val roadClass: RoadClass,
    val isBridge: Boolean = false,
    val isTunnel: Boolean = false
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
    val nearbyRoads: List<RoadSegment> = emptyList(),
    val nearbyFeatures: List<MapFeature> = emptyList(),
    val timeRemainingSeconds: Double = 0.0
)

enum class TurnDirection {
    NONE, STRAIGHT, SLIGHT_LEFT, LEFT, SHARP_LEFT,
    SLIGHT_RIGHT, RIGHT, SHARP_RIGHT, UTURN
}
