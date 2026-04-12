package com.pebblemaps.android.ui.navigation

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pebblemaps.android.data.repository.RouteRepository
import com.pebblemaps.android.domain.model.LatLng
import com.pebblemaps.android.domain.model.NavigationState
import com.pebblemaps.android.domain.model.Route
import com.pebblemaps.android.domain.model.RouteProfile
import com.pebblemaps.android.domain.model.TurnDirection
import com.pebblemaps.android.domain.model.WatchFrame
import com.pebblemaps.android.domain.model.WatchRenderConfig
import com.pebblemaps.android.domain.model.toTurnDirection
import com.pebblemaps.android.util.GoogleMapsUrlParser
import com.pebblemaps.android.util.GoogleMapsUrlResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.util.GeoPoint

class NavigationViewModel(
    private val routeRepository: RouteRepository,
    private val urlResolver: GoogleMapsUrlResolver
) : ViewModel() {

    private val _state = MutableStateFlow(NavigationState())
    val state: StateFlow<NavigationState> = _state.asStateFlow()

    private val _watchFrame = MutableStateFlow<WatchFrame?>(null)
    val watchFrame: StateFlow<WatchFrame?> = _watchFrame.asStateFlow()

    private val _navigationEvents = Channel<String>(Channel.BUFFERED)
    val navigationEvents = _navigationEvents.receiveAsFlow()

    fun setRoute(route: Route) {
        _state.value = _state.value.copy(route = route, isLoading = false, error = null)
        updateWatchFrame()
    }

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
        viewModelScope.launch {
            calculateRouteSuspending()
        }
    }

    private suspend fun calculateRouteSuspending() {
        val start = _state.value.startLocation ?: return
        val end = _state.value.endLocation ?: return
        val profile = _state.value.profile

        _state.value = _state.value.copy(isLoading = true, error = null)

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

    fun processSharedUrl(url: String) {
        viewModelScope.launch {
            Log.d("NavigationViewModel", "Processing share URL: $url")
            val resolvedUrl = withContext(Dispatchers.IO) {
                urlResolver.resolveShortUrl(url) ?: url
            }

            val directions = GoogleMapsUrlParser.parseDirectionsUrl(resolvedUrl)
            if (directions == null || directions.end == null) {
                Log.w("NavigationViewModel", "Could not parse directions from URL: $resolvedUrl")
                return@launch
            }

            val start = directions.start
            val end = directions.end
            Log.d("NavigationViewModel", "Parsed directions: start=$start, end=$end")

            start?.let { setStartLocation(it.lat, it.lng) }
            setEndLocation(end.lat, end.lng)

            if (start != null) {
                calculateRouteSuspending()
                if (_state.value.route != null) {
                    Log.d("NavigationViewModel", "Route ready, navigating to activeNavigation")
                    _navigationEvents.send("activeNavigation")
                } else {
                    Log.w("NavigationViewModel", "Route calculation produced no route")
                    _navigationEvents.send("navigation")
                }
            } else {
                Log.d("NavigationViewModel", "No start location, navigating to navigation screen")
                _navigationEvents.send("navigation")
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

    fun reroute(currentLocation: LatLng) {
        val end = _state.value.endLocation ?: return
        val profile = _state.value.profile
        _state.value = _state.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
            try {
                val route = routeRepository.getRoute(
                    org.osmdroid.util.GeoPoint(currentLocation.lat, currentLocation.lng),
                    org.osmdroid.util.GeoPoint(end.lat, end.lng),
                    profile
                )
                _state.value = _state.value.copy(route = route, isLoading = false, error = null)
                updateWatchFrame()
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    error = e.message ?: "Reroute failed",
                    isLoading = false
                )
            }
        }
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