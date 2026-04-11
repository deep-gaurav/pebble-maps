package com.pebblemaps.android.util

import com.pebblemaps.android.domain.model.LatLng
import kotlin.math.abs

object PathSimplifier {

    fun simplify(points: List<LatLng>, epsilon: Double): List<LatLng> {
        if (points.size <= 2) return points.toList()

        val indices = mutableListOf<Int>()
        indices.add(0)
        douglasPeucker(points, 0, points.size - 1, epsilon, indices)
        indices.add(points.size - 1)
        indices.sort()

        return indices.map { points[it] }
    }

    fun simplifyToBudget(
        points: List<LatLng>,
        targetCount: Int,
        maxEpsilon: Double = 0.0001
    ): List<LatLng> {
        if (points.size <= targetCount) return points.toList()

        var low = 0.0
        var high = maxEpsilon
        var result = points

        for (i in 0..20) {
            val mid = (low + high) / 2
            val simplified = simplify(points, mid)
            if (simplified.size <= targetCount) {
                result = simplified
                high = mid
            } else {
                low = mid
            }
        }

        return result
    }

    fun simplifyViewport(points: List<ViewportPoint>, epsilon: Double): List<ViewportPoint> {
        if (points.size <= 2) return points.toList()

        val indices = mutableListOf<Int>()
        indices.add(0)
        douglasPeuckerViewport(points, 0, points.size - 1, epsilon, indices)
        indices.add(points.size - 1)
        indices.sort()

        return indices.map { points[it] }
    }

    fun simplifyViewportToBudget(
        points: List<ViewportPoint>,
        targetCount: Int,
        maxEpsilon: Double
    ): List<ViewportPoint> {
        if (points.size <= targetCount) return points.toList()

        var low = 0.0
        var high = maxEpsilon
        var result = points

        repeat(21) {
            val mid = (low + high) / 2
            val simplified = simplifyViewport(points, mid)
            if (simplified.size <= targetCount) {
                result = simplified
                high = mid
            } else {
                low = mid
            }
        }

        return result
    }

    private fun douglasPeucker(
        points: List<LatLng>,
        start: Int,
        end: Int,
        epsilon: Double,
        indices: MutableList<Int>
    ) {
        if (end - start < 2) return

        var maxDist = 0.0
        var maxIdx = start

        for (i in (start + 1) until end) {
            val dist = perpendicularDistance(points[i], points[start], points[end])
            if (dist > maxDist) {
                maxDist = dist
                maxIdx = i
            }
        }

        if (maxDist > epsilon) {
            indices.add(maxIdx)
            douglasPeucker(points, start, maxIdx, epsilon, indices)
            douglasPeucker(points, maxIdx, end, epsilon, indices)
        }
    }

    private fun perpendicularDistance(point: LatLng, lineStart: LatLng, lineEnd: LatLng): Double {
        val dx = lineEnd.lng - lineStart.lng
        val dy = lineEnd.lat - lineStart.lat
        val lenSq = dx * dx + dy * dy

        if (lenSq < 1e-20) {
            val dLng = point.lng - lineStart.lng
            val dLat = point.lat - lineStart.lat
            return kotlin.math.sqrt(dLng * dLng + dLat * dLat)
        }

        val num = abs(
            dy * point.lng - dx * point.lat + lineEnd.lng * lineStart.lat - lineEnd.lat * lineStart.lng
        )
        return num / kotlin.math.sqrt(lenSq)
    }

    private fun douglasPeuckerViewport(
        points: List<ViewportPoint>,
        start: Int,
        end: Int,
        epsilon: Double,
        indices: MutableList<Int>
    ) {
        if (end - start < 2) return

        var maxDist = 0.0
        var maxIdx = start

        for (i in (start + 1) until end) {
            val dist = perpendicularDistanceViewport(points[i], points[start], points[end])
            if (dist > maxDist) {
                maxDist = dist
                maxIdx = i
            }
        }

        if (maxDist > epsilon) {
            indices.add(maxIdx)
            douglasPeuckerViewport(points, start, maxIdx, epsilon, indices)
            douglasPeuckerViewport(points, maxIdx, end, epsilon, indices)
        }
    }

    private fun perpendicularDistanceViewport(
        point: ViewportPoint,
        lineStart: ViewportPoint,
        lineEnd: ViewportPoint
    ): Double {
        val dx = lineEnd.xMeters - lineStart.xMeters
        val dy = lineEnd.yMeters - lineStart.yMeters
        val lenSq = dx * dx + dy * dy

        if (lenSq < 1e-20) {
            val px = point.xMeters - lineStart.xMeters
            val py = point.yMeters - lineStart.yMeters
            return kotlin.math.sqrt(px * px + py * py)
        }

        val num = abs(
            dy * point.xMeters - dx * point.yMeters +
                lineEnd.xMeters * lineStart.yMeters -
                lineEnd.yMeters * lineStart.xMeters
        )
        return num / kotlin.math.sqrt(lenSq)
    }
}
