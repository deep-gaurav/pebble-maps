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
import com.pebblemaps.android.domain.model.WatchFrame
import com.pebblemaps.android.domain.model.WatchRenderConfig
import com.pebblemaps.android.util.PreparedRoadSegment
import com.pebblemaps.android.util.PreparedWatchGeometry
import com.pebblemaps.android.util.ViewportPoint
import com.pebblemaps.android.util.WatchGeometryPreparer
import java.util.UUID

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
        const val KEY_DESTINATION_INDEX = 7
        const val KEY_SCREEN_WIDTH = 10
        const val KEY_SCREEN_HEIGHT = 11
        const val KEY_ROAD_POINTS = 14
        const val KEY_HAS_ROADS = 21
        const val KEY_ZOOM = 22

        private const val INTENT_APP_RECEIVE_ACK = "com.getpebble.action.app.RECEIVE_ACK"
        private const val INTENT_APP_RECEIVE_NACK = "com.getpebble.action.app.RECEIVE_NACK"
        private const val INTENT_APP_RECEIVE_DATA = "com.getpebble.action.app.RECEIVE_DATA"
        private const val TRANSACTION_ID_KEY = "transaction_id"
    }

    private val handler = Handler(Looper.getMainLooper())

    private var pendingFrame: WatchFrame? = null
    private var isWaitingAck = false
    private var nextTransactionId = 0
    private var lastSentTransactionId = -1

    private var watchConfig = WatchRenderConfig(176, 176)
    private var zoomLevel: Int = 0
    private val baseViewportMeters: Double = 150.0

    fun getEffectiveViewportMeters(): Double {
        return baseViewportMeters * (1 shl -zoomLevel)
    }

    fun getZoomLevel(): Int = zoomLevel

    private val ackReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val tx = intent.getIntExtra(TRANSACTION_ID_KEY, -1)
            if (tx != lastSentTransactionId) return
            isWaitingAck = false
            handler.post { startNextFrameIfPossible() }
        }
    }

    private val nackReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val tx = intent.getIntExtra(TRANSACTION_ID_KEY, -1)
            if (tx != lastSentTransactionId) return
            isWaitingAck = false
            handler.post { startNextFrameIfPossible() }
        }
    }

    private var isWatchReady = false
    
    private val dataReceiver = object : PebbleKit.PebbleDataReceiver(APP_UUID) {
        override fun receiveData(context: Context, transactionId: Int, dict: PebbleDictionary) {
            PebbleKit.sendAckToPebble(context, transactionId)
            val width = dict.getInteger(KEY_SCREEN_WIDTH)
            val height = dict.getInteger(KEY_SCREEN_HEIGHT)
            if (width != null && height != null) {
                watchConfig = WatchRenderConfig(width.toInt(), height.toInt())
                isWatchReady = true
                Log.d(TAG, "Watch ready: ${width}x${height}")
            }
            val zoomDelta = dict.getInteger(KEY_ZOOM)
            if (zoomDelta != null) {
                zoomLevel = (zoomLevel + zoomDelta.toInt()).coerceIn(-2, 2)
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
    fun isPebbleConnected(): Boolean = PebbleKit.isWatchConnected(context)
    fun launchWatchApp() = PebbleKit.startAppOnPebble(context, APP_UUID)

    fun postFrame(frame: WatchFrame) {
        pendingFrame = frame
        if (!isWaitingAck) {
            startNextFrameIfPossible()
        }
    }

    fun stopSending() {
        handler.removeCallbacksAndMessages(null)
        pendingFrame = null
        isWaitingAck = false
        isWatchReady = false
    }

    private fun startNextFrameIfPossible() {
        if (isWaitingAck) return
        if (!isWatchReady) return
        val frame = pendingFrame ?: return
        pendingFrame = null
        sendFrame(frame, WatchGeometryPreparer.prepare(frame))
    }

    private fun sendFrame(frame: WatchFrame, geometry: PreparedWatchGeometry) {
        val routeBytes = packPoints(geometry.routePoints, frame.viewportMeters / 2.0)
        val roadBytes = packRoadSegments(geometry.roadSegments, frame.viewportMeters / 2.0)

        val dict = PebbleDictionary().apply {
            addUint8(KEY_TURN_DIRECTION, frame.turnDirection.ordinal.toByte())
            addInt32(KEY_DISTANCE_TO_TURN, frame.distanceToNextTurn.toInt())
            addInt32(KEY_DISTANCE_REMAINING, frame.distanceRemaining.toInt())
            addString(KEY_STREET_NAME, frame.streetName?.take(20) ?: "")
            addUint8(KEY_NUM_ROUTE_POINTS, geometry.routePoints.size.toByte())
            addBytes(KEY_ROUTE_POINTS, routeBytes)
            addUint8(KEY_DESTINATION_INDEX, (geometry.destinationIndex ?: 255).toByte())
            addUint8(KEY_HAS_ROADS, if (roadBytes.isEmpty()) 0.toByte() else 1.toByte())
            addBytes(KEY_ROAD_POINTS, roadBytes)
        }

        sendDict(dict)
        Log.d(
            TAG,
            "Frame: routePts=${geometry.routePoints.size} roadBytes=${geometry.estimatedRoadBytes} packedRoadBytes=${roadBytes.size}"
        )
    }

    private fun sendDict(dict: PebbleDictionary) {
        val txId = nextTransactionId
        nextTransactionId = (nextTransactionId + 1) and 0xFF
        lastSentTransactionId = txId
        isWaitingAck = true
        PebbleKit.sendDataToPebbleWithTransactionId(context, APP_UUID, dict, txId)
    }

    private fun packRoadSegments(segments: List<PreparedRoadSegment>, halfViewport: Double): ByteArray {
        val out = ArrayList<Byte>(segments.sumOf { 1 + (it.points.size * 2) + 2 })
        for (segment in segments) {
            if (segment.points.size < 2) continue
            out.add(segment.roadClass.wireValue.toByte())
            for (point in segment.points) {
                val (x, y) = packSinglePoint(point, halfViewport)
                out.add(x.toByte())
                out.add(y.toByte())
            }
            out.add(0xFF.toByte())
            out.add(0xFF.toByte())
        }
        return out.toByteArray()
    }

    private fun packPoints(points: List<ViewportPoint>, halfViewport: Double): ByteArray {
        val bytes = ByteArray(points.size * 2)
        for (i in points.indices) {
            val (x, y) = packSinglePoint(points[i], halfViewport)
            bytes[i * 2] = x.toByte()
            bytes[i * 2 + 1] = y.toByte()
        }
        return bytes
    }

    private fun packSinglePoint(point: ViewportPoint, halfViewport: Double): Pair<Int, Int> {
        val nx = (point.xMeters / halfViewport * 0.5 + 0.5).coerceIn(0.0, 1.0)
        val ny = (-point.yMeters / halfViewport * 0.5 + 0.5).coerceIn(0.0, 1.0)
        return Pair(
            (nx * 255).toInt().coerceIn(0, 254),
            (ny * 255).toInt().coerceIn(0, 254)
        )
    }
}
