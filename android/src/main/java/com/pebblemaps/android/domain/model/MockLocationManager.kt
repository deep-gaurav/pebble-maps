package com.pebblemaps.android.domain.model

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.*

data class MockLocationState(
    val currentPosition: LatLng,
    val bearing: Float,
    val smoothedBearing: Float,
    val distanceToNextTurn: Double,
    val totalRemainingDistance: Double,
    val currentStepIndex: Int,
    val isRunning: Boolean = false,
    val currentSpeedKmh: Double = 0.0
)

object MockLocationManager {
    private const val TAG = "MockLocationManager"
    private const val TICK_MS = 50L
    private const val BASE_SMOOTHING_FACTOR = 0.2f
    private const val MEDIUM_TURN_SMOOTHING_FACTOR = 0.35f
    private const val SHARP_TURN_SMOOTHING_FACTOR = 0.55f
    
    private var route: Route? = null
    private var allCoords: List<LatLng> = emptyList()
    private var cumulativeDistances: DoubleArray = doubleArrayOf()
    private var totalRouteDistance: Double = 0.0
    private var job: Job? = null
    
    private var distanceTraveled = 0.0
    private var currentStepIndex = 0
    
    // Cumulative distances to the START of each step (maneuver location)
    private var stepStartDistances: List<Double> = emptyList()
    // Distance to next maneuver location
    private var distanceToNextManeuver = 0.0
    
    var speedKmh: Double = 20.0
    
    private val _stateFlow = MutableStateFlow<MockLocationState?>(null)
    val stateFlow = _stateFlow.asStateFlow()
    
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    private var smoothedBearing = 0f
    
    fun setRoute(route: Route) {
        this.route = route
        this.allCoords = route.geometry.coordinates
        this.distanceTraveled = 0.0
        this.currentStepIndex = 0
        this.smoothedBearing = 0f
        
        buildCumulativeDistances()
        buildStepStartDistances()
        
        Log.d(TAG, "setRoute: ${allCoords.size} coords, totalDist=${totalRouteDistance}m, ${stepStartDistances.size} steps")
        
        if (allCoords.size >= 2) {
            val startPos = allCoords[0]
            val bearing = calculateBearing(startPos, allCoords[1])
            this.smoothedBearing = bearing
            
            // Initial distance to next maneuver is distance to first step start
            this.distanceToNextManeuver = if (stepStartDistances.isNotEmpty()) stepStartDistances[0] else 0.0
            
            _stateFlow.value = MockLocationState(
                currentPosition = startPos,
                bearing = bearing,
                smoothedBearing = bearing,
                distanceToNextTurn = distanceToNextManeuver,
                totalRemainingDistance = totalRouteDistance,
                currentStepIndex = 0,
                isRunning = false,
                currentSpeedKmh = 0.0
            )
        } else {
            Log.w(TAG, "  Not enough coordinates: ${allCoords.size}")
            _stateFlow.value = null
        }
    }
    
    private fun buildCumulativeDistances() {
        if (allCoords.size < 2) {
            cumulativeDistances = doubleArrayOf(0.0)
            totalRouteDistance = 0.0
            return
        }
        
        cumulativeDistances = DoubleArray(allCoords.size)
        cumulativeDistances[0] = 0.0
        
        var total = 0.0
        for (i in 1 until allCoords.size) {
            total += haversineDistance(allCoords[i - 1], allCoords[i])
            cumulativeDistances[i] = total
        }
        totalRouteDistance = total
    }
    
    private fun buildStepStartDistances() {
        val r = route ?: return
        stepStartDistances = mutableListOf<Double>().also { list ->
            var cumDist = 0.0
            for (leg in r.legs) {
                for (step in leg.steps) {
                    // The step starts at cumDist (where the maneuver is located)
                    list.add(cumDist)
                    cumDist += step.distance
                }
            }
        }
    }
    
    private fun getPositionAtDistance(dist: Double): Pair<LatLng, Float> {
        if (allCoords.size < 2) return Pair(allCoords.firstOrNull() ?: LatLng(0.0, 0.0), 0f)
        if (dist <= 0) return Pair(allCoords[0], calculateBearing(allCoords[0], allCoords[1]))
        if (dist >= totalRouteDistance) {
            val last = allCoords.last()
            val prev = allCoords[allCoords.size - 2]
            return Pair(last, calculateBearing(prev, last))
        }
        
        var low = 0
        var high = cumulativeDistances.size - 1
        
        while (low < high - 1) {
            val mid = (low + high) / 2
            if (cumulativeDistances[mid] <= dist) {
                low = mid
            } else {
                high = mid
            }
        }
        
        val segStartDist = cumulativeDistances[low]
        val segEndDist = cumulativeDistances[low + 1]
        val segLength = segEndDist - segStartDist
        
        if (segLength < 0.0001) {
            // Very short segment, just return start
            return Pair(allCoords[low], calculateBearing(allCoords[low], allCoords.getOrElse(low + 1) { allCoords[low] }))
        }
        
        val ratio = (dist - segStartDist) / segLength
        val start = allCoords[low]
        val end = allCoords[low + 1]
        
        val lat = start.lat + ratio * (end.lat - start.lat)
        val lng = start.lng + ratio * (end.lng - start.lng)
        
        return Pair(LatLng(lat, lng), calculateBearing(start, end))
    }
    
    fun start() {
        if (job?.isActive == true) {
            Log.d(TAG, "start: already running")
            return
        }
        if (allCoords.size < 2) {
            Log.w(TAG, "start: no coordinates")
            return
        }
        
        Log.d(TAG, "start: beginning navigation")
        job = scope.launch {
            while (isActive && distanceTraveled < totalRouteDistance) {
                val newState = advancePosition()
                _stateFlow.value = newState
                delay(TICK_MS)
            }
            Log.d(TAG, "start: navigation complete")
        }
    }
    
    fun stop() {
        Log.d(TAG, "stop")
        job?.cancel()
        job = null
        _stateFlow.value = _stateFlow.value?.copy(isRunning = false)
    }
    
    fun pause() {
        Log.d(TAG, "pause")
        job?.cancel()
        job = null
    }
    
    fun resume() {
        if (job?.isActive != true && distanceTraveled < totalRouteDistance) {
            Log.d(TAG, "resume")
            start()
        }
    }
    
    private fun advancePosition(): MockLocationState {
        if (distanceTraveled >= totalRouteDistance) {
            return createCompletedState(allCoords.last())
        }
        
        val speedMs = speedKmh / 3.6
        val distThisTick = speedMs * (TICK_MS / 1000.0)
        
        distanceTraveled += distThisTick
        if (distanceTraveled > totalRouteDistance) {
            distanceTraveled = totalRouteDistance
        }
        
        val (position, bearing) = getPositionAtDistance(distanceTraveled)
        smoothedBearing = lerpBearing(smoothedBearing, bearing, smoothingFactorForTurn(smoothedBearing, bearing))
        
        val remaining = totalRouteDistance - distanceTraveled
        
        // Find current step index
        val stepIdx = findStepIndex(distanceTraveled)
        
        // Calculate distance to next maneuver
        val nextManeuverDist = if (stepIdx < stepStartDistances.size - 1) {
            stepStartDistances[stepIdx + 1]
        } else {
            totalRouteDistance
        }
        distanceToNextManeuver = (nextManeuverDist - distanceTraveled).coerceAtLeast(0.0)
        
        return MockLocationState(
            currentPosition = position,
            bearing = bearing,
            smoothedBearing = smoothedBearing,
            distanceToNextTurn = distanceToNextManeuver,
            totalRemainingDistance = remaining,
            currentStepIndex = stepIdx,
            isRunning = true,
            currentSpeedKmh = speedKmh
        )
    }
    
    private fun findStepIndex(dist: Double): Int {
        val r = route ?: return 0
        var stepIdx = 0
        var cumDist = 0.0
        
        for (leg in r.legs) {
            for (step in leg.steps) {
                cumDist += step.distance
                if (dist < cumDist) {
                    return stepIdx
                }
                stepIdx++
            }
        }
        return stepIdx.coerceAtLeast(0)
    }
    
    private fun lerpBearing(from: Float, to: Float, factor: Float): Float {
        var diff = to - from
        if (diff > 180) diff -= 360
        if (diff < -180) diff += 360
        var result = from + diff * factor
        if (result < 0) result += 360
        if (result >= 360) result -= 360
        return result
    }

    private fun smoothingFactorForTurn(from: Float, to: Float): Float {
        var diff = abs(to - from)
        if (diff > 180f) diff = 360f - diff
        return when {
            diff >= 35f -> SHARP_TURN_SMOOTHING_FACTOR
            diff >= 15f -> MEDIUM_TURN_SMOOTHING_FACTOR
            else -> BASE_SMOOTHING_FACTOR
        }
    }
    
    private fun createCompletedState(lastPos: LatLng): MockLocationState {
        return MockLocationState(
            currentPosition = lastPos,
            bearing = 0f,
            smoothedBearing = smoothedBearing,
            distanceToNextTurn = 0.0,
            totalRemainingDistance = 0.0,
            currentStepIndex = 0,
            isRunning = false,
            currentSpeedKmh = 0.0
        )
    }
    
    private fun calculateBearing(from: LatLng, to: LatLng): Float {
        val lat1 = Math.toRadians(from.lat)
        val lat2 = Math.toRadians(to.lat)
        val dLng = Math.toRadians(to.lng - from.lng)
        
        val x = sin(dLng) * cos(lat2)
        val y = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLng)
        
        var bearing = Math.toDegrees(atan2(x, y)).toFloat()
        bearing = (bearing + 360) % 360
        return bearing
    }
    
    private fun haversineDistance(from: LatLng, to: LatLng): Double {
        val R = 6371000.0
        val lat1 = Math.toRadians(from.lat)
        val lat2 = Math.toRadians(to.lat)
        val dLat = Math.toRadians(to.lat - from.lat)
        val dLng = Math.toRadians(to.lng - from.lng)
        
        val a = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLng / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        
        return R * c
    }
}
