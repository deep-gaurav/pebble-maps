package com.pebblemaps.android.domain.model

data class NavigationLocationState(
    val currentPosition: LatLng,
    val bearing: Float,
    val smoothedBearing: Float,
    val distanceToNextTurn: Double,
    val totalRemainingDistance: Double,
    val currentStepIndex: Int,
    val distanceFromRoute: Double,
    val currentSpeedKmh: Double = 0.0,
    val smoothedSpeedKmh: Double = 0.0
)
