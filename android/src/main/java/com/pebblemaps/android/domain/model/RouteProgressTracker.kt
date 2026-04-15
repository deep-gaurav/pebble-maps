package com.pebblemaps.android.domain.model

import kotlin.math.*

object RouteProgressTracker {

    private const val BASE_SMOOTHING_FACTOR = 0.2f
    private const val MEDIUM_TURN_SMOOTHING_FACTOR = 0.35f
    private const val SHARP_TURN_SMOOTHING_FACTOR = 0.55f

    private const val LOOK_BEHIND_SEGMENTS = 20
    private const val LOOK_AHEAD_SEGMENTS = 50
    private const val BACKWARD_SNAP_THRESHOLD_METERS = 25.0

    private var route: Route? = null
    private var allCoords: List<LatLng> = emptyList()
    private var cumulativeDistances: DoubleArray = doubleArrayOf()
    private var totalRouteDistance: Double = 0.0
    private var stepStartDistances: List<Double> = emptyList()

    private var smoothedBearing = 0f
    private var lastProgressIndex = 0

    fun setRoute(route: Route) {
        this.route = route
        this.allCoords = route.geometry.coordinates
        this.smoothedBearing = 0f
        this.lastProgressIndex = 0

        buildCumulativeDistances()
        buildStepStartDistances()

        if (allCoords.size >= 2) {
            val startPos = allCoords[0]
            val bearing = calculateBearing(startPos, allCoords[1])
            this.smoothedBearing = bearing
        }
    }

    fun updateLocation(location: LatLng): NavigationLocationState {
        if (allCoords.size < 2) {
            return NavigationLocationState(
                currentPosition = location,
                bearing = 0f,
                smoothedBearing = smoothedBearing,
                distanceToNextTurn = 0.0,
                totalRemainingDistance = 0.0,
                currentStepIndex = 0,
                distanceFromRoute = 0.0,
                currentSpeedKmh = 0.0
            )
        }

        val (nearestIndex, projection, distanceFromRoute) = findNearestSegmentWindowed(location)
        val segStart = allCoords[nearestIndex]
        val segEnd = allCoords[nearestIndex + 1]
        val segLength = haversineDistance(segStart, segEnd)

        val distanceAlongSegment = if (segLength > 0.0001) {
            val ratio = projectRatio(location, segStart, segEnd)
            ratio * segLength
        } else 0.0

        val distanceTraveled = cumulativeDistances[nearestIndex] + distanceAlongSegment
        val remaining = (totalRouteDistance - distanceTraveled).coerceAtLeast(0.0)

        if (nearestIndex > lastProgressIndex) {
            lastProgressIndex = nearestIndex
        }

        val bearing = calculateBearing(segStart, segEnd)
        smoothedBearing = lerpBearing(smoothedBearing, bearing, smoothingFactorForTurn(smoothedBearing, bearing))

        val stepIdx = findStepIndex(distanceTraveled)

        val nextManeuverDist = if (stepIdx < stepStartDistances.size - 1) {
            stepStartDistances[stepIdx + 1]
        } else {
            totalRouteDistance
        }
        val distanceToNextManeuver = (nextManeuverDist - distanceTraveled).coerceAtLeast(0.0)

        return NavigationLocationState(
            currentPosition = projection,
            bearing = bearing,
            smoothedBearing = smoothedBearing,
            distanceToNextTurn = distanceToNextManeuver,
            totalRemainingDistance = remaining,
            currentStepIndex = stepIdx,
            distanceFromRoute = distanceFromRoute,
            currentSpeedKmh = 0.0
        )
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
                    list.add(cumDist)
                    cumDist += step.distance
                }
            }
        }
    }

    private fun findNearestSegmentWindowed(location: LatLng): Triple<Int, LatLng, Double> {
        val maxIdx = allCoords.lastIndex

        if (lastProgressIndex == 0 || maxIdx < 2) {
            return findNearestSegmentInRange(location, 0, maxIdx)
        }

        val windowStart = (lastProgressIndex - LOOK_BEHIND_SEGMENTS).coerceAtLeast(0)
        val windowEnd = (lastProgressIndex + LOOK_AHEAD_SEGMENTS).coerceAtMost(maxIdx)

        val (bestIdx, bestProj, bestDist) = findNearestSegmentInRange(location, windowStart, windowEnd)

        if (bestIdx >= lastProgressIndex) {
            return Triple(bestIdx, bestProj, bestDist)
        }

        val (fwdIdx, fwdProj, fwdDist) = findNearestSegmentInRange(location, lastProgressIndex, windowEnd)
        if (fwdDist < BACKWARD_SNAP_THRESHOLD_METERS) {
            return Triple(fwdIdx, fwdProj, fwdDist)
        }

        return Triple(bestIdx, bestProj, fwdDist)
    }

    private fun findNearestSegmentInRange(location: LatLng, fromIdx: Int, toIdx: Int): Triple<Int, LatLng, Double> {
        var bestIdx = fromIdx
        var bestProj = allCoords[fromIdx]
        var bestDist = Double.MAX_VALUE

        val end = toIdx.coerceAtMost(allCoords.lastIndex)
        for (i in fromIdx until end) {
            val start = allCoords[i]
            val segEnd = allCoords[i + 1]
            val (proj, dist) = projectOntoSegment(location, start, segEnd)
            if (dist < bestDist) {
                bestDist = dist
                bestProj = proj
                bestIdx = i
            }
        }
        return Triple(bestIdx, bestProj, bestDist)
    }

    private fun projectOntoSegment(point: LatLng, start: LatLng, end: LatLng): Pair<LatLng, Double> {
        val dLat = end.lat - start.lat
        val dLng = end.lng - start.lng
        val lenSq = dLat * dLat + dLng * dLng
        if (lenSq < 1e-18) {
            return Pair(start, haversineDistance(point, start))
        }
        val t = ((point.lat - start.lat) * dLat + (point.lng - start.lng) * dLng) / lenSq
        val clamped = t.coerceIn(0.0, 1.0)
        val proj = LatLng(start.lat + clamped * dLat, start.lng + clamped * dLng)
        return Pair(proj, haversineDistance(point, proj))
    }

    private fun projectRatio(point: LatLng, start: LatLng, end: LatLng): Double {
        val dLat = end.lat - start.lat
        val dLng = end.lng - start.lng
        val lenSq = dLat * dLat + dLng * dLng
        if (lenSq < 1e-18) return 0.0
        val t = ((point.lat - start.lat) * dLat + (point.lng - start.lng) * dLng) / lenSq
        return t.coerceIn(0.0, 1.0)
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
