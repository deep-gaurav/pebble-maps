package com.pebblemaps.android.ui.navigation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.pebblemaps.android.domain.model.LocationManager
import com.pebblemaps.android.domain.model.RouteProfile
import com.google.android.gms.location.LocationServices
import org.koin.androidx.compose.koinViewModel
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

@Composable
fun NavigationScreen(
    onNavigateBack: () -> Unit,
    onNavigateToPreview: () -> Unit,
    onStartNavigation: () -> Unit,
    viewModel: NavigationViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    var hasLocationPermission by remember { mutableStateOf(false) }
    var profileMenuExpanded by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasLocationPermission = permissions.values.all { it }
    }

    LaunchedEffect(Unit) {
        val fineLocation = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarseLocation = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        hasLocationPermission = fineLocation == PackageManager.PERMISSION_GRANTED ||
                coarseLocation == PackageManager.PERMISSION_GRANTED

        if (!hasLocationPermission) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 48.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
        ) {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }

            Text(
                text = "Navigation",
                modifier = Modifier.weight(1f).padding(8.dp)
            )

            TextButton(onClick = onNavigateToPreview) {
                Text("Preview")
            }
        }

        var startMarkerPos by remember { mutableStateOf<GeoPoint?>(null) }
        var endMarkerPos by remember { mutableStateOf<GeoPoint?>(null) }

        Box(modifier = Modifier.weight(1f)) {
            OSMNavigationMapView(
                context = context,
                hasLocationPermission = hasLocationPermission,
                route = state.route,
                startLocation = startMarkerPos,
                endLocation = endMarkerPos,
                onMapTap = { point ->
                    if (startMarkerPos == null) {
                        startMarkerPos = point
                        viewModel.setStartLocation(point.latitude, point.longitude)
                    } else if (endMarkerPos == null) {
                        endMarkerPos = point
                        viewModel.setEndLocation(point.latitude, point.longitude)
                    }
                }
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(16.dp)
        ) {
            Text("Profile: ${state.profile.name}")
            DropdownMenu(
                expanded = profileMenuExpanded,
                onDismissRequest = { profileMenuExpanded = false }
            ) {
                RouteProfile.entries.forEach { profile ->
                    DropdownMenuItem(
                        text = { Text(profile.name) },
                        onClick = {
                            viewModel.setProfile(profile)
                            profileMenuExpanded = false
                        }
                    )
                }
            }

            if (state.isLoading) {
                Text("Calculating route...")
            } else if (state.error != null) {
                Text("Error: ${state.error}")
            } else if (state.route != null) {
                val currentStep = state.currentStep
                Text(
                    text = currentStep?.maneuver?.type?.replaceFirstChar { it.uppercase() } ?: ""
                )
                Text(text = "Distance: ${String.format("%.1f", state.currentStep?.distance ?: 0.0)} m")
            }

            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Button(
                    onClick = {
                        startMarkerPos = null
                        endMarkerPos = null
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Clear")
                }

                Button(
                    onClick = { viewModel.calculateRoute() },
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp),
                    enabled = startMarkerPos != null && endMarkerPos != null
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Text("Calculate", modifier = Modifier.padding(start = 4.dp))
                }
            }

            if (state.route != null) {
                Button(
                    onClick = onStartNavigation,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Text("Start Navigation", modifier = Modifier.padding(start = 4.dp))
                }
            }
        }
    }
}

@Composable
fun OSMNavigationMapView(
    context: Context,
    hasLocationPermission: Boolean,
    route: com.pebblemaps.android.domain.model.Route?,
    startLocation: GeoPoint?,
    endLocation: GeoPoint?,
    onMapTap: (GeoPoint) -> Unit
) {
    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(15.0)
        }
    }

    val eventsReceiver = remember {
        object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                onMapTap(p)
                return true
            }
            override fun longPressHelper(p: GeoPoint): Boolean = false
        }
    }

    var initialCentered by remember { mutableStateOf(false) }

    fun centerMapOnLocation() {
        if (initialCentered) return
        
        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (location != null) {
                val geoPoint = GeoPoint(location.latitude, location.longitude)
                mapView.controller.animateTo(geoPoint)
                LocationManager.updateLocation(location.latitude, location.longitude)
                initialCentered = true
            } else {
                LocationManager.getLastLocation()?.let { lastLoc ->
                    mapView.controller.animateTo(GeoPoint(lastLoc.lat, lastLoc.lng))
                    initialCentered = true
                }
            }
        }.addOnFailureListener {
            LocationManager.getLastLocation()?.let { lastLoc ->
                mapView.controller.animateTo(GeoPoint(lastLoc.lat, lastLoc.lng))
                initialCentered = true
            }
        }
    }

    LaunchedEffect(hasLocationPermission) {
        centerMapOnLocation()
    }

    DisposableEffect(Unit) {
        val eventsOverlay = MapEventsOverlay(eventsReceiver)
        mapView.overlays.add(0, eventsOverlay)

        onDispose {
            try {
                mapView.onDetach()
            } catch (e: Exception) {
                // Ignore cleanup errors
            }
        }
    }

    AndroidView(
        factory = { mapView },
        update = { view ->
            view.overlays.clear()

            val eventsOverlay = MapEventsOverlay(eventsReceiver)
            view.overlays.add(0, eventsOverlay)

            startLocation?.let { start ->
                val marker = Marker(view)
                marker.position = start
                marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                marker.title = "Start"
                view.overlays.add(marker)
            }

            endLocation?.let { end ->
                val marker = Marker(view)
                marker.position = end
                marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                marker.title = "End"
                view.overlays.add(marker)
            }

            route?.let { r ->
                val polyline = Polyline()
                polyline.setPoints(r.geometry.coordinates.map {
                    GeoPoint(it.lat, it.lng)
                })
                view.overlays.add(polyline)
            }

            view.invalidate()
        },
        modifier = Modifier.fillMaxSize()
    )
}