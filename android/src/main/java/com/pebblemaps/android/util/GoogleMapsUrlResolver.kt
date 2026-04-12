package com.pebblemaps.android.util

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.isSuccess

class GoogleMapsUrlResolver(private val client: HttpClient) {

    companion object {
        private const val TAG = "GoogleMapsUrlResolver"
    }

    suspend fun resolveShortUrl(shortUrl: String): String? {
        return try {
            // Ktor Android engine follows redirects by default,
            // but we can use the response call.request.url to see final url.
            // However, with default redirect following, the final URL is in response.call.request.url
            val response: HttpResponse = client.get(shortUrl) {
                // Ensure we follow redirects
            }
            if (response.status.isSuccess()) {
                val finalUrl = response.call.request.url.toString()
                Log.d(TAG, "Resolved $shortUrl -> $finalUrl")
                finalUrl
            } else {
                Log.w(TAG, "Non-success status ${response.status} for $shortUrl")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve $shortUrl: ${e.message}")
            null
        }
    }
}
