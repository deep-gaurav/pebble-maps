package com.pebblemaps.android.ui.preview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pebblemaps.android.data.pebble.PebbleWatchManager
import com.pebblemaps.android.domain.model.LatLng
import com.pebblemaps.android.domain.model.TurnDirection
import com.pebblemaps.android.domain.model.WatchFrame
import com.pebblemaps.android.domain.model.WatchRenderConfig
import org.koin.androidx.compose.koinViewModel
import org.koin.androidx.compose.get
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun WatchPreviewScreen(
    onNavigateBack: () -> Unit,
    viewModel: WatchPreviewViewModel = koinViewModel()
) {
    val config by viewModel.config.collectAsState()
    val frame by viewModel.frame.collectAsState()

    var widthInput by remember { mutableStateOf(config.width.toString()) }
    var heightInput by remember { mutableStateOf(config.height.toString()) }

    val defaultFrame = remember {
        WatchFrame(
            routePoints = listOf(
                LatLng(40.7128, -74.0060),
                LatLng(40.7138, -74.0050),
                LatLng(40.7148, -74.0040),
                LatLng(40.7158, -74.0030),
                LatLng(40.7168, -74.0020)
            ),
            currentLocation = LatLng(40.7128, -74.0060),
            turnDirection = TurnDirection.RIGHT,
            distanceToNextTurn = 150.0,
            distanceRemaining = 1250.0,
            streetName = "Main Street"
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 48.dp)
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                text = "Watch Preview",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge
            )
            val pebbleManager: PebbleWatchManager = get()
            TextButton(onClick = {
                pebbleManager.launchWatchApp()
                pebbleManager.sendWatchFrame(frame ?: defaultFrame)
            }) {
                Text("Launch on Pebble")
            }
            TextButton(onClick = {
                val w = widthInput.toIntOrNull() ?: 176
                val h = heightInput.toIntOrNull() ?: 176
                viewModel.updateConfig(w, h)
            }) {
                Text("Apply")
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = widthInput,
                onValueChange = { widthInput = it },
                label = { Text("Width") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            OutlinedTextField(
                value = heightInput,
                onValueChange = { heightInput = it },
                label = { Text("Height") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center
        ) {
            WatchPreviewCanvas(
                config = config,
                frame = frame ?: defaultFrame,
                modifier = Modifier
                    .aspectRatio(1f)
                    .size(280.dp)
                    .border(2.dp, MaterialTheme.colorScheme.primary)
            )
        }

        Text(
            text = "Size: ${config.width}x${config.height}",
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
fun WatchPreviewCanvas(
    config: WatchRenderConfig,
    frame: WatchFrame,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    val backgroundColor = Color.Black
    val routeColor = Color.Yellow
    val currentLocationColor = Color.Green
    val textColor = Color.White

    Canvas(
        modifier = modifier.background(backgroundColor)
    ) {
        val width = size.width
        val height = size.height

        if (frame.routePoints.size >= 2) {
            val minLat = frame.routePoints.minOf { it.lat }
            val maxLat = frame.routePoints.maxOf { it.lat }
            val minLng = frame.routePoints.minOf { it.lng }
            val maxLng = frame.routePoints.maxOf { it.lng }

            val padding = 20f
            val usableWidth = width - 2 * padding
            val usableHeight = height - 2 * padding

            val latRange = maxLat - minLat
            val lngRange = maxLng - minLng

            fun LatLng.toCanvasPoint(): Offset {
                val x = if (lngRange > 0) {
                    padding + ((lng - minLng) / lngRange * usableWidth).toFloat()
                } else {
                    width / 2
                }
                val y = if (latRange > 0) {
                    padding + ((maxLat - lat) / latRange * usableHeight).toFloat()
                } else {
                    height / 2
                }
                return Offset(x, y)
            }

            val path = Path()
            val points = frame.routePoints.map { it.toCanvasPoint() }
            path.moveTo(points.first().x, points.first().y)
            for (i in 1 until points.size) {
                path.lineTo(points[i].x, points[i].y)
            }
            drawPath(
                path = path,
                color = routeColor,
                style = Stroke(width = 3f)
            )

            frame.currentLocation.toCanvasPoint().let {
                drawCircle(
                    color = currentLocationColor,
                    radius = 8f,
                    center = it
                )
            }

            val lastPoint = points.last()
            drawCircle(
                color = Color.Red,
                radius = 6f,
                center = lastPoint
            )
        }

        val turnText = when (frame.turnDirection) {
            TurnDirection.RIGHT -> "→"
            TurnDirection.LEFT -> "←"
            TurnDirection.STRAIGHT -> "↑"
            TurnDirection.SLIGHT_LEFT -> "↖"
            TurnDirection.SLIGHT_RIGHT -> "↗"
            TurnDirection.SHARP_LEFT -> "⇐"
            TurnDirection.SHARP_RIGHT -> "⇒"
            TurnDirection.UTURN -> "↺"
            TurnDirection.NONE -> ""
        }

        val turnTextStyle = TextStyle(
            color = textColor,
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold
        )

        drawText(
            textMeasurer = textMeasurer,
            text = turnText,
            topLeft = Offset(width / 2 - 20f, height / 2 - 60f),
            style = turnTextStyle
        )

        val distanceText = formatDistance(frame.distanceToNextTurn)
        val distanceStyle = TextStyle(
            color = textColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.Normal
        )

        drawText(
            textMeasurer = textMeasurer,
            text = distanceText,
            topLeft = Offset(width / 2 - 30f, height / 2 - 20f),
            style = distanceStyle
        )

        val remainingText = formatDistance(frame.distanceRemaining)
        val remainingStyle = TextStyle(
            color = textColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Normal
        )

        drawText(
            textMeasurer = textMeasurer,
            text = remainingText,
            topLeft = Offset(width / 2 - 25f, height - 30f),
            style = remainingStyle
        )

        frame.streetName?.let { street ->
            val streetStyle = TextStyle(
                color = textColor,
                fontSize = 10.sp,
                fontWeight = FontWeight.Normal
            )

            drawText(
                textMeasurer = textMeasurer,
                text = street,
                topLeft = Offset(width / 2 - 40f, height / 2 + 10f),
                style = streetStyle
            )
        }
    }
}

private fun formatDistance(meters: Double): String {
    return when {
        meters < 1000 -> "${meters.toInt()}m"
        else -> String.format("%.1fkm", meters / 1000)
    }
}