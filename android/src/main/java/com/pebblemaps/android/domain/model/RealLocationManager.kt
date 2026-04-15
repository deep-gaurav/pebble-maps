package com.pebblemaps.android.domain.model

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object RealLocationManager {

    private const val TAG = "RealLocationManager"

    private val _locationState = MutableStateFlow<NavigationLocationState?>(null)
    val locationState = _locationState.asStateFlow()

    private var fusedLocationClient: FusedLocationProviderClient? = null
    private var locationCallback: LocationCallback? = null

    private var currentRoute: Route? = null
    private var smoothedSpeedKmh = 0.0
    private const val SPEED_SMOOTHING_ALPHA = 0.3

    @SuppressLint("MissingPermission")
    fun startNavigation(route: Route, context: Context) {
        if (fusedLocationClient != null) {
            Log.d(TAG, "Already started")
            return
        }
        currentRoute = route
        RouteProgressTracker.setRoute(route)

        val client = LocationServices.getFusedLocationProviderClient(context)
        fusedLocationClient = client

        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            1000L
        ).apply {
            setMinUpdateIntervalMillis(500L)
            setMinUpdateDistanceMeters(2f)
        }.build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                val latLng = LatLng(loc.latitude, loc.longitude)
                val rawSpeedKmh = (loc.speed * 3.6).coerceAtLeast(0.0)
                smoothedSpeedKmh = SPEED_SMOOTHING_ALPHA * rawSpeedKmh + (1.0 - SPEED_SMOOTHING_ALPHA) * smoothedSpeedKmh
                val state = RouteProgressTracker.updateLocation(latLng)
                    .copy(currentSpeedKmh = rawSpeedKmh, smoothedSpeedKmh = smoothedSpeedKmh)
                _locationState.value = state
            }
        }

        client.requestLocationUpdates(
            request,
            locationCallback!!,
            Looper.getMainLooper()
        )
        Log.d(TAG, "Location updates started")
    }

    fun stopNavigation() {
        locationCallback?.let { callback ->
            fusedLocationClient?.removeLocationUpdates(callback)
        }
        locationCallback = null
        fusedLocationClient = null
        currentRoute = null
        smoothedSpeedKmh = 0.0
        _locationState.value = null
        Log.d(TAG, "Location updates stopped")
    }

    fun updateRoute(newRoute: Route) {
        currentRoute = newRoute
        RouteProgressTracker.setRoute(newRoute)
        Log.d(TAG, "Route updated")
    }
}
