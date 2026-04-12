package com.pebblemaps.android.util

import android.net.Uri
import android.util.Log
import com.pebblemaps.android.domain.model.LatLng

data class DirectionsData(
    val start: LatLng?,
    val end: LatLng?
)

object GoogleMapsUrlParser {

    private const val TAG = "GoogleMapsUrlParser"
    private val COORD_REGEX = Regex("""(-?\d+\.\d+),\s*(-?\d+\.\d+)""")
    // Google Maps data-param encoding: !3dLAT!4dLNG
    private val DATA_COORD_REGEX = Regex("""!3d(-?\d+\.?\d*)!4d(-?\d+\.?\d*)""")

    fun parseDirectionsUrl(url: String): DirectionsData? {
        Log.d(TAG, "Parsing URL: $url")

        val uri = Uri.parse(url)
        val path = uri.path ?: ""

        // Query-param style: /maps/dir/?api=1&origin=...&destination=...
        if (uri.getQueryParameter("api") == "1" || uri.queryParameterNames.contains("destination")) {
            val origin = parseLatLng(uri.getQueryParameter("origin"))
            val destination = parseLatLng(uri.getQueryParameter("destination"))
            if (destination != null || origin != null) {
                return DirectionsData(start = origin, end = destination)
            }
        }

        // Path-segment style: /maps/dir/.../.../...
        if (path.startsWith("/maps/dir/")) {
            val segments = path.removePrefix("/maps/dir/")
                .split("/")
                .filter { it.isNotBlank() }

            val coordMatches = segments.mapNotNull { segment ->
                when {
                    segment.equals("Current+Location", ignoreCase = true) -> null
                    segment.equals("My+Location", ignoreCase = true) -> null
                    else -> COORD_REGEX.find(segment)?.let { match ->
                        LatLng(
                            match.groupValues[1].toDouble(),
                            match.groupValues[2].toDouble()
                        )
                    }
                }
            }.toMutableList()

            // Also look for !3d...!4d... coordinates anywhere in the path/query
            val dataCoords = extractDataCoords(url)
            coordMatches.addAll(dataCoords)

            val unique = coordMatches.distinctBy { "${it.lat},${it.lng}" }

            val hasTextOrigin = segments.firstOrNull()?.let {
                it.equals("Current+Location", ignoreCase = true) ||
                        it.equals("My+Location", ignoreCase = true)
            } == true

            return when {
                unique.isEmpty() -> null
                unique.size == 1 -> DirectionsData(start = null, end = unique[0])
                hasTextOrigin -> DirectionsData(start = null, end = unique[0])
                else -> DirectionsData(start = unique[0], end = unique[1])
            }
        }

        // Fallback: just search for any coordinates in the entire URL
        val allMatches = (COORD_REGEX.findAll(url) + DATA_COORD_REGEX.findAll(url))
            .map { match ->
                LatLng(match.groupValues[1].toDouble(), match.groupValues[2].toDouble())
            }
            .distinctBy { "${it.lat},${it.lng}" }
            .toList()

        return when (allMatches.size) {
            0 -> null
            1 -> DirectionsData(start = null, end = allMatches[0])
            else -> DirectionsData(start = allMatches[0], end = allMatches[1])
        }
    }

    private fun extractDataCoords(url: String): List<LatLng> {
        return DATA_COORD_REGEX.findAll(url).map { match ->
            LatLng(match.groupValues[1].toDouble(), match.groupValues[2].toDouble())
        }.toList()
    }

    private fun parseLatLng(value: String?): LatLng? {
        if (value.isNullOrBlank()) return null
        val match = COORD_REGEX.find(value) ?: return null
        return LatLng(match.groupValues[1].toDouble(), match.groupValues[2].toDouble())
    }
}
