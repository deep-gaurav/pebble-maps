package com.pebblemaps.android.data

import android.util.Log
import com.pebblemaps.android.data.remote.OverpassApi
import com.pebblemaps.android.domain.model.LatLng
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.sqrt

class RoadCache(private val overpassApi: OverpassApi) {

    companion object {
        private const val TAG = "RoadCache"
        private const val MIN_REFRESH_DISTANCE = 60.0
        private const val MAX_REFRESH_DISTANCE = 140.0
        private const val MIN_QUERY_RADIUS = 120.0
        private const val MAX_QUERY_RADIUS = 260.0
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var cachedRoads: List<List<LatLng>> = emptyList()
    private var lastQueryCenter: LatLng? = null
    private var isRefreshing = false

    fun getRoads(): List<List<LatLng>> = cachedRoads

    fun refreshIfNeeded(location: LatLng, viewportMeters: Double) {
        val refreshDistance = (viewportMeters * 0.45).coerceIn(MIN_REFRESH_DISTANCE, MAX_REFRESH_DISTANCE)
        val queryRadius = (viewportMeters * 0.95).coerceIn(MIN_QUERY_RADIUS, MAX_QUERY_RADIUS)
        val last = lastQueryCenter
        if (last != null && meterDistance(last, location) < refreshDistance) return
        if (isRefreshing) return

        isRefreshing = true
        lastQueryCenter = location

        scope.launch {
            try {
                val roads = withContext(Dispatchers.IO) {
                    overpassApi.fetchRoads(location, queryRadius)
                }
                cachedRoads = roads
                Log.d(TAG, "Fetched ${roads.size} road segments for radius=$queryRadius")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch roads: ${e.message}")
            } finally {
                isRefreshing = false
            }
        }
    }

    private fun meterDistance(a: LatLng, b: LatLng): Double {
        val dLat = (a.lat - b.lat) * 111320.0
        val dLng = (a.lng - b.lng) * 111320.0 * cos(Math.toRadians((a.lat + b.lat) / 2.0))
        return sqrt(dLat * dLat + dLng * dLng)
    }
}
