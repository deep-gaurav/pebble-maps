package com.pebblemaps.android.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class LatLng(
    val lat: Double,
    val lng: Double
)

@Serializable
data class Route(
    val geometry: Geometry,
    val legs: List<Leg>
)

@Serializable
data class Geometry(
    val coordinates: List<LatLng>
)

@Serializable
data class Leg(
    val steps: List<Step>
)

@Serializable
data class Step(
    val maneuver: Maneuver,
    val distance: Double,
    val duration: Double,
    val geometry: Geometry
)

@Serializable
data class Maneuver(
    val type: String,
    val modifier: String? = null,
    val location: LatLng
)

enum class RouteProfile(val osrmValue: String) {
    DRIVING("car"),
    CYCLING("bike"),
    WALKING("foot")
}

data class NavigationState(
    val currentLocation: LatLng? = null,
    val startLocation: LatLng? = null,
    val endLocation: LatLng? = null,
    val route: Route? = null,
    val currentStepIndex: Int = 0,
    val profile: RouteProfile = RouteProfile.DRIVING,
    val isLoading: Boolean = false,
    val isProcessingShare: Boolean = false,
    val error: String? = null
) {
    val currentStep: Step?
        get() {
            val allSteps = route?.legs?.flatMap { it.steps } ?: return null
            return allSteps.getOrNull(currentStepIndex)
        }

    val nextStep: Step?
        get() {
            val allSteps = route?.legs?.flatMap { it.steps } ?: return null
            return allSteps.getOrNull(currentStepIndex + 1)
        }
}