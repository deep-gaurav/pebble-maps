package com.pebblemaps.android.ui.navigation

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.Paint
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import android.util.Log
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import com.pebblemaps.android.data.RoadCache
import com.pebblemaps.android.data.pebble.PebbleWatchManager
import com.pebblemaps.android.data.remote.ProtomapsTileApi
import com.pebblemaps.android.domain.model.LatLng
import com.pebblemaps.android.domain.model.MockLocationManager
import com.pebblemaps.android.domain.model.MockLocationState
import com.pebblemaps.android.domain.model.Route
import com.pebblemaps.android.domain.model.Step
import com.pebblemaps.android.domain.model.TurnDirection
import com.pebblemaps.android.domain.model.WatchFrame
import com.pebblemaps.android.domain.model.toArrow
import com.pebblemaps.android.domain.model.toDescription
import com.pebblemaps.android.domain.model.toTurnDirection
import com.pebblemaps.android.util.PreparedWatchGeometry
import com.pebblemaps.android.util.WatchGeometryPreparer
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.androidx.compose.get
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import kotlin.math.roundToInt

@Composable
fun ActiveNavigationScreen(
    onNavigateBack: () -> Unit,
    viewModel: NavigationViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsState()
    val mockState by MockLocationManager.stateFlow.collectAsState()
    val pebbleManager: PebbleWatchManager = get()
    val roadCache: RoadCache = get()
    val tileApi: ProtomapsTileApi = get()
    
    var showDebug by remember { mutableStateOf(false) }
    var speedSlider by remember { mutableFloatStateOf(MockLocationManager.speedKmh.toFloat()) }
    var mapView by remember { mutableStateOf<MapView?>(null) }
    var currentPreparedGeometry by remember { mutableStateOf<PreparedWatchGeometry?>(null) }
    var currentViewportMeters by remember { mutableFloatStateOf(150f) }
    
    val context = LocalContext.current
    
    DisposableEffect(Unit) {
        val activity = context as Activity
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Log.d("NavScreen", "Screen kept on")
        
        onDispose {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            Log.d("NavScreen", "Screen keep released")
        }
    }
    
    LaunchedEffect(state.route) {
        state.route?.let { route ->
            val coordinates = route.geometry.coordinates

            roadCache.prefetchEntireRoute(coordinates, 150.0)

            MockLocationManager.setRoute(route)
            MockLocationManager.start()
            if (pebbleManager.isPebbleConnected()) {
                pebbleManager.launchWatchApp()
            }
        }
    }

    LaunchedEffect(mockState) {
        mockState?.let { mock ->
            val route = state.route
            val turnDirection = getCurrentTurnDirection(route, mock.currentStepIndex)
            val streetName = route?.legs
                ?.flatMap { it.steps }
                ?.getOrNull(mock.currentStepIndex)
                ?.maneuver
                ?.type
            val viewportMeters = pebbleManager.getEffectiveViewportMeters()
            roadCache.refreshIfNeeded(mock.currentPosition, mock.smoothedBearing, viewportMeters)
            val nearbyRoads = roadCache.nearbyRoads.value
            val nearbyFeatures = roadCache.nearbyFeatures.value
            val timeRemainingSeconds = if (mock.currentSpeedKmh > 0.5) {
                mock.totalRemainingDistance / (mock.currentSpeedKmh / 3.6)
            } else 0.0
            if (nearbyRoads.isEmpty()) {
                Log.w("NavScreen", "No roads available at ${mock.currentPosition}, viewport=${viewportMeters.toInt()}m")
            }
            val frame = WatchFrame(
                routePoints = route?.geometry?.coordinates ?: emptyList(),
                currentLocation = mock.currentPosition,
                turnDirection = turnDirection,
                distanceToNextTurn = mock.distanceToNextTurn,
                distanceRemaining = mock.totalRemainingDistance,
                streetName = streetName,
                bearing = mock.smoothedBearing,
                viewportMeters = viewportMeters,
                nearbyRoads = nearbyRoads,
                nearbyFeatures = nearbyFeatures,
                timeRemainingSeconds = timeRemainingSeconds
            )
            val geometry = WatchGeometryPreparer.prepare(frame)
            currentPreparedGeometry = geometry
            currentViewportMeters = viewportMeters.toFloat()
            pebbleManager.postFrame(frame)
        }
    }

    LaunchedEffect(Unit) {
        roadCache.nearbyRoads.collect { nearbyRoads ->
            val mock = MockLocationManager.stateFlow.value
            val route = viewModel.state.value.route
            mock?.let { m ->
                val turnDirection = getCurrentTurnDirection(route, m.currentStepIndex)
                val streetName = route?.legs
                    ?.flatMap { it.steps }
                    ?.getOrNull(m.currentStepIndex)
                    ?.maneuver
                    ?.type
                val viewportMeters = pebbleManager.getEffectiveViewportMeters()
                val nearbyFeatures = roadCache.nearbyFeatures.value
                val timeRemainingSeconds = if (m.currentSpeedKmh > 0.5) {
                    m.totalRemainingDistance / (m.currentSpeedKmh / 3.6)
                } else 0.0
                val frame = WatchFrame(
                    routePoints = route?.geometry?.coordinates ?: emptyList(),
                    currentLocation = m.currentPosition,
                    turnDirection = turnDirection,
                    distanceToNextTurn = m.distanceToNextTurn,
                    distanceRemaining = m.totalRemainingDistance,
                    streetName = streetName,
                    bearing = m.smoothedBearing,
                    viewportMeters = viewportMeters,
                    nearbyRoads = nearbyRoads,
                    nearbyFeatures = nearbyFeatures,
                    timeRemainingSeconds = timeRemainingSeconds
                )
                val geometry = WatchGeometryPreparer.prepare(frame)
                currentPreparedGeometry = geometry
                currentViewportMeters = viewportMeters.toFloat()
                pebbleManager.postFrame(frame)
            }
        }
    }
    
    DisposableEffect(Unit) {
        onDispose {
            MockLocationManager.stop()
            pebbleManager.stopSending()
        }
    }
    
    val mock = mockState
    val route = state.route
    var hasInitiallyCentered by remember { mutableStateOf(false) }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 48.dp)
    ) {
        // Map View
        AndroidView(
            factory = { ctx ->
                MapView(ctx).apply {
                    setTileSource(TileSourceFactory.MAPNIK)
                    setMultiTouchControls(true)
                    mapView = this
                }
            },
            update = { view ->
                view.overlays.clear()
                
                // Draw route polyline
                route?.let { r ->
                    val polyline = Polyline()
                    polyline.setPoints(r.geometry.coordinates.map {
                        GeoPoint(it.lat, it.lng)
                    })
                    view.overlays.add(polyline)
                }
                
                mock?.let { m ->
                    val geoPoint = GeoPoint(m.currentPosition.lat, m.currentPosition.lng)
                    
                    // Create directional arrow marker (pointing UP, no rotation in bitmap)
                    val arrowBitmap = createDirectionArrow(0f)
                    val marker = Marker(view)
                    marker.position = geoPoint
                    marker.icon = BitmapDrawable(view.context.resources, arrowBitmap)
                    marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    view.overlays.add(marker)
                    
                    // Center map on mock location and set rotation
                    view.controller.setCenter(geoPoint)
                    view.mapOrientation = -m.smoothedBearing
                    
                    // Only set initial zoom once
                    if (!hasInitiallyCentered) {
                        view.controller.setZoom(17.0)
                        hasInitiallyCentered = true
                    }
                }
                
                view.invalidate()
            },
            modifier = Modifier.fillMaxSize()
        )
        
        // Top Turn Indicator
        TurnIndicatorPanel(
            turnDirection = getCurrentTurnDirection(state.route, mock?.currentStepIndex ?: 0),
            distanceToTurn = mock?.distanceToNextTurn ?: 0.0,
            modifier = Modifier.align(Alignment.TopCenter)
        )
        
        // Back button
        IconButton(
            onClick = {
                MockLocationManager.stop()
                onNavigateBack()
            },
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
                .background(ComposeColor.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = ComposeColor.White
            )
        }
        
        // Loading overlay while pre-fetching route
        val isPrefetching by roadCache.isPrefetching.collectAsState()
        val prefetchProgress by roadCache.prefetchProgress.collectAsState()
        
        if (isPrefetching) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(ComposeColor.Black.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(
                        color = ComposeColor.White,
                        modifier = Modifier.size(48.dp)
                    )
                    Text(
                        text = "Loading map tiles... ${(prefetchProgress * 100).toInt()}%",
                        color = ComposeColor.White,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                    LinearProgressIndicator(
                        progress = { prefetchProgress },
                        color = ComposeColor.Cyan,
                        trackColor = ComposeColor.White.copy(alpha = 0.3f),
                        modifier = Modifier
                            .fillMaxWidth(0.6f)
                            .padding(top = 8.dp)
                    )
                }
            }
        }
        
        // Bottom Panel
        BottomNavigationPanel(
            remainingDistance = mock?.totalRemainingDistance ?: 0.0,
            currentSpeed = mock?.currentSpeedKmh ?: 0.0,
            isRunning = mock?.isRunning ?: false,
            showDebug = showDebug,
            speedSlider = speedSlider,
            preparedGeometry = currentPreparedGeometry,
            viewportMeters = currentViewportMeters.toDouble(),
            tileApi = tileApi,
            roadCache = roadCache,
            mockState = mock,
            onSpeedChange = { newSpeed ->
                speedSlider = newSpeed
                MockLocationManager.speedKmh = newSpeed.toDouble()
            },
            onPauseResume = {
                if (mock?.isRunning == true) MockLocationManager.pause()
                else MockLocationManager.resume()
            },
            onStop = {
                MockLocationManager.stop()
                onNavigateBack()
            },
            onDebugToggle = { showDebug = !showDebug },
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
fun TurnIndicatorPanel(
    turnDirection: TurnDirection,
    distanceToTurn: Double,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.padding(16.dp),
        shape = RoundedCornerShape(16.dp),
        color = ComposeColor.Black.copy(alpha = 0.8f)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = turnDirection.toArrow(),
                fontSize = 48.sp,
                fontWeight = FontWeight.Bold,
                color = ComposeColor.White
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = turnDirection.toDescription(),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                    color = ComposeColor.White
                )
                Text(
                    text = formatDistance(distanceToTurn),
                    fontSize = 16.sp,
                    color = ComposeColor.White.copy(alpha = 0.8f)
                )
            }
        }
    }
}

@Composable
fun BottomNavigationPanel(
    remainingDistance: Double,
    currentSpeed: Double,
    isRunning: Boolean,
    showDebug: Boolean,
    speedSlider: Float,
    preparedGeometry: PreparedWatchGeometry?,
    viewportMeters: Double,
    tileApi: ProtomapsTileApi,
    roadCache: RoadCache,
    mockState: MockLocationState?,
    onSpeedChange: (Float) -> Unit,
    onPauseResume: () -> Unit,
    onStop: () -> Unit,
    onDebugToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 48.dp),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        color = ComposeColor.Black.copy(alpha = 0.9f)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Debug toggle
            IconButton(onClick = onDebugToggle) {
                Icon(
                    Icons.Default.Speed,
                    contentDescription = "Debug",
                    tint = ComposeColor.White.copy(alpha = 0.5f)
                )
            }
            
            // Stats row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem(label = "Distance", value = formatDistance(remainingDistance))
                StatItem(label = "Speed", value = "${currentSpeed.roundToInt()} km/h")
                StatItem(label = "Time", value = if (currentSpeed > 0) formatDuration(remainingDistance / (currentSpeed / 3.6)) else "-- min")
            }
            
            // Debug controls (animated visibility)
            AnimatedVisibility(visible = showDebug) {
                Column(modifier = Modifier.padding(top = 16.dp)) {
                    MvtDebugPanel(
                        tileApi = tileApi,
                        roadCache = roadCache,
                        mockState = mockState,
                        modifier = Modifier.fillMaxWidth()
                    )
                    
                    Text(
                        text = "Mock Speed: ${speedSlider.roundToInt()} km/h",
                        color = ComposeColor.White,
                        fontSize = 14.sp
                    )
                    Slider(
                        value = speedSlider,
                        onValueChange = onSpeedChange,
                        valueRange = 5f..60f,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Button(onClick = onPauseResume) {
                            Icon(
                                if (isRunning) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = null
                            )
                            Text(if (isRunning) "Pause" else "Resume")
                        }

                        Button(onClick = onStop) {
                            Icon(Icons.Default.Stop, contentDescription = null)
                            Text("Stop")
                        }
                    }

                    // Watch preview debug section
                    preparedGeometry?.let { geometry ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "Watch Preview (${geometry.roadSegments.size} roads, ${geometry.estimatedRoadBytes}B, ${viewportMeters.toInt()}m)",
                                color = ComposeColor.White,
                                fontSize = 12.sp
                            )

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                                horizontalArrangement = Arrangement.Center
                            ) {
                                WatchDebugCanvas(
                                    geometry = geometry,
                                    viewportMeters = viewportMeters,
                                    modifier = Modifier.size(100.dp)
                                )
                            }

                            // Road class breakdown
                            val classBreakdown = geometry.roadSegments
                                .groupBy { it.roadClass }
                                .mapValues { it.value.size }
                            Text(
                                text = classBreakdown.entries
                                    .sortedBy { it.key.ordinal }
                                    .joinToString(" ") { "${it.key.name[0]}:${it.value}" },
                                color = ComposeColor.White.copy(alpha = 0.7f),
                                fontSize = 10.sp
                            )
                        }
                    } ?: run {
                        Text(
                            text = "No road data prepared",
                            color = ComposeColor.White.copy(alpha = 0.5f),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 16.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = ComposeColor.White
        )
        Text(
            text = label,
            fontSize = 12.sp,
            color = ComposeColor.White.copy(alpha = 0.7f)
        )
    }
}

private fun formatDistance(meters: Double): String {
    return when {
        meters < 1000 -> "${meters.roundToInt()} m"
        else -> String.format("%.1f km", meters / 1000)
    }
}

private fun formatDuration(seconds: Double): String {
    if (seconds.isNaN() || seconds.isInfinite() || seconds < 0) return "-- min"
    val totalMinutes = (seconds / 60).roundToInt()
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours > 0 -> "${hours}h ${minutes}min"
        else -> "${minutes} min"
    }
}

private fun createDirectionArrow(bearing: Float): Bitmap {
    val size = 96
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    
    canvas.save()
    canvas.rotate(bearing, size / 2f, size / 2f)
    
    // Draw outer circle (white background)
    val bgPaint = Paint().apply {
        isAntiAlias = true
        color = android.graphics.Color.WHITE
        style = Paint.Style.FILL
    }
    canvas.drawCircle(size / 2f, size / 2f, size / 2f - 2f, bgPaint)
    
    // Draw arrow (blue)
    val paint = Paint().apply {
        isAntiAlias = true
        color = android.graphics.Color.parseColor("#1976D2")
        style = Paint.Style.FILL
    }
    
    val path = android.graphics.Path()
    val cx = size / 2f
    val cy = size / 2f
    val arrowSize = size / 2f - 8f
    
    // Arrow pointing up (north)
    path.moveTo(cx, cy - arrowSize)
    path.lineTo(cx - arrowSize * 0.6f, cy + arrowSize * 0.6f)
    path.lineTo(cx, cy + arrowSize * 0.2f)
    path.lineTo(cx + arrowSize * 0.6f, cy + arrowSize * 0.6f)
    path.close()
    
    canvas.drawPath(path, paint)
    
    // Draw white outline
    val outlinePaint = Paint().apply {
        isAntiAlias = true
        color = android.graphics.Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    canvas.drawPath(path, outlinePaint)
    
    canvas.restore()
    
    return bitmap
}

private fun getCurrentTurnDirection(route: Route?, stepIndex: Int): TurnDirection {
    if (route == null) return TurnDirection.STRAIGHT
    
    // Collect all steps
    val allSteps = mutableListOf<Step>()
    for (leg in route.legs) {
        allSteps.addAll(leg.steps)
    }
    
    if (allSteps.isEmpty()) return TurnDirection.STRAIGHT
    
    // Show the maneuver for stepIndex + 1 (next upcoming turn)
    // But if we're at the last step, show that one
    val targetIndex = if (stepIndex < allSteps.size - 1) stepIndex + 1 else stepIndex
    
    // Skip "depart" type for the first step
    val startIndex = if (allSteps.isNotEmpty() && allSteps[0].maneuver.type == "depart") 1 else 0
    val actualTarget = targetIndex.coerceAtLeast(startIndex)
    
    if (actualTarget < allSteps.size) {
        val maneuver = allSteps[actualTarget].maneuver
        // Skip "arrive" type
        if (maneuver.type == "arrive") {
            return TurnDirection.STRAIGHT
        }
        return maneuver.toTurnDirection()
    }
    
    return TurnDirection.STRAIGHT
}

private fun computeViewportMeters(mapView: MapView): Double {
    val zoom = mapView.zoomLevelDouble
    val screenMeters = 360.0 / (256.0 * Math.pow(2.0, zoom)) * 111320.0
    val screenWidth = mapView.width.toDouble().coerceAtLeast(1.0)
    val tilePixels = 256.0
    val metersPerPixel = screenMeters / tilePixels
    val viewportMeters = metersPerPixel * screenWidth
    return viewportMeters.coerceIn(50.0, 500.0)
}

@Composable
private fun WatchDebugCanvas(
    geometry: PreparedWatchGeometry,
    viewportMeters: Double,
    modifier: Modifier = Modifier
) {
    val routeColor = ComposeColor.Yellow
    val roadColor = ComposeColor(0xFF757575)

    Canvas(
        modifier = modifier
            .background(ComposeColor.Black)
    ) {
        val width = size.width
        val height = size.height
        val centerX = width / 2f
        val centerY = height / 2f
        val padding = 2f
        val usableWidth = width - 2 * padding
        val usableHeight = height - 2 * padding

        val halfViewport = viewportMeters / 2.0

        fun toScreenOffset(xMeters: Double, yMeters: Double): Offset {
            val nx = (xMeters / halfViewport * 0.5 + 0.5).coerceIn(0.0, 1.0)
            val ny = (-yMeters / halfViewport * 0.7 + 0.8).coerceIn(0.0, 1.0)
            return Offset(
                padding + (nx * usableWidth).toFloat(),
                padding + (ny * usableHeight).toFloat()
            )
        }

        // Draw road segments
        for (segment in geometry.roadSegments) {
            if (segment.points.size < 2) continue
            val path = androidx.compose.ui.graphics.Path()
            val first = toScreenOffset(segment.points.first().xMeters, segment.points.first().yMeters)
            path.moveTo(first.x, first.y)
            for (i in 1 until segment.points.size) {
                val point = toScreenOffset(segment.points[i].xMeters, segment.points[i].yMeters)
                path.lineTo(point.x, point.y)
            }
            drawPath(
                path = path,
                color = roadColor,
                style = Stroke(width = segment.roadClass.previewWidthPx * 0.5f)
            )
        }

        // Draw route polyline
        if (geometry.routePoints.size >= 2) {
            val routePath = androidx.compose.ui.graphics.Path()
            val firstRoute = toScreenOffset(geometry.routePoints.first().xMeters, geometry.routePoints.first().yMeters)
            routePath.moveTo(firstRoute.x, firstRoute.y)
            for (i in 1 until geometry.routePoints.size) {
                val point = toScreenOffset(geometry.routePoints[i].xMeters, geometry.routePoints[i].yMeters)
                routePath.lineTo(point.x, point.y)
            }
            drawPath(
                path = routePath,
                color = routeColor,
                style = Stroke(width = 3f)
            )
        }

        // Draw center point (current location)
        drawCircle(color = ComposeColor.Green, radius = 4f, center = Offset(centerX, height * 0.8f))
    }
}

private fun calculateBearing(from: LatLng, to: LatLng): Float {
    val lat1 = Math.toRadians(from.lat)
    val lat2 = Math.toRadians(to.lat)
    val dLng = Math.toRadians(to.lng - from.lng)
    
    val x = sin(dLng) * cos(lat2)
    val y = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLng)
    
    var bearing = Math.toDegrees(atan2(x, y)).toFloat()
    bearing = (bearing + 360) % 360
    return bearing
}

@Composable
private fun MvtDebugPanel(
    tileApi: ProtomapsTileApi,
    roadCache: RoadCache,
    mockState: MockLocationState?,
    modifier: Modifier = Modifier
) {
    var tileInfo by remember { mutableStateOf<ProtomapsTileApi.TileDebugInfo?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var manualZ by remember { mutableStateOf("15") }
    var manualX by remember { mutableStateOf("") }
    var manualY by remember { mutableStateOf("") }

    val tileDebugInfo by roadCache.tileDebugInfo.collectAsState()

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(8.dp),
        shape = RoundedCornerShape(8.dp),
        color = ComposeColor(0xFF1A1A1A)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "MVT Tile Debug",
                color = ComposeColor.Cyan,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Current Tiles (${tileDebugInfo.size}):",
                        color = ComposeColor.White,
                        fontSize = 10.sp
                    )
                    tileDebugInfo.forEach { info ->
                        Text(
                            text = "${info.z}/${info.x}/${info.y} ${info.status.name} roads=${info.roadCount}",
                            color = when (info.status) {
                                RoadCache.TileStatus.CACHED -> ComposeColor.Green
                                RoadCache.TileStatus.FETCHING -> ComposeColor.Yellow
                                RoadCache.TileStatus.MISSING -> ComposeColor.Red
                            },
                            fontSize = 9.sp
                        )
                    }
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "Manual Query:",
                        color = ComposeColor.White,
                        fontSize = 10.sp
                    )
                    Row {
                        OutlinedTextField(
                            value = manualZ,
                            onValueChange = { manualZ = it },
                            modifier = Modifier.width(48.dp),
                            textStyle = androidx.compose.ui.text.TextStyle(color = ComposeColor.White, fontSize = 10.sp),
                            singleLine = true
                        )
                        Text("-", color = ComposeColor.White, fontSize = 10.sp)
                        OutlinedTextField(
                            value = manualX,
                            onValueChange = { manualX = it },
                            modifier = Modifier.width(58.dp),
                            textStyle = androidx.compose.ui.text.TextStyle(color = ComposeColor.White, fontSize = 10.sp),
                            singleLine = true
                        )
                        Text("-", color = ComposeColor.White, fontSize = 10.sp)
                        OutlinedTextField(
                            value = manualY,
                            onValueChange = { manualY = it },
                            modifier = Modifier.width(58.dp),
                            textStyle = androidx.compose.ui.text.TextStyle(color = ComposeColor.White, fontSize = 10.sp),
                            singleLine = true
                        )
                    }
                    TextButton(
                        onClick = {
                            val z = manualZ.toIntOrNull() ?: 15
                            val x = manualX.toIntOrNull()
                            val y = manualY.toIntOrNull()
                            if (x != null && y != null) {
                                isLoading = true
                                kotlinx.coroutines.GlobalScope.launch {
                                    tileInfo = tileApi.fetchDebugTileInfo(z, x, y)
                                    isLoading = false
                                }
                            }
                        },
                        modifier = Modifier.height(28.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, tint = ComposeColor.Cyan, modifier = Modifier.size(14.dp))
                        Text("Fetch", color = ComposeColor.Cyan, fontSize = 10.sp)
                    }
                }
            }

            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = ComposeColor.Cyan)
            }

            tileInfo?.let { info ->
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    Text(
                        text = "Tile ${info.z}/${info.x}/${info.y}:",
                        color = ComposeColor.Yellow,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (info.fetchError != null) {
                        Text(
                            text = "ERROR: ${info.fetchError}",
                            color = ComposeColor.Red,
                            fontSize = 10.sp
                        )
                    } else {
                        Text(
                            text = "Layers: ${info.layerNames.joinToString(", ")}",
                            color = ComposeColor.White,
                            fontSize = 9.sp
                        )
                        Text(
                            text = "Features: ${info.featureCountByLayer.entries.joinToString(", ") { "${it.key}=${it.value}" }}",
                            color = ComposeColor.White,
                            fontSize = 9.sp
                        )
                        Text(
                            text = "Roads: ${info.roadCount}, Highway tags: ${info.highwayTagValues.take(10).joinToString(", ")}",
                            color = ComposeColor.Green,
                            fontSize = 9.sp
                        )
                    }
                }
            }

            mockState?.let { m ->
                val (cx, cy) = latLngToTileCoord(m.currentPosition)
                Text(
                    text = "Current tile: $cx/$cy (z15)",
                    color = ComposeColor.Gray,
                    fontSize = 9.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

private fun latLngToTileCoord(latLng: LatLng): Pair<Int, Int> {
    val zoom = 15
    val n = 1 shl zoom
    val x = ((latLng.lng + 180.0) / 360.0 * n).toInt()
    val latRad = Math.toRadians(latLng.lat)
    val y = ((1.0 - kotlin.math.ln(kotlin.math.tan(latRad) + 1.0 / kotlin.math.cos(latRad)) / Math.PI) / 2.0 * n).toInt()
    return x to y
}
