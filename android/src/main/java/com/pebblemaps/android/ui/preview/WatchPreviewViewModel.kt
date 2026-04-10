package com.pebblemaps.android.ui.preview

import androidx.lifecycle.ViewModel
import com.pebblemaps.android.domain.model.LatLng
import com.pebblemaps.android.domain.model.TurnDirection
import com.pebblemaps.android.domain.model.WatchFrame
import com.pebblemaps.android.domain.model.WatchRenderConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class WatchPreviewViewModel : ViewModel() {

    private val _config = MutableStateFlow(WatchRenderConfig())
    val config: StateFlow<WatchRenderConfig> = _config.asStateFlow()

    private val _frame = MutableStateFlow<WatchFrame?>(null)
    val frame: StateFlow<WatchFrame?> = _frame.asStateFlow()

    fun setPreviewFrame(
        routePoints: List<LatLng>,
        currentLocation: LatLng,
        turnDirection: TurnDirection,
        distanceToNextTurn: Double,
        distanceRemaining: Double,
        streetName: String?
    ) {
        _frame.value = WatchFrame(
            routePoints = routePoints,
            currentLocation = currentLocation,
            turnDirection = turnDirection,
            distanceToNextTurn = distanceToNextTurn,
            distanceRemaining = distanceRemaining,
            streetName = streetName
        )
    }

    fun updateConfig(width: Int, height: Int) {
        _config.value = WatchRenderConfig(width = width, height = height)
    }
}