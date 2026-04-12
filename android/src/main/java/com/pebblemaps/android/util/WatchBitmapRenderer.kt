package com.pebblemaps.android.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import com.pebblemaps.android.domain.model.WatchFrame
import com.pebblemaps.android.domain.model.TurnDirection
import org.osmdroid.views.MapView
import kotlin.math.abs
import kotlin.math.min

class WatchBitmapRenderer {

    private val turnArrows = mapOf(
        TurnDirection.STRAIGHT to "\u2191",
        TurnDirection.SLIGHT_LEFT to "\u2196",
        TurnDirection.LEFT to "\u2190",
        TurnDirection.SHARP_LEFT to "\u21d0",
        TurnDirection.SLIGHT_RIGHT to "\u2197",
        TurnDirection.RIGHT to "\u2192",
        TurnDirection.SHARP_RIGHT to "\u21d2",
        TurnDirection.UTURN to "\u21ba",
        TurnDirection.NONE to ""
    )

    fun renderMapRle(mapView: MapView, width: Int, height: Int): ByteArray {
        val mapBitmap = captureMapView(mapView, width, height)
        val edges = edgeDetect(mapBitmap, width, height)
        mapBitmap.recycle()
        return rleEncode(edges, width, height)
    }

    fun render(mapView: MapView, width: Int, height: Int, frame: WatchFrame): ByteArray {
        val mapBitmap = captureMapView(mapView, width, height)
        val canvas = Canvas(mapBitmap)
        drawOverlays(canvas, width, height, frame)
        val mono = thresholdToMono(mapBitmap, width, height)
        mapBitmap.recycle()
        return rleEncode(mono, width, height)
    }

    fun renderPreview(mapView: MapView, width: Int, height: Int, frame: WatchFrame): Bitmap {
        val mapBitmap = captureMapView(mapView, width, height)
        val canvas = Canvas(mapBitmap)
        drawOverlays(canvas, width, height, frame)
        val mono = thresholdToMono(mapBitmap, width, height)
        mapBitmap.recycle()
        return monoToBitmap(mono, width, height)
    }

    private fun edgeDetect(bitmap: Bitmap, width: Int, height: Int): IntArray {
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val brightness = IntArray(width * height)
        for (i in pixels.indices) {
            val p = pixels[i]
            brightness[i] = (AndroidColor.red(p) + AndroidColor.green(p) + AndroidColor.blue(p)) / 3
        }

        val edges = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * width + x
                val c = brightness[idx]
                var maxGrad = 0
                if (x > 0) maxGrad = maxOf(maxGrad, abs(c - brightness[idx - 1]))
                if (x < width - 1) maxGrad = maxOf(maxGrad, abs(c - brightness[idx + 1]))
                if (y > 0) maxGrad = maxOf(maxGrad, abs(c - brightness[idx - width]))
                if (y < height - 1) maxGrad = maxOf(maxGrad, abs(c - brightness[idx + width]))
                edges[idx] = if (maxGrad > 20 || c < 80) 1 else 0
            }
        }

        val result = edges.copyOf()
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (edges[y * width + x] == 1) {
                    for (dy in -1..1) {
                        for (dx in -1..1) {
                            val ny = y + dy
                            val nx = x + dx
                            if (ny in 0 until height && nx in 0 until width) {
                                result[ny * width + nx] = 1
                            }
                        }
                    }
                }
            }
        }

        return result
    }

    private fun monoToBitmap(mono: IntArray, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        for (i in mono.indices) {
            pixels[i] = if (mono[i] == 1) AndroidColor.WHITE else AndroidColor.BLACK
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    private fun captureMapView(mapView: MapView, targetWidth: Int, targetHeight: Int): Bitmap {
        val vw = mapView.width
        val vh = mapView.height
        if (vw <= 0 || vh <= 0) {
            return Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        }

        val full = Bitmap.createBitmap(vw, vh, Bitmap.Config.ARGB_8888)
        val c = Canvas(full)
        mapView.draw(c)

        val cropSize = min(vw, vh)
        val left = (vw - cropSize) / 2
        val top = (vh - cropSize) / 2
        val cropped = Bitmap.createBitmap(full, left, top, cropSize, cropSize)
        val scaled = Bitmap.createScaledBitmap(cropped, targetWidth, targetHeight, true)

        full.recycle()
        cropped.recycle()
        return scaled
    }

    private fun drawOverlays(canvas: Canvas, width: Int, height: Int, frame: WatchFrame) {
        val turnText = turnArrows[frame.turnDirection] ?: ""

        val shadowPaint = Paint().apply {
            isAntiAlias = true
            color = AndroidColor.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }

        val turnPaint = Paint().apply {
            isAntiAlias = true
            color = AndroidColor.YELLOW
            textSize = height * 0.22f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }

        val distPaint = Paint().apply {
            isAntiAlias = true
            color = AndroidColor.WHITE
            textSize = height * 0.12f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }

        val smallPaint = Paint().apply {
            isAntiAlias = true
            color = AndroidColor.WHITE
            textSize = height * 0.08f
            textAlign = Paint.Align.CENTER
        }

        val cx = width / 2f

        if (turnText.isNotEmpty()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                shadowPaint.textSize = turnPaint.textSize
                shadowPaint.textAlign = Paint.Align.CENTER
                shadowPaint.style = Paint.Style.STROKE
                canvas.drawText(turnText, cx, height * 0.22f, shadowPaint)
            }
            canvas.drawText(turnText, cx, height * 0.22f, turnPaint)
        }

        val distText = formatDistance(frame.distanceToNextTurn)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            shadowPaint.textSize = distPaint.textSize
            canvas.drawText(distText, cx, height * 0.38f, shadowPaint)
        }
        canvas.drawText(distText, cx, height * 0.38f, distPaint)

        frame.streetName?.takeIf { it.isNotBlank() }?.let { street ->
            val truncated = if (street.length > 20) street.take(20) + "\u2026" else street
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                shadowPaint.textSize = smallPaint.textSize
                canvas.drawText(truncated, cx, height * 0.82f, shadowPaint)
            }
            canvas.drawText(truncated, cx, height * 0.82f, smallPaint)
        }

        val remainingText = formatDistance(frame.distanceRemaining)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            shadowPaint.textSize = smallPaint.textSize
            canvas.drawText(remainingText, cx, height * 0.92f, shadowPaint)
        }
        canvas.drawText(remainingText, cx, height * 0.92f, smallPaint)
    }

    private fun thresholdToMono(bitmap: Bitmap, width: Int, height: Int): IntArray {
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        for (i in pixels.indices) {
            pixels[i] = if (isBright(pixels[i])) 1 else 0
        }
        return pixels
    }

    private fun isBright(color: Int): Boolean {
        val r = AndroidColor.red(color)
        val g = AndroidColor.green(color)
        val b = AndroidColor.blue(color)
        return (r + g + b) / 3 >= 128
    }

    private fun rleEncode(pixels: IntArray, width: Int, height: Int): ByteArray {
        val out = mutableListOf<Byte>()
        for (y in 0 until height) {
            var x = 0
            while (x < width) {
                val color = pixels[y * width + x]
                var count = 1
                while (x + count < width && pixels[y * width + x + count] == color && count < 255) {
                    count++
                }
                out.add(count.toByte())
                out.add(color.toByte())
                x += count
            }
            out.add(0)
        }
        return out.toByteArray()
    }

    private fun formatDistance(meters: Double): String {
        return when {
            meters < 1000 -> "${meters.toInt()}m"
            else -> {
                val km = meters / 1000.0
                String.format("%.1fkm", km)
            }
        }
    }
}
