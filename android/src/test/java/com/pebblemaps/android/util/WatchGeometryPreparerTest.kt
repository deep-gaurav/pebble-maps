package com.pebblemaps.android.util

import com.pebblemaps.android.domain.model.LatLng
import com.pebblemaps.android.domain.model.TurnDirection
import com.pebblemaps.android.domain.model.WatchFrame
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class WatchGeometryPreparerTest {

    @Test
    fun sparseStraightRouteKeepsRouteNearCenter() {
        val frame = baseFrame(
            routePoints = listOf(
                offsetMeters(-120.0, 0.0),
                offsetMeters(120.0, 0.0)
            )
        )

        val prepared = WatchGeometryPreparer.prepare(frame)

        assertTrue(prepared.routePoints.size >= 2)
        assertTrue(prepared.routePoints.any { abs(it.xMeters) < 2.0 && abs(it.yMeters) < 2.0 })
    }

    @Test
    fun turnGeometrySurvivesNearCurrentPosition() {
        val frame = baseFrame(
            routePoints = listOf(
                offsetMeters(-60.0, -20.0),
                offsetMeters(0.0, 0.0),
                offsetMeters(0.0, 60.0)
            )
        )

        val prepared = WatchGeometryPreparer.prepare(frame)

        assertTrue(prepared.routePoints.size >= 3)
        assertTrue(prepared.routePoints.any { abs(it.xMeters) < 2.0 && abs(it.yMeters) < 2.0 })
        assertNotNull(prepared.destinationIndex)
    }

    @Test
    fun roadsAreClippedAndRetainedWithinViewportBudget() {
        val frame = baseFrame(
            routePoints = listOf(offsetMeters(-20.0, 0.0), offsetMeters(70.0, 0.0)),
            nearbyRoads = listOf(
                listOf(offsetMeters(-160.0, 25.0), offsetMeters(160.0, 25.0)),
                listOf(offsetMeters(-140.0, -30.0), offsetMeters(140.0, -30.0))
            )
        )

        val prepared = WatchGeometryPreparer.prepare(frame)

        assertFalse(prepared.roadSegments.isEmpty())
        assertTrue(prepared.roadSegments.flatten().all {
            abs(it.xMeters) <= frame.viewportMeters / 2.0 * 1.12 + 0.5 &&
                abs(it.yMeters) <= frame.viewportMeters / 2.0 * 1.12 + 0.5
        })
        assertTrue(prepared.estimatedRoadBytes > 0)
    }

    @Test
    fun roadPriorityFavorsCorridorOverDistantSegments() {
        val frame = baseFrame(
            routePoints = listOf(offsetMeters(-20.0, 0.0), offsetMeters(90.0, 0.0)),
            nearbyRoads = buildList {
                add(listOf(offsetMeters(-100.0, 8.0), offsetMeters(120.0, 8.0)))
                add(listOf(offsetMeters(-100.0, -10.0), offsetMeters(120.0, -10.0)))
                repeat(6) { idx ->
                    val north = 45.0 + idx * 7.0
                    add(listOf(offsetMeters(-90.0, north), offsetMeters(110.0, north)))
                }
            }
        )

        val prepared = WatchGeometryPreparer.prepare(frame)

        assertFalse(prepared.roadSegments.isEmpty())
        val firstSegmentAverageY = prepared.roadSegments.first().map { it.yMeters }.average()
        assertTrue(abs(firstSegmentAverageY) < 20.0)
        assertNotEquals(0, prepared.estimatedRoadBytes)
    }

    private fun baseFrame(
        routePoints: List<LatLng>,
        nearbyRoads: List<List<LatLng>> = emptyList()
    ): WatchFrame {
        return WatchFrame(
            routePoints = routePoints,
            currentLocation = ORIGIN,
            turnDirection = TurnDirection.STRAIGHT,
            distanceToNextTurn = 20.0,
            distanceRemaining = 120.0,
            streetName = "Test Street",
            bearing = 0f,
            viewportMeters = 140.0,
            nearbyRoads = nearbyRoads
        )
    }

    private fun offsetMeters(eastMeters: Double, northMeters: Double): LatLng {
        val metersPerLat = 111320.0
        val metersPerLng = 111320.0
        return LatLng(
            lat = ORIGIN.lat + northMeters / metersPerLat,
            lng = ORIGIN.lng + eastMeters / metersPerLng
        )
    }

    private companion object {
        val ORIGIN = LatLng(0.0, 0.0)
    }
}
