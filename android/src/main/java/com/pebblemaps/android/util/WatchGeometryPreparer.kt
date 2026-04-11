package com.pebblemaps.android.util

import com.pebblemaps.android.domain.model.LatLng
import com.pebblemaps.android.domain.model.WatchFrame
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

data class ViewportPoint(
    val xMeters: Double,
    val yMeters: Double
)

data class PreparedWatchGeometry(
    val routePoints: List<ViewportPoint>,
    val destinationIndex: Int?,
    val roadSegments: List<List<ViewportPoint>>,
    val estimatedRoadBytes: Int
)

object WatchGeometryPreparer {

    private const val ROUTE_MAX_POINTS = 60
    private const val ROUTE_LOOK_BEHIND_FACTOR = 0.45
    private const val ROUTE_LOOK_AHEAD_FACTOR = 1.8
    private const val ROUTE_MARGIN_FACTOR = 0.2
    private const val ROAD_MARGIN_FACTOR = 0.12
    private const val ROUTE_CORRIDOR_METERS = 42.0
    private const val ROAD_MAX_BYTES = 320

    fun prepare(frame: WatchFrame): PreparedWatchGeometry {
        val basis = ProjectionBasis(frame.currentLocation, frame.bearing)
        val halfViewport = frame.viewportMeters / 2.0

        val projectedDestination = frame.routePoints.lastOrNull()?.let { basis.project(it) }
        val routePoints = prepareRoute(frame.routePoints, basis, halfViewport)
        val roadSegments = prepareRoads(frame.nearbyRoads, basis, halfViewport, routePoints)
        val destinationIndex = if (projectedDestination != null && isInside(projectedDestination, halfViewport)) {
            routePoints.indexOfLast { distance(it, projectedDestination) <= 2.0 }.takeIf { it >= 0 }
        } else {
            null
        }
        val estimatedRoadBytes = roadSegments.sumOf { it.size * 2 + 2 }

        return PreparedWatchGeometry(
            routePoints = routePoints,
            destinationIndex = destinationIndex,
            roadSegments = roadSegments,
            estimatedRoadBytes = estimatedRoadBytes
        )
    }

    private fun prepareRoute(
        route: List<LatLng>,
        basis: ProjectionBasis,
        halfViewport: Double
    ): List<ViewportPoint> {
        if (route.isEmpty()) return emptyList()

        val projected = route.map { basis.project(it) }
        if (projected.size == 1) {
            return listOf(projected.first()).filter { isInside(it, halfViewport) }
        }

        val nearest = findNearestSegment(projected)
        val anchorInsertion = insertAnchor(projected, nearest.index, nearest.point)
        val withAnchor = anchorInsertion.points
        val anchorIndex = anchorInsertion.anchorIndex

        val behindDistance = max(halfViewport * ROUTE_LOOK_BEHIND_FACTOR, 25.0)
        val aheadDistance = max(halfViewport * ROUTE_LOOK_AHEAD_FACTOR, 60.0)
        val routeWindow = sliceAroundAnchor(withAnchor, anchorIndex, behindDistance, aheadDistance)
        val clipped = clipPolyline(routeWindow, halfViewport * (1.0 + ROUTE_MARGIN_FACTOR))
        if (clipped.size < 2) {
            return clipped.take(ROUTE_MAX_POINTS)
        }

        return PathSimplifier.simplifyViewportToBudget(
            clipped,
            ROUTE_MAX_POINTS,
            maxEpsilon = max(halfViewport / 24.0, 2.0)
        )
    }

    private fun prepareRoads(
        roads: List<List<LatLng>>,
        basis: ProjectionBasis,
        halfViewport: Double,
        routePoints: List<ViewportPoint>
    ): List<List<ViewportPoint>> {
        if (roads.isEmpty()) return emptyList()

        val prepared = roads.mapNotNull { segment ->
            if (segment.size < 2) return@mapNotNull null
            val projected = segment.map { basis.project(it) }
            val clipped = clipPolyline(projected, halfViewport * (1.0 + ROAD_MARGIN_FACTOR))
            if (clipped.size < 2) return@mapNotNull null

            val simplified = PathSimplifier.simplifyViewport(
                clipped,
                max(halfViewport / 36.0, 1.5)
            )
            if (simplified.size < 2) return@mapNotNull null
            val minToOrigin = minDistanceToOrigin(simplified)
            val minToRoute = minDistanceToPolyline(simplified, routePoints)
            val score = minToOrigin + minToRoute * 0.65
            val corridorBonus = if (minToRoute <= ROUTE_CORRIDOR_METERS) -20.0 else 0.0
            PreparedRoadSegment(
                points = simplified,
                sortScore = score + corridorBonus
            )
        }

        var usedBytes = 0
        return prepared
            .sortedBy { it.sortScore }
            .mapNotNull { segment ->
                val segmentBytes = segment.points.size * 2 + 2
                if (usedBytes + segmentBytes > ROAD_MAX_BYTES) {
                    null
                } else {
                    usedBytes += segmentBytes
                    segment.points
                }
            }
    }

    private fun insertAnchor(
        points: List<ViewportPoint>,
        segmentIndex: Int,
        anchor: ViewportPoint
    ): AnchorInsertion {
        val start = points[segmentIndex]
        val end = points[segmentIndex + 1]
        if (distance(start, anchor) < 0.5 || distance(end, anchor) < 0.5) {
            val anchorIndex = if (distance(start, anchor) <= distance(end, anchor)) segmentIndex else segmentIndex + 1
            return AnchorInsertion(points, anchorIndex)
        }

        val inserted = buildList(points.size + 1) {
            addAll(points.subList(0, segmentIndex + 1))
            add(anchor)
            addAll(points.subList(segmentIndex + 1, points.size))
        }
        return AnchorInsertion(inserted, segmentIndex + 1)
    }

    private fun sliceAroundAnchor(
        points: List<ViewportPoint>,
        anchorIndex: Int,
        behindDistance: Double,
        aheadDistance: Double
    ): List<ViewportPoint> {
        var start = anchorIndex
        var remainingBehind = behindDistance
        while (start > 0 && remainingBehind > 0.0) {
            remainingBehind -= distance(points[start], points[start - 1])
            start--
        }

        var end = anchorIndex
        var remainingAhead = aheadDistance
        while (end < points.lastIndex && remainingAhead > 0.0) {
            remainingAhead -= distance(points[end], points[end + 1])
            end++
        }

        return points.subList(start, end + 1)
    }

    private fun clipPolyline(points: List<ViewportPoint>, halfExtent: Double): List<ViewportPoint> {
        if (points.size < 2) return points.filter { isInside(it, halfExtent) }

        val output = mutableListOf<ViewportPoint>()
        for (i in 0 until points.lastIndex) {
            val clipped = clipSegment(points[i], points[i + 1], halfExtent) ?: continue
            if (output.isEmpty() || distance(output.last(), clipped.first) > 0.25) {
                output.add(clipped.first)
            }
            if (distance(output.last(), clipped.second) > 0.25) {
                output.add(clipped.second)
            }
        }
        return output
    }

    private fun clipSegment(
        start: ViewportPoint,
        end: ViewportPoint,
        halfExtent: Double
    ): Pair<ViewportPoint, ViewportPoint>? {
        var t0 = 0.0
        var t1 = 1.0
        val dx = end.xMeters - start.xMeters
        val dy = end.yMeters - start.yMeters

        fun clip(p: Double, q: Double): Boolean {
            if (p == 0.0) return q >= 0.0
            val r = q / p
            return if (p < 0.0) {
                if (r > t1) {
                    false
                } else {
                    if (r > t0) t0 = r
                    true
                }
            } else {
                if (r < t0) {
                    false
                } else {
                    if (r < t1) t1 = r
                    true
                }
            }
        }

        if (!clip(-dx, start.xMeters + halfExtent)) return null
        if (!clip(dx, halfExtent - start.xMeters)) return null
        if (!clip(-dy, start.yMeters + halfExtent)) return null
        if (!clip(dy, halfExtent - start.yMeters)) return null
        if (t0 > t1) return null

        return interpolate(start, end, t0) to interpolate(start, end, t1)
    }

    private fun interpolate(start: ViewportPoint, end: ViewportPoint, t: Double): ViewportPoint {
        return ViewportPoint(
            xMeters = start.xMeters + (end.xMeters - start.xMeters) * t,
            yMeters = start.yMeters + (end.yMeters - start.yMeters) * t
        )
    }

    private fun findNearestSegment(points: List<ViewportPoint>): SegmentProjection {
        var best = SegmentProjection(0, points.first(), squaredDistance(points.first()))
        for (i in 0 until points.lastIndex) {
            val projection = projectOriginOntoSegment(points[i], points[i + 1])
            if (projection.distanceSq < best.distanceSq) {
                best = SegmentProjection(i, projection.point, projection.distanceSq)
            }
        }
        return best
    }

    private fun projectOriginOntoSegment(
        start: ViewportPoint,
        end: ViewportPoint
    ): OriginProjection {
        val dx = end.xMeters - start.xMeters
        val dy = end.yMeters - start.yMeters
        val lenSq = dx * dx + dy * dy
        if (lenSq < 1e-9) {
            return OriginProjection(start, squaredDistance(start))
        }

        val t = ((-start.xMeters * dx) + (-start.yMeters * dy)) / lenSq
        val clamped = t.coerceIn(0.0, 1.0)
        val point = interpolate(start, end, clamped)
        return OriginProjection(point, squaredDistance(point))
    }

    private fun minDistanceToOrigin(points: List<ViewportPoint>): Double {
        return points.minOfOrNull { sqrt(squaredDistance(it)) } ?: Double.MAX_VALUE
    }

    private fun minDistanceToPolyline(points: List<ViewportPoint>, route: List<ViewportPoint>): Double {
        if (points.isEmpty() || route.size < 2) return Double.MAX_VALUE

        var best = Double.MAX_VALUE
        for (point in points) {
            for (i in 0 until route.lastIndex) {
                val projection = projectPointOntoSegment(point, route[i], route[i + 1])
                if (projection < best) best = projection
            }
        }
        return best
    }

    private fun projectPointOntoSegment(
        point: ViewportPoint,
        start: ViewportPoint,
        end: ViewportPoint
    ): Double {
        val dx = end.xMeters - start.xMeters
        val dy = end.yMeters - start.yMeters
        val lenSq = dx * dx + dy * dy
        if (lenSq < 1e-9) return distance(point, start)

        val t = (((point.xMeters - start.xMeters) * dx) + ((point.yMeters - start.yMeters) * dy)) / lenSq
        val clamped = t.coerceIn(0.0, 1.0)
        val nearest = interpolate(start, end, clamped)
        return distance(point, nearest)
    }

    private fun squaredDistance(point: ViewportPoint): Double {
        return point.xMeters * point.xMeters + point.yMeters * point.yMeters
    }

    private fun distance(a: ViewportPoint, b: ViewportPoint): Double {
        val dx = a.xMeters - b.xMeters
        val dy = a.yMeters - b.yMeters
        return sqrt(dx * dx + dy * dy)
    }

    private fun isInside(point: ViewportPoint, halfExtent: Double): Boolean {
        return point.xMeters in -halfExtent..halfExtent && point.yMeters in -halfExtent..halfExtent
    }

    private data class ProjectionBasis(
        val center: LatLng,
        val bearingDegrees: Float
    ) {
        private val bearingRad = Math.toRadians(bearingDegrees.toDouble())
        private val cosBearing = cos(bearingRad)
        private val sinBearing = sin(bearingRad)
        private val metersPerLat = 111320.0
        private val metersPerLng = 111320.0 * cos(Math.toRadians(center.lat))

        fun project(point: LatLng): ViewportPoint {
            val dLat = point.lat - center.lat
            val dLng = point.lng - center.lng
            val east = dLng * metersPerLng
            val north = dLat * metersPerLat
            return ViewportPoint(
                xMeters = east * cosBearing - north * sinBearing,
                yMeters = east * sinBearing + north * cosBearing
            )
        }
    }

    private data class SegmentProjection(
        val index: Int,
        val point: ViewportPoint,
        val distanceSq: Double
    )

    private data class OriginProjection(
        val point: ViewportPoint,
        val distanceSq: Double
    )

    private data class AnchorInsertion(
        val points: List<ViewportPoint>,
        val anchorIndex: Int
    )

    private data class PreparedRoadSegment(
        val points: List<ViewportPoint>,
        val sortScore: Double
    )
}
