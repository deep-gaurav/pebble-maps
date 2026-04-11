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
import com.pebblemaps.android.domain.model.RoadClass
import com.pebblemaps.android.domain.model.TurnDirection
import com.pebblemaps.android.domain.model.WatchFrame
import com.pebblemaps.android.util.PreparedRoadSegment
import com.pebblemaps.android.util.PreparedWatchGeometry
import com.pebblemaps.android.util.ViewportPoint
import com.pebblemaps.android.util.WatchGeometryPreparer
import org.koin.androidx.compose.koinViewModel
import org.koin.androidx.compose.get

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
                pebbleManager.postFrame(frame ?: defaultFrame)
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
    frame: WatchFrame,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    val prepared = remember(frame) { WatchGeometryPreparer.prepare(frame) }
    val routeColor = Color.Yellow
    val roadColor = Color(0xFFBDBDBD)
    val textColor = Color.White

    Canvas(
        modifier = modifier.background(Color.Black)
    ) {
        val width = size.width
        val height = size.height
        val centerX = width / 2f
        val centerY = height / 2f

        val halfViewport = frame.viewportMeters / 2.0
        val padding = 10f
        val usableWidth = width - 2 * padding
        val usableHeight = height - 2 * padding

        fun ViewportPoint.toScreenOffset(): Offset {
            val nx = (xMeters / halfViewport * 0.5 + 0.5).coerceIn(0.0, 1.0)
            val ny = (-yMeters / halfViewport * 0.5 + 0.5).coerceIn(0.0, 1.0)
            return Offset(
                padding + (nx * usableWidth).toFloat(),
                padding + (ny * usableHeight).toFloat()
            )
        }

        drawRoadSegments(prepared, roadColor) { point -> point.toScreenOffset() }

        if (prepared.routePoints.size >= 2) {
            val screenPoints = prepared.routePoints.map { it.toScreenOffset() }

            if (screenPoints.size >= 2) {
                val path = Path()
                path.moveTo(screenPoints.first().x, screenPoints.first().y)
                for (i in 1 until screenPoints.size) {
                    path.lineTo(screenPoints[i].x, screenPoints[i].y)
                }
                drawPath(
                    path = path,
                    color = routeColor,
                    style = Stroke(width = 3f)
                )
            }

            prepared.destinationIndex?.let { destIdx ->
                if (destIdx < screenPoints.size) {
                    drawCircle(color = Color.Red, radius = 6f, center = screenPoints[destIdx])
                }
            }
        }

        drawCircle(color = Color.Green, radius = 8f, center = Offset(centerX, centerY))

        val turnText = when (frame.turnDirection) {
            TurnDirection.RIGHT -> "\u2192"
            TurnDirection.LEFT -> "\u2190"
            TurnDirection.STRAIGHT -> "\u2191"
            TurnDirection.SLIGHT_LEFT -> "\u2196"
            TurnDirection.SLIGHT_RIGHT -> "\u2197"
            TurnDirection.SHARP_LEFT -> "\u21d0"
            TurnDirection.SHARP_RIGHT -> "\u21d2"
            TurnDirection.UTURN -> "\u21ba"
            TurnDirection.NONE -> ""
        }

        if (turnText.isNotEmpty()) {
            drawText(
                textMeasurer = textMeasurer,
                text = turnText,
                topLeft = Offset(centerX - 20f, 8f),
                style = TextStyle(color = textColor, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            )
        }

        drawText(
            textMeasurer = textMeasurer,
            text = formatDistance(frame.distanceToNextTurn),
            topLeft = Offset(centerX - 30f, centerY - 20f),
            style = TextStyle(color = textColor, fontSize = 14.sp, fontWeight = FontWeight.Normal)
        )

        drawText(
            textMeasurer = textMeasurer,
            text = formatDistance(frame.distanceRemaining),
            topLeft = Offset(centerX - 25f, height - 30f),
            style = TextStyle(color = textColor, fontSize = 11.sp, fontWeight = FontWeight.Normal)
        )

        frame.streetName?.let { street ->
            drawText(
                textMeasurer = textMeasurer,
                text = street,
                topLeft = Offset(centerX - 40f, centerY + 10f),
                style = TextStyle(color = textColor, fontSize = 10.sp, fontWeight = FontWeight.Normal)
            )
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRoadSegments(
    prepared: PreparedWatchGeometry,
    roadColor: Color,
    toScreenOffset: (ViewportPoint) -> Offset
) {
    for (segment in prepared.roadSegments) {
        if (segment.points.size < 2) continue
        val path = Path()
        val first = toScreenOffset(segment.points.first())
        path.moveTo(first.x, first.y)
        for (i in 1 until segment.points.size) {
            val point = toScreenOffset(segment.points[i])
            path.lineTo(point.x, point.y)
        }
        drawPath(
            path = path,
            color = roadColor,
            style = Stroke(width = segment.roadClass.previewWidthPx)
        )
    }
}

private fun formatDistance(meters: Double): String {
    return when {
        meters < 1000 -> "${meters.toInt()}m"
        else -> String.format("%.1fkm", meters / 1000)
    }
}
