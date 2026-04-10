package com.pebblemaps.android.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pebblemaps.android.data.repository.RouteRepository
import com.pebblemaps.android.domain.model.LatLng
import com.pebblemaps.android.domain.model.NavigationState
import com.pebblemaps.android.domain.model.RouteProfile
import com.pebblemaps.android.domain.model.TurnDirection
import com.pebblemaps.android.domain.model.WatchFrame
import com.pebblemaps.android.domain.model.WatchRenderConfig
import com.pebblemaps.android.domain.model.toTurnDirection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint

class NavigationViewModel(
    private val routeRepository: RouteRepository
) : ViewModel() {

    private val _state = MutableStateFlow(NavigationState())
    val state: StateFlow<NavigationState> = _state.asStateFlow()

    private val _watchFrame = MutableStateFlow<WatchFrame?>(null)
    val watchFrame: StateFlow<WatchFrame?> = _watchFrame.asStateFlow()

    fun setStartLocation(lat: Double, lng: Double) {
        _state.value = _state.value.copy(startLocation = LatLng(lat, lng))
    }

    fun setEndLocation(lat: Double, lng: Double) {
        _state.value = _state.value.copy(endLocation = LatLng(lat, lng))
    }

    fun setCurrentLocation(lat: Double, lng: Double) {
        _state.value = _state.value.copy(currentLocation = LatLng(lat, lng))
        updateWatchFrame()
    }

    fun setProfile(profile: RouteProfile) {
        _state.value = _state.value.copy(profile = profile)
    }

    fun calculateRoute() {
        val start = _state.value.startLocation ?: return
        val end = _state.value.endLocation ?: return
        val profile = _state.value.profile

        _state.value = _state.value.copy(isLoading = true, error = null)

        viewModelScope.launch {
            try {
                val route = routeRepository.getRoute(
                    GeoPoint(start.lat, start.lng),
                    GeoPoint(end.lat, end.lng),
                    profile
                )
                _state.value = _state.value.copy(route = route, isLoading = false)
                updateWatchFrame()
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    error = e.message ?: "Route calculation failed",
                    isLoading = false
                )
            }
        }
    }

    private fun updateWatchFrame() {
        val navState = _state.value
        val currentLocation = navState.currentLocation ?: return
        val route = navState.route ?: return

        val currentStep = navState.currentStep

        _watchFrame.value = WatchFrame(
            routePoints = route.geometry.coordinates,
            currentLocation = currentLocation,
            turnDirection = currentStep?.maneuver?.toTurnDirection() ?: TurnDirection.NONE,
            distanceToNextTurn = currentStep?.distance ?: 0.0,
            distanceRemaining = route.legs.sumOf { leg ->
                leg.steps.sumOf { it.distance }
            },
            streetName = currentStep?.maneuver?.type
        )
    }

    fun moveToNextStep() {
        val navState = _state.value
        val route = navState.route ?: return
        val totalSteps = route.legs.sumOf { it.steps.size }

        if (navState.currentStepIndex < totalSteps - 1) {
            _state.value = navState.copy(currentStepIndex = navState.currentStepIndex + 1)
            updateWatchFrame()
        }
    }

    fun getWatchConfig(): WatchRenderConfig {
        return WatchRenderConfig()
    }
}