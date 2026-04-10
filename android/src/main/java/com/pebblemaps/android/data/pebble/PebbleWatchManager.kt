package com.pebblemaps.android.data.pebble

import android.content.Context
import android.util.Log
import com.getpebble.android.kit.PebbleKit
import com.getpebble.android.kit.util.PebbleDictionary
import com.pebblemaps.android.domain.model.LatLng
import com.pebblemaps.android.domain.model.WatchFrame
import java.util.UUID
import kotlin.math.sqrt

class PebbleWatchManager(private val context: Context) {

    companion object {
        private const val TAG = "PebbleWatchManager"
        val APP_UUID: UUID = UUID.fromString("7ead5e7d-a91c-4875-a314-d7685b456df7")

        const val KEY_TURN_DIRECTION = 0
        const val KEY_DISTANCE_TO_TURN = 1
        const val KEY_DISTANCE_REMAINING = 2
        const val KEY_STREET_NAME = 3
        const val KEY_NUM_ROUTE_POINTS = 4
        const val KEY_ROUTE_POINTS = 5
        const val KEY_CURRENT_LOC_INDEX = 6

        const val MAX_ROUTE_POINTS = 20
    }

    fun isPebbleConnected(): Boolean {
        val connected = PebbleKit.isWatchConnected(context)
        Log.d(TAG, "isPebbleConnected: $connected")
        return connected
    }

    fun launchWatchApp() {
        Log.d(TAG, "launchWatchApp: starting app with UUID $APP_UUID")
        PebbleKit.startAppOnPebble(context, APP_UUID)
    }

    fun sendWatchFrame(frame: WatchFrame) {
        val connected = isPebbleConnected()
        if (!connected) {
            Log.w(TAG, "sendWatchFrame: provider says not connected, but sending broadcast anyway")
        }

        Log.d(TAG, "sendWatchFrame: turn=${frame.turnDirection}, dist=${frame.distanceToNextTurn}, remaining=${frame.distanceRemaining}, street=${frame.streetName}, points=${frame.routePoints.size}")

        val dict = PebbleDictionary()

        dict.addUint8(KEY_TURN_DIRECTION, frame.turnDirection.ordinal.toByte())
        dict.addInt32(KEY_DISTANCE_TO_TURN, frame.distanceToNextTurn.toInt())
        dict.addInt32(KEY_DISTANCE_REMAINING, frame.distanceRemaining.toInt())

        val street = frame.streetName?.take(20) ?: ""
        dict.addString(KEY_STREET_NAME, street)

        val simplifiedPoints = simplifyRoutePoints(frame.routePoints)
        val packedPoints = packRoutePoints(simplifiedPoints)
        val currentLocIndex = findNearestPointIndex(frame.currentLocation, simplifiedPoints)

        dict.addUint8(KEY_NUM_ROUTE_POINTS, simplifiedPoints.size.toByte())
        dict.addBytes(KEY_ROUTE_POINTS, packedPoints)
        dict.addUint8(KEY_CURRENT_LOC_INDEX, currentLocIndex.toByte())

        PebbleKit.sendDataToPebble(context, APP_UUID, dict)
        Log.d(TAG, "sendWatchFrame: sent dictionary to Pebble")
    }

    private fun simplifyRoutePoints(points: List<LatLng>): List<LatLng> {
        if (points.size <= MAX_ROUTE_POINTS) return points

        val result = mutableListOf<LatLng>()
        result.add(points.first())

        val step = (points.size - 1).toFloat() / (MAX_ROUTE_POINTS - 1)
        for (i in 1 until MAX_ROUTE_POINTS - 1) {
            val idx = (i * step).toInt()
            result.add(points[idx])
        }

        result.add(points.last())
        return result
    }

    private fun packRoutePoints(points: List<LatLng>): ByteArray {
        if (points.isEmpty()) return byteArrayOf()

        val minLat = points.minOf { it.lat }
        val maxLat = points.maxOf { it.lat }
        val minLng = points.minOf { it.lng }
        val maxLng = points.maxOf { it.lng }

        val latRange = maxLat - minLat
        val lngRange = maxLng - minLng

        val bytes = ByteArray(points.size * 2)
        for (i in points.indices) {
            val x = if (lngRange > 0) {
                ((points[i].lng - minLng) / lngRange * 255).toInt()
            } else {
                127
            }
            val y = if (latRange > 0) {
                ((maxLat - points[i].lat) / latRange * 255).toInt()
            } else {
                127
            }
            bytes[i * 2] = x.coerceIn(0, 255).toByte()
            bytes[i * 2 + 1] = y.coerceIn(0, 255).toByte()
        }
        return bytes
    }

    private fun findNearestPointIndex(location: LatLng, points: List<LatLng>): Int {
        if (points.isEmpty()) return 0

        var bestIndex = 0
        var bestDist = Double.MAX_VALUE

        for (i in points.indices) {
            val dLat = points[i].lat - location.lat
            val dLng = points[i].lng - location.lng
            val dist = dLat * dLat + dLng * dLng
            if (dist < bestDist) {
                bestDist = dist
                bestIndex = i
            }
        }
        return bestIndex
    }
}
