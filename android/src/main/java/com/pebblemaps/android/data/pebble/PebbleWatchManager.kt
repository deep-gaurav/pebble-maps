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

        private const val INTENT_APP_RECEIVE_ACK = "com.getpebble.action.app.RECEIVE_ACK"
        private const val INTENT_APP_RECEIVE_NACK = "com.getpebble.action.app.RECEIVE_NACK"
        private const val INTENT_APP_RECEIVE_DATA = "com.getpebble.action.app.RECEIVE_DATA"
        private const val TRANSACTION_ID_KEY = "transaction_id"
    }

    private val handler = Handler(Looper.getMainLooper())

    private var pendingFrame: WatchFrame? = null
    private var currentFrame: WatchFrame? = null
    private var currentGeometry: PreparedWatchGeometry? = null
    private var sendPhase = 0 // 0=idle, 1=header sent, 2=roads sent
    private var nextTransactionId = 0
    private var lastSentTransactionId = -1
    private var isWaitingAck = false

    private var watchConfig = WatchRenderConfig(176, 176)

    private val ackReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val tx = intent.getIntExtra(TRANSACTION_ID_KEY, -1)
            if (tx != lastSentTransactionId) return
            isWaitingAck = false
            handler.post { onAckReceived() }
        }
    }

    private val nackReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val tx = intent.getIntExtra(TRANSACTION_ID_KEY, -1)
            if (tx != lastSentTransactionId) return
            isWaitingAck = false
            handler.post { onAckReceived() }
        }
    }

    private val dataReceiver = object : PebbleKit.PebbleDataReceiver(APP_UUID) {
        override fun receiveData(context: Context, transactionId: Int, dict: PebbleDictionary) {
            PebbleKit.sendAckToPebble(context, transactionId)
            val width = dict.getInteger(KEY_SCREEN_WIDTH)
            val height = dict.getInteger(KEY_SCREEN_HEIGHT)
            if (width != null && height != null) {
                watchConfig = WatchRenderConfig(width.toInt(), height.toInt())
            }
        }
    }

    init {
        ContextCompat.registerReceiver(context, ackReceiver,
            IntentFilter(INTENT_APP_RECEIVE_ACK), ContextCompat.RECEIVER_EXPORTED)
        ContextCompat.registerReceiver(context, nackReceiver,
            IntentFilter(INTENT_APP_RECEIVE_NACK), ContextCompat.RECEIVER_EXPORTED)
        ContextCompat.registerReceiver(context, dataReceiver,
            IntentFilter(INTENT_APP_RECEIVE_DATA), ContextCompat.RECEIVER_EXPORTED)
    }

    fun getWatchConfig(): WatchRenderConfig = watchConfig
    fun isPebbleConnected(): Boolean = PebbleKit.isWatchConnected(context)
    fun launchWatchApp() = PebbleKit.startAppOnPebble(context, APP_UUID)

    fun postFrame(frame: WatchFrame) {
        pendingFrame = frame
        if (sendPhase == 0 && !isWaitingAck) {
            startNextFrame()
        }
    }

    fun stopSending() {
        handler.removeCallbacksAndMessages(null)
        pendingFrame = null
        currentFrame = null
        currentGeometry = null
        sendPhase = 0
        isWaitingAck = false
    }

    private fun startNextFrame() {
        val frame = pendingFrame ?: return
        pendingFrame = null
        currentFrame = frame
        currentGeometry = WatchGeometryPreparer.prepare(frame)
        sendPhase = 1
        sendHeader(frame, currentGeometry ?: PreparedWatchGeometry(emptyList(), null, emptyList()))
    }

    private fun sendHeader(frame: WatchFrame, geometry: PreparedWatchGeometry) {
        val packedRoute = packPoints(geometry.routePoints, frame.viewportMeters / 2.0)

        val dict = PebbleDictionary().apply {
            addUint8(KEY_TURN_DIRECTION, frame.turnDirection.ordinal.toByte())
            addInt32(KEY_DISTANCE_TO_TURN, frame.distanceToNextTurn.toInt())
            addInt32(KEY_DISTANCE_REMAINING, frame.distanceRemaining.toInt())
            addString(KEY_STREET_NAME, frame.streetName?.take(20) ?: "")
            addUint8(KEY_NUM_ROUTE_POINTS, geometry.routePoints.size.toByte())
            addBytes(KEY_ROUTE_POINTS, packedRoute)
            addUint8(KEY_DESTINATION_INDEX, (geometry.destinationIndex ?: 255).toByte())
        }

        sendDict(dict)
        Log.d(TAG, "Header: ${geometry.routePoints.size} pts, destIdx=${geometry.destinationIndex ?: 255}")
    }

    private fun sendRoads(geometry: PreparedWatchGeometry, halfViewport: Double) {
        val roadBytes = mutableListOf<Byte>()
        for (segment in geometry.roadSegments) {
            if (segment.size < 2) continue
            for (point in segment) {
                val (x, y) = packSinglePoint(point, halfViewport)
                roadBytes.add(x.toByte())
                roadBytes.add(y.toByte())
            }
            roadBytes.add(0xFF.toByte())
            roadBytes.add(0xFF.toByte())
        }

        val dict = PebbleDictionary().apply {
            addUint8(KEY_HAS_ROADS, if (roadBytes.isEmpty()) 0.toByte() else 1.toByte())
            addBytes(KEY_ROAD_POINTS, roadBytes.toByteArray())
        }
        sendDict(dict)
        Log.d(TAG, "Roads: ${roadBytes.size / 2} entries")
    }

    private fun onAckReceived() {
        when (sendPhase) {
            1 -> {
                val frame = currentFrame
                val geometry = currentGeometry
                if (frame != null && geometry != null) {
                    sendPhase = 2
                    sendRoads(geometry, frame.viewportMeters / 2.0)
                } else {
                    finishFrame()
                }
            }
            2 -> {
                finishFrame()
            }
        }
    }

    private fun finishFrame() {
        sendPhase = 0
        currentFrame = null
        currentGeometry = null
        startNextFrame()
    }

    private fun sendDict(dict: PebbleDictionary) {
        val txId = nextTransactionId
        nextTransactionId = (nextTransactionId + 1) and 0xFF
        lastSentTransactionId = txId
        isWaitingAck = true
        PebbleKit.sendDataToPebbleWithTransactionId(context, APP_UUID, dict, txId)
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
        val sx = point.xMeters
        val sy = point.yMeters
        val nx = (sx / halfViewport * 0.5 + 0.5).coerceIn(0.0, 1.0)
        val ny = (-sy / halfViewport * 0.5 + 0.5).coerceIn(0.0, 1.0)
        return Pair(
            (nx * 255).toInt().coerceIn(0, 254),
            (ny * 255).toInt().coerceIn(0, 254)
        )
    }
}
