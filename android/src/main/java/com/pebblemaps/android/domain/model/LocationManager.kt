package com.pebblemaps.android.domain.model

object LocationManager {
    private var lastLocation: LatLng? = null

    fun updateLocation(lat: Double, lng: Double) {
        lastLocation = LatLng(lat, lng)
    }

    fun getLastLocation(): LatLng? = lastLocation
}