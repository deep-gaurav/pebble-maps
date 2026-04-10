package com.pebblemaps.android.ui.map

import androidx.lifecycle.ViewModel
import com.pebblemaps.android.data.repository.RouteRepository
import com.pebblemaps.android.domain.model.NavigationState
import com.pebblemaps.android.domain.model.RouteProfile
import org.osmdroid.util.GeoPoint

class MapViewModel(
    private val routeRepository: RouteRepository
) : ViewModel() {

    private val _state = NavigationState()
    val state: NavigationState = _state

    fun setCurrentLocation(location: GeoPoint) {
        // In real app, update state via StateFlow
    }

    fun setStartLocation(location: GeoPoint) {
        // In real app, update state via StateFlow
    }

    fun setEndLocation(location: GeoPoint) {
        // In real app, update state via StateFlow
    }
}