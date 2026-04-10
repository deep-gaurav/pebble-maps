package com.pebblemaps.android.data.pebble

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.getpebble.android.kit.PebbleKit
import com.getpebble.android.kit.util.PebbleDictionary
import com.pebblemaps.android.domain.model.LatLng
import com.pebblemaps.android.domain.model.WatchFrame
import com.pebblemaps.android.domain.model.WatchRenderConfig
import java.util.UUID
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

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
        const val KEY_DESTINATION_INDEX = 7
        const val KEY_BEARING = 8
        const val KEY_SCREEN_WIDTH = 10
        const val KEY_SCREEN_HEIGHT = 11

        const val MAX_ROUTE_POINTS = 20
        private const val MIN_SEND_INTERVAL_MS = 500L

        private const val INTENT_APP_RECEIVE_ACK = "com.getpebble.action.app.RECEIVE_ACK"
        private const val INTENT_APP_RECEIVE_NACK = "com.getpebble.action.app.RECEIVE_NACK"
        private const val INTENT_APP_RECEIVE_DATA = "com.getpebble.action.app.RECEIVE_DATA"
        private const val TRANSACTION_ID_KEY = "transaction_id"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var pendingFrame: WatchFrame? = null
    private var lastSendTime = 0L
    @Volatile
    private var ackReceived = true
    private var isRunning = false
    private var nextTransactionId = 0
    private var lastSentTransactionId = -1

    private var watchConfig = WatchRenderConfig(176, 176)

    private val ackReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val tx = intent.getIntExtra(TRANSACTION_ID_KEY, -1)
            if (tx != lastSentTransactionId) {
                Log.d(TAG, "ACK ignored for tx=$tx (expected $lastSentTransactionId)")
                return
            }
            Log.d(TAG, "ACK received tx=$tx")
            ackReceived = true
            scheduleSend()
        }
    }

    private val nackReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val tx = intent.getIntExtra(TRANSACTION_ID_KEY, -1)
            if (tx != lastSentTransactionId) {
                Log.d(TAG, "NACK ignored for tx=$tx (expected $lastSentTransactionId)")
                return
            }
            Log.w(TAG, "NACK received tx=$tx")
            ackReceived = true
            scheduleSend()
        }
    }

    private val dataReceiver = object : PebbleKit.PebbleDataReceiver(APP_UUID) {
        override fun receiveData(context: Context, transactionId: Int, dict: PebbleDictionary) {
            PebbleKit.sendAckToPebble(context, transactionId)
            val width = dict.getInteger(KEY_SCREEN_WIDTH)
            val height = dict.getInteger(KEY_SCREEN_HEIGHT)
            if (width != null && height != null) {
                watchConfig = WatchRenderConfig(width.toInt(), height.toInt())
                Log.d(TAG, "Watch reported size: ${watchConfig.width}x${watchConfig.height}")
            }
        }
    }

    init {
        ContextCompat.registerReceiver(
            context,
            ackReceiver,
            IntentFilter(INTENT_APP_RECEIVE_ACK),
            ContextCompat.RECEIVER_EXPORTED
        )
        ContextCompat.registerReceiver(
            context,
            nackReceiver,
            IntentFilter(INTENT_APP_RECEIVE_NACK),
            ContextCompat.RECEIVER_EXPORTED
        )
        ContextCompat.registerReceiver(
            context,
            dataReceiver,
            IntentFilter(INTENT_APP_RECEIVE_DATA),
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    fun getWatchConfig(): WatchRenderConfig = watchConfig

    fun isPebbleConnected(): Boolean {
        val connected = PebbleKit.isWatchConnected(context)
        Log.d(TAG, "isPebbleConnected: $connected")
        return connected
    }

    fun launchWatchApp() {
        Log.d(TAG, "launchWatchApp: starting app with UUID $APP_UUID")
        PebbleKit.startAppOnPebble(context, APP_UUID)
    }

    fun postWatchFrame(frame: WatchFrame) {
        pendingFrame = frame
        if (!isRunning) {
            isRunning = true
            scheduleSend()
        }
    }

    fun stopSending() {
        handler.removeCallbacksAndMessages(null)
        pendingFrame = null
        isRunning = false
        ackReceived = true
        Log.d(TAG, "stopSending: cleared queue")
    }

    private fun scheduleSend() {
        handler.post { trySend() }
    }

    private fun trySend() {
        val frame = pendingFrame
        if (frame == null) {
            isRunning = false
            return
        }

        if (!ackReceived) {
            handler.postDelayed({ trySend() }, 100)
            return
        }

        val elapsed = System.currentTimeMillis() - lastSendTime
        if (elapsed < MIN_SEND_INTERVAL_MS) {
            handler.postDelayed({ trySend() }, MIN_SEND_INTERVAL_MS - elapsed)
            return
        }

        pendingFrame = null
        lastSendTime = System.currentTimeMillis()
        ackReceived = false
        sendFrameInternal(frame)

        if (pendingFrame != null) {
            handler.postDelayed({ trySend() }, MIN_SEND_INTERVAL_MS)
        } else {
            isRunning = false
        }
    }

    private fun sendFrameInternal(frame: WatchFrame) {
        Log.d(TAG, "sendWatchFrame: turn=${frame.turnDirection}, dist=${frame.distanceToNextTurn}, remaining=${frame.distanceRemaining}, street=${frame.streetName}, points=${frame.routePoints.size}")

        val dict = PebbleDictionary()

        dict.addUint8(KEY_TURN_DIRECTION, frame.turnDirection.ordinal.toByte())
        dict.addInt32(KEY_DISTANCE_TO_TURN, frame.distanceToNextTurn.toInt())
        dict.addInt32(KEY_DISTANCE_REMAINING, frame.distanceRemaining.toInt())

        val street = frame.streetName?.take(20) ?: ""
        dict.addString(KEY_STREET_NAME, street)

        dict.addInt32(KEY_BEARING, (frame.bearing * 100).toInt())

        val viewportMeters = 150.0
        val deltaLat = viewportMeters / 111320.0
        val deltaLng = viewportMeters / (111320.0 * cos(Math.toRadians(frame.currentLocation.lat)))
        val minLat = frame.currentLocation.lat - deltaLat
        val maxLat = frame.currentLocation.lat + deltaLat
        val minLng = frame.currentLocation.lng - deltaLng
        val maxLng = frame.currentLocation.lng + deltaLng

        val viewportPoints = buildViewportPoints(frame, minLat, maxLat, minLng, maxLng)
        val packedPoints = packPointsToViewport(viewportPoints, minLat, maxLat, minLng, maxLng)

        val dest = frame.routePoints.lastOrNull()
        val destIndex = if (dest != null) {
            viewportPoints.indexOfFirst {
                latLngDistanceSq(it, dest) < 1e-16
            }
        } else -1
        val visibleDestIndex = if (destIndex >= 0) destIndex else 255

        dict.addUint8(KEY_NUM_ROUTE_POINTS, viewportPoints.size.toByte())
        dict.addBytes(KEY_ROUTE_POINTS, packedPoints)
        dict.addUint8(KEY_CURRENT_LOC_INDEX, 0)
        dict.addUint8(KEY_DESTINATION_INDEX, visibleDestIndex.toByte())

        val txId = nextTransactionId
        nextTransactionId = (nextTransactionId + 1) and 0xFF
        lastSentTransactionId = txId

        PebbleKit.sendDataToPebbleWithTransactionId(context, APP_UUID, dict, txId)
        Log.d(TAG, "sendWatchFrame: sent dictionary to Pebble tx=$txId points=${viewportPoints.size} destIdx=$visibleDestIndex")
    }

    private fun buildViewportPoints(
        frame: WatchFrame,
        minLat: Double, maxLat: Double, minLng: Double, maxLng: Double
    ): List<LatLng> {
        if (frame.routePoints.isEmpty()) return listOf(frame.currentLocation)

        // 1. Stable base sample across the whole route
        val baseSample = if (frame.routePoints.size <= MAX_ROUTE_POINTS) {
            frame.routePoints
        } else {
            val step = frame.routePoints.size.toFloat() / MAX_ROUTE_POINTS
            (0 until MAX_ROUTE_POINTS).map { i ->
                frame.routePoints[(i * step).toInt().coerceIn(0, frame.routePoints.lastIndex)]
            }
        }

        // 2. Keep only base points inside the viewport
        val visible = baseSample.filter {
            it.lat in minLat..maxLat && it.lng in minLng..maxLng
        }

        // 3. Drop points on top of current location to avoid duplicate dots
        val minDistSq = 1e-10
        val deduped = visible.filter { latLngDistanceSq(it, frame.currentLocation) > minDistSq }

        val result = mutableListOf(frame.currentLocation)
        result.addAll(deduped)

        val dest = frame.routePoints.lastOrNull()
        val destVisible = dest != null && dest.lat in minLat..maxLat && dest.lng in minLng..maxLng

        if (result.size <= MAX_ROUTE_POINTS) {
            if (destVisible) {
                val d = dest!!
                if (!result.any { latLngDistanceSq(it, d) < 1e-16 }) {
                    result.add(d)
                }
            }
            return result
        }

        // 4. Sample visible points down evenly, preserving destination if visible
        val keepCount = MAX_ROUTE_POINTS - 1
        val step = deduped.size.toFloat() / keepCount
        val sampled = (0 until keepCount).map { i ->
            deduped[(i * step).toInt().coerceIn(0, deduped.lastIndex)]
        }.toMutableList()

        if (destVisible) {
            val d = dest!!
            if (!sampled.any { latLngDistanceSq(it, d) < 1e-16 }) {
                sampled[keepCount - 1] = d
            }
        }

        return mutableListOf(frame.currentLocation).apply { addAll(sampled) }
    }

    private fun packPointsToViewport(
        points: List<LatLng>,
        minLat: Double, maxLat: Double, minLng: Double, maxLng: Double
    ): ByteArray {
        if (points.isEmpty()) return byteArrayOf()

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

    private fun latLngDistanceSq(a: LatLng, b: LatLng): Double {
        val dLat = a.lat - b.lat
        val dLng = a.lng - b.lng
        return dLat * dLat + dLng * dLng
    }
}
