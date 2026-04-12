package com.pebblemaps.android.data

import android.util.Log
import com.pebblemaps.android.data.remote.ProtomapsTileApi
import com.pebblemaps.android.domain.model.LatLng
import com.pebblemaps.android.domain.model.RoadSegment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.tan

class RoadCache(private val tileApi: ProtomapsTileApi) {

    companion object {
        private const val TAG = "RoadCache"
        private const val TILE_ZOOM = 15
        private const val MAX_CACHED_TILES = 128
        private const val MIN_FETCH_INTERVAL_MS = 500L
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val tileCache = linkedMapOf<TileKey, CachedTile>()
    private val refreshingTiles = mutableSetOf<TileKey>()
    private val lastFetchTime = mutableMapOf<TileKey, Long>()

    private val _prefetchProgress = MutableStateFlow(0f)
    val prefetchProgress: StateFlow<Float> = _prefetchProgress.asStateFlow()

    private val _isPrefetching = MutableStateFlow(false)
    val isPrefetching: StateFlow<Boolean> = _isPrefetching.asStateFlow()

    private val latestDesiredKeys = AtomicReference<Set<TileKey>>(emptySet())
    private val _nearbyRoads = MutableStateFlow<List<RoadSegment>>(emptyList())
    val nearbyRoads: StateFlow<List<RoadSegment>> = _nearbyRoads.asStateFlow()

    private val _tileDebugInfo = MutableStateFlow<List<TileDebugInfo>>(emptyList())
    val tileDebugInfo: StateFlow<List<TileDebugInfo>> = _tileDebugInfo.asStateFlow()

    data class TileDebugInfo(
        val z: Int,
        val x: Int,
        val y: Int,
        val status: TileStatus,
        val roadCount: Int = 0,
        val layerNames: List<String> = emptyList()
    ) {
        override fun toString() = "$z/$x/$y"
    }

    enum class TileStatus { CACHED, FETCHING, MISSING }

    fun getRoads(location: LatLng, bearing: Float, viewportMeters: Double): List<RoadSegment> {
        val keys = desiredTiles(location, bearing, viewportMeters)
        val keySet = keys.toSet()
        latestDesiredKeys.set(keySet)
        trimCache(keySet)
        val roads = keys
            .asSequence()
            .mapNotNull { tileCache[it]?.roads }
            .flatten()
            .distinctBy { roadSignature(it) }
            .toList()
        _nearbyRoads.value = roads
        return roads
    }

    fun refreshIfNeeded(location: LatLng, bearing: Float, viewportMeters: Double) {
        val keys = desiredTiles(location, bearing, viewportMeters)
        val keySet = keys.toSet()
        latestDesiredKeys.set(keySet)
        trimCache(keySet)
        updateTileDebugInfo(keys)
        val missingCount = keys.count { tileCache[it] == null }
        Log.d("PebbleMapsRoads", "refreshIfNeeded: ${keys.size} desired tiles, $missingCount missing")
        if (missingCount == 0) {
            recomputeNearbyRoads()
        }
        val now = System.currentTimeMillis()
        keys.forEach { key ->
            if (tileCache[key] != null) return@forEach

            val lastFetch = lastFetchTime[key]
            if (lastFetch != null && now - lastFetch < MIN_FETCH_INTERVAL_MS) return@forEach

            if (!refreshingTiles.add(key)) return@forEach

            scope.launch {
                try {
                    val roads = withContext(Dispatchers.IO) {
                        tileApi.fetchRoadsForTile(key.z, key.x, key.y)
                    }
                    tileCache[key] = CachedTile(roads = roads, fetchedAtMs = System.currentTimeMillis())
                    lastFetchTime[key] = now
                    recomputeNearbyRoads()
                    Log.d("PebbleMapsRoads", "Tile fetched $key: ${roads.size} roads")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to fetch tile $key: ${e.message}")
                } finally {
                    refreshingTiles.remove(key)
                }
            }
        }
    }

    private fun updateTileDebugInfo(keys: List<TileKey>) {
        val info = keys.map { key ->
            val status = when {
                tileCache[key] != null -> TileStatus.CACHED
                refreshingTiles.contains(key) -> TileStatus.FETCHING
                else -> TileStatus.MISSING
            }
            val roadCount = tileCache[key]?.roads?.size ?: 0
            TileDebugInfo(key.z, key.x, key.y, status, roadCount)
        }
        _tileDebugInfo.value = info
    }

    suspend fun refreshIfNeededAndWait(location: LatLng, bearing: Float, viewportMeters: Double) {
        val keys = desiredTiles(location, bearing, viewportMeters)
        val keySet = keys.toSet()
        latestDesiredKeys.set(keySet)
        trimCache(keySet)
        val now = System.currentTimeMillis()
        val jobs = mutableListOf<Job>()

        keys.forEach { key ->
            if (tileCache[key] != null) return@forEach
            if (!refreshingTiles.add(key)) return@forEach

            val job = scope.launch {
                try {
                    val roads = withContext(Dispatchers.IO) {
                        tileApi.fetchRoadsForTile(key.z, key.x, key.y)
                    }
                    tileCache[key] = CachedTile(roads = roads, fetchedAtMs = System.currentTimeMillis())
                    lastFetchTime[key] = now
                    Log.d(TAG, "Pre-cached ${roads.size} roads for tile=$key")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to pre-cache tile $key: ${e.message}")
                } finally {
                    refreshingTiles.remove(key)
                }
            }
            jobs.add(job)
        }

        if (jobs.isNotEmpty()) {
            Log.d(TAG, "Waiting for ${jobs.size} tile fetches to complete...")
            jobs.forEach { it.join() }
            recomputeNearbyRoads()
            Log.d(TAG, "All pre-cache fetches complete")
        }
    }

    suspend fun prefetchEntireRoute(coordinates: List<LatLng>, viewportMeters: Double) {
        if (coordinates.isEmpty()) {
            Log.d(TAG, "Prefetch skipped: empty coordinates")
            return
        }

        val allKeys = mutableSetOf<TileKey>()
        val step = maxOf(1, coordinates.size / 20)
        for (i in coordinates.indices step step) {
            val location = coordinates[i]
            allKeys.addAll(desiredTiles(location, 0f, viewportMeters))
        }

        val keysList = allKeys.toList()
        if (keysList.isEmpty()) {
            Log.d(TAG, "Prefetch skipped: no tiles needed")
            return
        }

        Log.d(TAG, "Prefetch starting: ${keysList.size} tiles")
        _isPrefetching.value = true
        _prefetchProgress.value = 0f

        val now = System.currentTimeMillis()
        val completed = AtomicInteger(0)
        val jobs = mutableListOf<Job>()
        val semaphore = Semaphore(3)

        keysList.forEach { key ->
            if (tileCache[key] != null) {
                completed.incrementAndGet()
                return@forEach
            }
            if (!refreshingTiles.add(key)) {
                completed.incrementAndGet()
                return@forEach
            }

            jobs.add(scope.launch {
                semaphore.withPermit {
                    try {
                        val roads = withContext(Dispatchers.IO) {
                            tileApi.fetchRoadsForTile(key.z, key.x, key.y)
                        }
                        tileCache[key] = CachedTile(roads = roads, fetchedAtMs = System.currentTimeMillis())
                        lastFetchTime[key] = now
                        Log.d(TAG, "Route pre-fetch: ${roads.size} roads for tile=$key")
                    } catch (e: Exception) {
                        Log.e(TAG, "Route pre-fetch failed for $key: ${e.message}")
                    } finally {
                        refreshingTiles.remove(key)
                        completed.incrementAndGet()
                    }
                }
            })
            delay(50)
        }

        jobs.forEach { it.join() }
        recomputeNearbyRoads()
        _prefetchProgress.value = 1f
        _isPrefetching.value = false
        val alreadyCached = keysList.size - jobs.size
        Log.d("PebbleMapsRoads", "Prefetch complete: ${keysList.size} tiles total, ${jobs.size} fetched, $alreadyCached already cached")
        Log.d(TAG, "Route pre-fetch complete: ${keysList.size} tiles, ${jobs.size} fetched")
    }

    private fun trimCache(keysToKeep: Set<TileKey>) {
        if (tileCache.size <= MAX_CACHED_TILES) return

        val removable = tileCache.keys
            .filterNot { it in keysToKeep }
            .sortedBy { tileCache[it]?.fetchedAtMs ?: Long.MAX_VALUE }

        var idx = 0
        while (tileCache.size > MAX_CACHED_TILES && idx < removable.size) {
            tileCache.remove(removable[idx])
            idx++
        }
    }

    private fun recomputeNearbyRoads() {
        val keys = latestDesiredKeys.get()
        val roads = keys
            .asSequence()
            .mapNotNull { tileCache[it]?.roads }
            .flatten()
            .distinctBy { roadSignature(it) }
            .toList()
        Log.d("PebbleMapsRoads", "Recomputed roads: ${roads.size} segments from ${keys.size} tiles")
        _nearbyRoads.value = roads
        updateTileDebugInfo(keys.toList())
    }

    private fun desiredTiles(location: LatLng, bearing: Float, viewportMeters: Double): List<TileKey> {
        val zoom = TILE_ZOOM
        val (cx, cy) = latLngToTile(location, zoom)
        return linkedSetOf<TileKey>().apply {
            for (dx in -1..1) {
                for (dy in -1..1) {
                    add(TileKey(zoom, cx + dx, cy + dy))
                }
            }
        }.toList()
    }

    private fun latLngToTile(latLng: LatLng, zoom: Int): Pair<Int, Int> {
        val n = 1 shl zoom
        val x = ((latLng.lng + 180.0) / 360.0 * n).toInt()
        val latRad = Math.toRadians(latLng.lat)
        val y = ((1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / Math.PI) / 2.0 * n).toInt()
        return x to y
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

    private data class TileKey(val z: Int, val x: Int, val y: Int) {
        override fun toString() = "$z/$x/$y"
    }

    private data class CachedTile(
        val roads: List<RoadSegment>,
        val fetchedAtMs: Long
    )
}
