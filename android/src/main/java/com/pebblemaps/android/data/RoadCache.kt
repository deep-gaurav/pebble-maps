package com.pebblemaps.android.data

import android.util.Log
import com.pebblemaps.android.data.remote.OverpassApi
import com.pebblemaps.android.domain.model.LatLng
import com.pebblemaps.android.domain.model.RoadSegment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

class RoadCache(private val overpassApi: OverpassApi) {

    companion object {
        private const val TAG = "RoadCache"
        private const val CELL_SIZE_METERS = 180.0
        private const val CELL_RADIUS_METERS = 130.0
        private const val MAX_CACHED_CELLS = 48
        private const val MIN_FETCH_INTERVAL_MS = 2000L
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val cellCache = linkedMapOf<CellKey, CachedCell>()
    private val refreshingCells = mutableSetOf<CellKey>()
    private val lastFetchTime = mutableMapOf<CellKey, Long>()
    
    private val _prefetchProgress = MutableStateFlow(0f)
    val prefetchProgress: StateFlow<Float> = _prefetchProgress.asStateFlow()
    
    private val _isPrefetching = MutableStateFlow(false)
    val isPrefetching: StateFlow<Boolean> = _isPrefetching.asStateFlow()

    fun getRoads(location: LatLng, bearing: Float, viewportMeters: Double): List<RoadSegment> {
        val keys = desiredCells(location, bearing, viewportMeters)
        return keys
            .asSequence()
            .mapNotNull { cellCache[it]?.roads }
            .flatten()
            .distinctBy { roadSignature(it) }
            .toList()
    }

    fun refreshIfNeeded(location: LatLng, bearing: Float, viewportMeters: Double) {
        val now = System.currentTimeMillis()
        desiredCells(location, bearing, viewportMeters).forEach { key ->
            if (cellCache[key] != null) return@forEach
            
            val lastFetch = lastFetchTime[key]
            if (lastFetch != null && now - lastFetch < MIN_FETCH_INTERVAL_MS) return@forEach
            
            if (!refreshingCells.add(key)) return@forEach

            scope.launch {
                try {
                    val center = cellCenter(key, location.lat)
                    val roads = withContext(Dispatchers.IO) {
                        overpassApi.fetchRoads(center, CELL_RADIUS_METERS)
                    }
                    cellCache[key] = CachedCell(roads = roads, fetchedAtMs = System.currentTimeMillis())
                    lastFetchTime[key] = now
                    trimCache(keysToKeep = desiredCells(location, bearing, viewportMeters).toSet())
                    Log.d(TAG, "Fetched ${roads.size} roads for cell=$key")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to fetch roads for $key: ${e.message}")
                } finally {
                    refreshingCells.remove(key)
                }
            }
        }
    }

    suspend fun refreshIfNeededAndWait(location: LatLng, bearing: Float, viewportMeters: Double) {
        val now = System.currentTimeMillis()
        val keys = desiredCells(location, bearing, viewportMeters)
        
        val jobs = mutableListOf<Job>()
        
        keys.forEach { key ->
            if (cellCache[key] != null) return@forEach
            if (!refreshingCells.add(key)) return@forEach
            
            val job = scope.launch {
                try {
                    val center = cellCenter(key, location.lat)
                    val roads = withContext(Dispatchers.IO) {
                        overpassApi.fetchRoads(center, CELL_RADIUS_METERS)
                    }
                    cellCache[key] = CachedCell(roads = roads, fetchedAtMs = System.currentTimeMillis())
                    lastFetchTime[key] = now
                    Log.d(TAG, "Pre-cached ${roads.size} roads for cell=$key")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to pre-cache roads for $key: ${e.message}")
                } finally {
                    refreshingCells.remove(key)
                }
            }
            jobs.add(job)
        }
        
        trimCache(keys.toSet())
        
        if (jobs.isNotEmpty()) {
            Log.d(TAG, "Waiting for ${jobs.size} cell fetches to complete...")
            jobs.forEach { it.join() }
            Log.d(TAG, "All pre-cache fetches complete")
        }
    }

    suspend fun prefetchEntireRoute(coordinates: List<LatLng>, viewportMeters: Double) {
        if (coordinates.isEmpty()) {
            Log.d(TAG, "Prefetch skipped: empty coordinates")
            return
        }
        
        val allKeys = mutableSetOf<CellKey>()
        val step = maxOf(1, coordinates.size / 20)
        for (i in coordinates.indices step step) {
            val location = coordinates[i]
            allKeys.addAll(desiredCells(location, 0f, viewportMeters))
        }
        
        val keysList = allKeys.toList()
        if (keysList.isEmpty()) {
            Log.d(TAG, "Prefetch skipped: no cells needed")
            return
        }
        
        Log.d(TAG, "Prefetch starting: ${keysList.size} cells")
        _isPrefetching.value = true
        _prefetchProgress.value = 0f
        
        val now = System.currentTimeMillis()
        val completed = AtomicInteger(0)
        val jobs = mutableListOf<Job>()
        
        keysList.forEach { key ->
            if (cellCache[key] != null) {
                completed.incrementAndGet()
                return@forEach
            }
            if (!refreshingCells.add(key)) {
                completed.incrementAndGet()
                return@forEach
            }
            
            jobs.add(scope.launch {
                try {
                    val center = cellCenter(key, coordinates.first().lat)
                    val roads = withContext(Dispatchers.IO) {
                        overpassApi.fetchRoads(center, CELL_RADIUS_METERS)
                    }
                    cellCache[key] = CachedCell(roads = roads, fetchedAtMs = System.currentTimeMillis())
                    lastFetchTime[key] = now
                    Log.d(TAG, "Route pre-fetch: ${roads.size} roads for cell=$key")
                } catch (e: Exception) {
                    Log.e(TAG, "Route pre-fetch failed for $key: ${e.message}")
                } finally {
                    refreshingCells.remove(key)
                    completed.incrementAndGet()
                }
            })
        }
        
        jobs.forEach { it.join() }
        _prefetchProgress.value = 1f
        _isPrefetching.value = false
        Log.d(TAG, "Route pre-fetch complete: ${keysList.size} cells, ${jobs.size} fetched")
    }

    private fun trimCache(keysToKeep: Set<CellKey>) {
        if (cellCache.size <= MAX_CACHED_CELLS) return

        val removable = cellCache.keys
            .filterNot { it in keysToKeep }
            .sortedBy { cellCache[it]?.fetchedAtMs ?: Long.MAX_VALUE }

        var idx = 0
        while (cellCache.size > MAX_CACHED_CELLS && idx < removable.size) {
            cellCache.remove(removable[idx])
            idx++
        }
    }

    private fun desiredCells(location: LatLng, bearing: Float, viewportMeters: Double): List<CellKey> {
        val centerKey = cellKey(location)
        val forwardOffset = offset(location, bearing, viewportMeters * 0.75)
        val forwardKey = cellKey(forwardOffset)
        val lateralLeft = offset(location, bearing - 90f, viewportMeters * 0.45)
        val lateralRight = offset(location, bearing + 90f, viewportMeters * 0.45)

        return linkedSetOf<CellKey>().apply {
            add(centerKey)
            addAll(neighborCells(centerKey))
            add(forwardKey)
            addAll(neighborCells(forwardKey, radius = 1))
            add(cellKey(lateralLeft))
            add(cellKey(lateralRight))
        }.toList()
    }

    private fun neighborCells(center: CellKey, radius: Int = 1): List<CellKey> {
        val cells = mutableListOf<CellKey>()
        for (dx in -radius..radius) {
            for (dy in -radius..radius) {
                cells += CellKey(center.x + dx, center.y + dy)
            }
        }
        return cells
    }

    private fun cellKey(location: LatLng): CellKey {
        val latMeters = location.lat * 111320.0
        val lngMeters = location.lng * metersPerLng(location.lat)
        return CellKey(
            x = floor(lngMeters / CELL_SIZE_METERS).toInt(),
            y = floor(latMeters / CELL_SIZE_METERS).toInt()
        )
    }

    private fun cellCenter(key: CellKey, referenceLat: Double): LatLng {
        val latMeters = (key.y + 0.5) * CELL_SIZE_METERS
        val lngMeters = (key.x + 0.5) * CELL_SIZE_METERS
        val lat = latMeters / 111320.0
        val lng = lngMeters / metersPerLng(referenceLat)
        return LatLng(lat, lng)
    }

    private fun offset(origin: LatLng, bearingDegrees: Float, distanceMeters: Double): LatLng {
        val radians = Math.toRadians(bearingDegrees.toDouble())
        val north = cos(radians) * distanceMeters
        val east = sin(radians) * distanceMeters
        return LatLng(
            lat = origin.lat + north / 111320.0,
            lng = origin.lng + east / metersPerLng(origin.lat)
        )
    }

    private fun metersPerLng(lat: Double): Double {
        return 111320.0 * cos(Math.toRadians(lat)).coerceAtLeast(0.01)
    }

    private fun roadSignature(road: RoadSegment): String {
        val first = road.points.firstOrNull() ?: return "empty"
        val last = road.points.lastOrNull() ?: return "empty"
        return buildString {
            append(road.roadClass.wireValue)
            append(':')
            append(road.points.size)
            append(':')
            append(first.lat)
            append(':')
            append(first.lng)
            append(':')
            append(last.lat)
            append(':')
            append(last.lng)
        }
    }

    private data class CellKey(val x: Int, val y: Int)

    private data class CachedCell(
        val roads: List<RoadSegment>,
        val fetchedAtMs: Long
    )
}
